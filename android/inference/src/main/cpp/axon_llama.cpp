// AXON — JNI bridge to llama.cpp (spec §7.3, §9.3; decision D1).
//
// Written against the llama.cpp submodule pinned in third_party/, API read from
// include/llama.h on 2026-08-11. Two things in that header had changed from what
// most published examples assume, which is why this file was written against the
// source rather than from memory:
//
//   • llama_model_params::use_mmap is GONE. Loading strategy is now
//     `enum llama_load_mode load_mode`, with LLAMA_LOAD_MODE_AUTO as the default
//     that "avoids mmap on iGPUs". §7.3 requires mmap unconditionally, so AXON
//     asks for it explicitly rather than accepting AUTO (see load_mode below).
//   • llama_sampler_sample() already calls llama_sampler_accept() internally.
//     Calling accept again would advance the grammar automaton twice per token
//     and corrupt every constrained generation after the first.
//
// This file exists at all because Llamatik exposes only a JSON-Schema constraint
// path with no way to pass raw GBNF — see docs/DECISIONS.md D1.

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"

#define LOG_TAG "AxonLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Indices into the long[] the Kotlin side passes in as an out-parameter.
// Returning a struct across JNI would mean constructing a Java object from C++;
// filling a caller-owned array keeps generate() a single call with no object
// plumbing, and keeps the stats layout in one place. Mirrored by
// NativeLlama.Stats — the two must be changed together.
enum Stat {
    STAT_PROMPT_TOKENS = 0,
    STAT_COMPLETION_TOKENS,
    STAT_PREFILL_MS,
    STAT_DECODE_MS,
    STAT_TOTAL_MS,
    STAT_TRUNCATED,       // hit maxTokens rather than stopping naturally
    STAT_STOPPED_ON_EOG,
    STAT_GRAMMAR_MS,      // time inside grammar-constrained sampling
    STAT_COUNT,
};

int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

void throw_java(JNIEnv *env, const char *cls, const std::string &msg) {
    jclass c = env->FindClass(cls);
    if (c != nullptr) {
        env->ThrowNew(c, msg.c_str());
    }
}

void throw_illegal_state(JNIEnv *env, const std::string &msg) {
    throw_java(env, "java/lang/IllegalStateException", msg);
}

std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Route llama.cpp's internal logging into logcat. Without this the library logs
// to stderr, which on Android goes nowhere — and a model that fails to load
// fails silently, which is a miserable thing to debug on a phone.
void log_callback(ggml_log_level level, const char *text, void * /*user_data*/) {
    if (text == nullptr) return;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: LOGE("%s", text); break;
        case GGML_LOG_LEVEL_WARN:  LOGW("%s", text); break;
        default:                   LOGI("%s", text); break;
    }
}

/**
 * Pump `stderr` into logcat.
 *
 * Not every llama.cpp diagnostic goes through the log callback installed by
 * llama_log_set. The GBNF parser in particular reports the *reason* a grammar
 * failed — "expecting name at …", "Undefined rule identifier …" — with a bare
 * `fprintf(stderr, …)`, while the callback only ever sees the generic "failed to
 * parse grammar". On Android stderr is discarded, so the useful half of that
 * message is invisible.
 *
 * That mattered here: the §10.6 grammar failed to parse on-device and the only
 * evidence available was five words. A grammar is the mechanism behind C3, so
 * being unable to see why one was rejected is not a tolerable blind spot.
 */
void redirect_stderr_to_logcat() {
    static bool started = false;
    if (started) return;
    started = true;

    static int pipe_fd[2];
    if (pipe(pipe_fd) != 0) return;

    setvbuf(stderr, nullptr, _IONBF, 0);
    dup2(pipe_fd[1], STDERR_FILENO);

    pthread_t thread;
    pthread_create(&thread, nullptr, [](void *) -> void * {
        char buf[512];
        ssize_t n;
        while ((n = read(pipe_fd[0], buf, sizeof(buf) - 1)) > 0) {
            if (buf[n - 1] == '\n') n--;
            buf[n] = '\0';
            if (n > 0) __android_log_write(ANDROID_LOG_ERROR, LOG_TAG, buf);
        }
        return nullptr;
    }, nullptr);
    pthread_detach(thread);
}

/**
 * A loaded model plus its context.
 *
 * Held as one handle because AXON never wants a model without a context, and
 * pairing them removes a whole class of use-after-free where Kotlin frees one
 * and keeps using the other.
 */
struct Session {
    llama_model   *model = nullptr;
    llama_context *ctx   = nullptr;
    const llama_vocab *vocab = nullptr;

    // Serialises generate() calls. llama_context is not thread-safe, and the
    // agent loop is coroutine-driven, so two planner calls could otherwise land
    // on it concurrently and corrupt the KV cache in a way that presents as the
    // model "going insane" mid-task.
    std::mutex mu;

    ~Session() {
        if (ctx != nullptr)   llama_free(ctx);
        if (model != nullptr) llama_model_free(model);
    }
};

Session *as_session(jlong handle) {
    return reinterpret_cast<Session *>(handle);
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    // Negative return = required buffer size. Ask once, allocate, ask again.
    int32_t needed = -llama_tokenize(
            vocab, text.data(), static_cast<int32_t>(text.size()),
            nullptr, 0, add_special, /*parse_special=*/true);
    if (needed <= 0) return {};

    std::vector<llama_token> tokens(needed);
    int32_t n = llama_tokenize(
            vocab, text.data(), static_cast<int32_t>(text.size()),
            tokens.data(), needed, add_special, /*parse_special=*/true);
    if (n < 0) return {};
    tokens.resize(n);
    return tokens;
}

std::string token_to_piece(const llama_vocab *vocab, llama_token token) {
    char buf[256];
    int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, /*special=*/false);
    if (n < 0) {
        std::vector<char> big(-n);
        n = llama_token_to_piece(vocab, token, big.data(), static_cast<int32_t>(big.size()), 0, false);
        if (n < 0) return {};
        return std::string(big.data(), n);
    }
    return std::string(buf, n);
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_dev_axon_android_inference_NativeLlama_nativeInit(JNIEnv * /*env*/, jobject /*thiz*/) {
    redirect_stderr_to_logcat();
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
    LOGI("llama backend initialised");
}

JNIEXPORT void JNICALL
Java_dev_axon_android_inference_NativeLlama_nativeFreeBackend(JNIEnv * /*env*/, jobject /*thiz*/) {
    llama_backend_free();
}

/**
 * Enumerate the compiled-in ggml backends.
 *
 * Exists for decision D8: §18 lists -DLLAMA_VULKAN=ON as a verified flag, but on
 * the target device (Mali-G52 MC2, driver r32p1) the Vulkan backend is likely
 * slower than CPU and historically unreliable. Rather than trust the flag, the
 * app reports which backends the loaded .so actually offers, so the CPU-vs-Vulkan
 * comparison in Phase 1 is measuring a fact rather than an assumption.
 */
JNIEXPORT jstring JNICALL
Java_dev_axon_android_inference_NativeLlama_nativeBackends(JNIEnv *env, jobject /*thiz*/) {
    std::string out;
    const size_t n = ggml_backend_dev_count();
    for (size_t i = 0; i < n; i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev == nullptr) continue;
        ggml_backend_dev_props props{};
        ggml_backend_dev_get_props(dev, &props);
        if (!out.empty()) out += "\n";
        out += std::string(props.name == nullptr ? "?" : props.name);
        out += "|";
        out += std::string(props.description == nullptr ? "" : props.description);
        out += "|";
        out += std::to_string(static_cast<int>(props.type));
        out += "|";
        out += std::to_string(props.memory_total);
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jlong JNICALL
Java_dev_axon_android_inference_NativeLlama_nativeLoadModel(
        JNIEnv *env, jobject /*thiz*/,
        jstring j_path, jint n_gpu_layers, jint n_ctx,
        jint n_threads, jint n_threads_batch) {

    const std::string path = jstring_to_std(env, j_path);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;

    // §7.3: "always mmap the weights — never heap-allocate the model, so the OS
    // pages under memory pressure instead of killing the app."
    //
    // Stated explicitly rather than left at LLAMA_LOAD_MODE_AUTO. AUTO disables
    // mmap when it detects an integrated GPU — which is every phone — so on the
    // Vulkan build AUTO would silently do the opposite of what §7.3 requires. On
    // a 6–8 GB device that is the difference between the OS reclaiming pages and
    // the OS killing the process.
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        throw_illegal_state(env, "failed to load model: " + path);
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    // Prefill and decode want different thread counts on big.LITTLE, and the
    // difference is measurable rather than theoretical. On the Helio G85 target
    // (2x Cortex-A75 + 6x A55), going from 4 to 8 threads moved prefill from
    // 12.6 to 15.6 tok/s but dropped decode from 2.0 to 1.5 tok/s: prompt
    // processing is compute-bound and parallelises across all cores, while
    // single-token decode is memory-bound and finishes only when the slowest
    // thread does — so the little cores hold it back.
    cparams.n_threads = n_threads;              // decode
    cparams.n_threads_batch = n_threads_batch;  // prefill

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        throw_illegal_state(env, "failed to create context for: " + path);
        return 0;
    }

    auto *session = new Session();
    session->model = model;
    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);

    LOGI("loaded model: %s (n_ctx=%d, threads=%d/%d, gpu_layers=%d)",
         path.c_str(), n_ctx, n_threads, n_threads_batch, n_gpu_layers);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_dev_axon_android_inference_NativeLlama_nativeFreeModel(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    delete as_session(handle);
}

JNIEXPORT jstring JNICALL
Java_dev_axon_android_inference_NativeLlama_nativeModelDesc(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    Session *s = as_session(handle);
    if (s == nullptr) return env->NewStringUTF("");
    char buf[512];
    int32_t n = llama_model_desc(s->model, buf, sizeof(buf));
    if (n < 0) return env->NewStringUTF("");
    return env->NewStringUTF(std::string(buf, n).c_str());
}

JNIEXPORT jstring JNICALL
Java_dev_axon_android_inference_NativeLlama_nativeModelMeta(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring j_key) {
    Session *s = as_session(handle);
    if (s == nullptr) return env->NewStringUTF("");
    const std::string key = jstring_to_std(env, j_key);
    char buf[512];
    int32_t n = llama_model_meta_val_str(s->model, key.c_str(), buf, sizeof(buf));
    if (n < 0) return env->NewStringUTF("");
    return env->NewStringUTF(std::string(buf, n).c_str());
}

/**
 * Format messages with the model's own chat template.
 *
 * Done in native code because the template ships inside the GGUF, so the correct
 * one is whatever the loaded model says it is. Hard-coding Gemma's turn markers
 * in Kotlin would silently produce a malformed prompt the moment the ablation
 * (§14.3) swaps in a different model for config E — and a malformed prompt does
 * not error, it just degrades quality, which is the worst way for a benchmark to
 * be wrong.
 */
JNIEXPORT jstring JNICALL
Java_dev_axon_android_inference_NativeLlama_nativeApplyChatTemplate(
        JNIEnv *env, jobject /*thiz*/, jlong handle,
        jstring j_system, jstring j_user, jstring j_fallback_template) {

    Session *s = as_session(handle);
    if (s == nullptr) {
        throw_illegal_state(env, "null session");
        return nullptr;
    }

    const std::string system = jstring_to_std(env, j_system);
    const std::string user = jstring_to_std(env, j_user);

    std::vector<llama_chat_message> messages;
    if (!system.empty()) {
        messages.push_back({"system", system.c_str()});
    }
    messages.push_back({"user", user.c_str()});

    // Try the GGUF's own template first, then a caller-named built-in.
    //
    // The fallback is not defensive padding — it is load-bearing for this
    // model. The Gemma 4 E2B GGUF carries no `tokenizer.chat_template` key, so
    // llama_model_chat_template returns NULL and the template call fails. Left
    // unhandled, the prompt goes to the model without turn markers, which does
    // not error — it just quietly degrades quality, and every measurement taken
    // afterwards would be of a misformatted prompt rather than of the model.
    const char *candidates[2] = {
        llama_model_chat_template(s->model, /*name=*/nullptr),
        nullptr,
    };
    const std::string fallback = jstring_to_std(env, j_fallback_template);
    if (!fallback.empty()) candidates[1] = fallback.c_str();

    std::vector<char> buf(2 * (system.size() + user.size()) + 1024);

    for (const char *tmpl : candidates) {
        if (tmpl == nullptr) continue;

        int32_t n = llama_chat_apply_template(
                tmpl, messages.data(), messages.size(), /*add_ass=*/true,
                buf.data(), static_cast<int32_t>(buf.size()));

        if (n > static_cast<int32_t>(buf.size())) {
            buf.resize(n + 1);
            n = llama_chat_apply_template(
                    tmpl, messages.data(), messages.size(), true,
                    buf.data(), static_cast<int32_t>(buf.size()));
        }
        if (n >= 0) {
            return env->NewStringUTF(std::string(buf.data(), n).c_str());
        }
    }

    LOGW("no usable chat template; falling back to raw prompt (quality will suffer)");
    return env->NewStringUTF((system.empty() ? user : system + "\n\n" + user).c_str());
}

/**
 * Generate, optionally under a GBNF grammar — the C3 mechanism (§7.4).
 *
 * ## Sampler chain order
 *
 * The grammar sampler goes at the **head** of the chain, ahead of top-k, top-p
 * and temperature:
 *
 *     [ grammar ] → top_k → top_p → temp → dist
 *
 * Order is load-bearing, not stylistic. The grammar sets every non-conforming
 * token's logit to -INF; the stochastic samplers then choose only among what
 * survives. Placed *after* top-k, the grammar would be masking an already-
 * truncated candidate list, and if all k survivors happened to be invalid there
 * would be nothing left to sample — the constraint would fail exactly when it
 * mattered most. Head position makes "malformed output is impossible" true
 * regardless of how the other samplers are tuned.
 *
 * The cost is honest and worth reporting: at head position the grammar is
 * evaluated against the full vocabulary (~262k tokens for Gemma) on every step,
 * where llama.cpp's own `common_sampler` uses an optimistic scheme that checks
 * one token first and only falls back to full masking on rejection. AXON takes
 * the slower, unconditionally-correct path because C3 is a claim about
 * correctness, and measures what it costs — STAT_GRAMMAR_MS is reported per
 * generation, so "constrained decoding costs X ms/token on a Helio G85" becomes
 * a result in §14.2 rather than an unexamined assumption.
 */
JNIEXPORT jstring JNICALL
Java_dev_axon_android_inference_NativeLlama_nativeGenerate(
        JNIEnv *env, jobject /*thiz*/,
        jlong handle,
        jstring j_prompt,
        jstring j_grammar,       // nullable — null means unconstrained
        jint max_tokens,
        jfloat temperature,
        jint top_k,
        jfloat top_p,
        jint seed,
        jlongArray j_stats) {

    Session *s = as_session(handle);
    if (s == nullptr) {
        throw_illegal_state(env, "null session");
        return nullptr;
    }

    const std::string prompt = jstring_to_std(env, j_prompt);
    const bool constrained = (j_grammar != nullptr);
    const std::string grammar = constrained ? jstring_to_std(env, j_grammar) : std::string();

    std::lock_guard<std::mutex> lock(s->mu);

    const int64_t t_start = now_ms();
    int64_t stats[STAT_COUNT] = {0};

    // --- build the sampler chain -------------------------------------------

    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (chain == nullptr) {
        throw_illegal_state(env, "failed to init sampler chain");
        return nullptr;
    }

    if (constrained) {
        llama_sampler *gsmpl = llama_sampler_init_grammar(s->vocab, grammar.c_str(), "root");
        if (gsmpl == nullptr) {
            // Returns NULL specifically when the GBNF fails to parse. Surfacing
            // this as an exception matters: a silently-dropped grammar would
            // produce an unconstrained run that still looks constrained, which
            // would quietly invalidate the §14.3 ablation by making arm B behave
            // like arm A while being labelled B.
            llama_sampler_free(chain);
            throw_illegal_state(env, "GBNF grammar failed to parse (root symbol 'root')");
            return nullptr;
        }
        llama_sampler_chain_add(chain, gsmpl);
    }

    if (temperature <= 0.0f) {
        // Greedy. Used by the acceptance harness so the 500-generation run is
        // reproducible rather than a different sample every time.
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }

    // --- prefill ------------------------------------------------------------

    // Fresh KV state per generation. Prefix reuse across planner steps is a
    // Phase 3 optimisation (see D8): the system prompt and goal are stable while
    // only the screen changes, so most of this prefill is re-done needlessly.
    // Correctness first, then measure, then cache.
    llama_memory_clear(llama_get_memory(s->ctx), /*data=*/true);

    std::vector<llama_token> tokens = tokenize(s->vocab, prompt, /*add_special=*/true);
    if (tokens.empty()) {
        llama_sampler_free(chain);
        throw_illegal_state(env, "prompt tokenised to zero tokens");
        return nullptr;
    }

    const uint32_t n_ctx = llama_n_ctx(s->ctx);
    if (tokens.size() + static_cast<size_t>(max_tokens) > n_ctx) {
        llama_sampler_free(chain);
        throw_illegal_state(env,
                "prompt (" + std::to_string(tokens.size()) + " tokens) + max_tokens (" +
                std::to_string(max_tokens) + ") exceeds context " + std::to_string(n_ctx));
        return nullptr;
    }

    const int64_t t_prefill_start = now_ms();
    {
        llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
        const int32_t rc = llama_decode(s->ctx, batch);
        if (rc != 0) {
            llama_sampler_free(chain);
            throw_illegal_state(env, "llama_decode failed on prefill, rc=" + std::to_string(rc));
            return nullptr;
        }
    }
    stats[STAT_PREFILL_MS] = now_ms() - t_prefill_start;
    stats[STAT_PROMPT_TOKENS] = static_cast<int64_t>(tokens.size());

    // --- decode -------------------------------------------------------------

    std::string out;
    int32_t generated = 0;
    bool stopped_on_eog = false;
    int64_t grammar_ms = 0;

    const int64_t t_decode_start = now_ms();
    llama_token token = 0;

    while (generated < max_tokens) {
        const int64_t t_sample = now_ms();
        // Note: llama_sampler_sample() accepts the token internally. Calling
        // llama_sampler_accept() as well would advance the grammar automaton
        // twice per token and derail every constrained generation.
        token = llama_sampler_sample(chain, s->ctx, -1);
        if (constrained) grammar_ms += now_ms() - t_sample;

        if (llama_vocab_is_eog(s->vocab, token)) {
            stopped_on_eog = true;
            break;
        }

        out += token_to_piece(s->vocab, token);
        generated++;

        llama_batch batch = llama_batch_get_one(&token, 1);
        const int32_t rc = llama_decode(s->ctx, batch);
        if (rc != 0) {
            LOGW("llama_decode returned %d during generation; stopping", rc);
            break;
        }
    }

    stats[STAT_DECODE_MS] = now_ms() - t_decode_start;
    stats[STAT_COMPLETION_TOKENS] = generated;
    stats[STAT_TRUNCATED] = (generated >= max_tokens && !stopped_on_eog) ? 1 : 0;
    stats[STAT_STOPPED_ON_EOG] = stopped_on_eog ? 1 : 0;
    stats[STAT_GRAMMAR_MS] = grammar_ms;
    stats[STAT_TOTAL_MS] = now_ms() - t_start;

    llama_sampler_free(chain);

    if (j_stats != nullptr && env->GetArrayLength(j_stats) >= STAT_COUNT) {
        env->SetLongArrayRegion(j_stats, 0, STAT_COUNT, stats);
    }

    return env->NewStringUTF(out.c_str());
}

/**
 * Check that a GBNF string parses, without running a generation.
 *
 * Lets the grammar be validated at startup and in instrumented tests rather than
 * discovered broken on the first planner call. §10.6 warns that GBNF is fussy;
 * this is the cheap way to keep that from becoming a runtime surprise.
 */
JNIEXPORT jboolean JNICALL
Java_dev_axon_android_inference_NativeLlama_nativeValidateGrammar(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring j_grammar) {

    Session *s = as_session(handle);
    if (s == nullptr) return JNI_FALSE;

    const std::string grammar = jstring_to_std(env, j_grammar);
    llama_sampler *smpl = llama_sampler_init_grammar(s->vocab, grammar.c_str(), "root");
    if (smpl == nullptr) return JNI_FALSE;
    llama_sampler_free(smpl);
    return JNI_TRUE;
}

} // extern "C"
