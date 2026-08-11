package dev.axon.android.inference

/**
 * Raw JNI surface over llama.cpp. Everything here maps 1:1 onto a function in
 * `src/main/cpp/axon_llama.cpp`.
 *
 * Kept deliberately thin and free of policy. Anything that decides *how* AXON
 * uses the model — sampling defaults, prompt construction, retry on truncation,
 * thermal gating — belongs in [LlamaEngine], so that the boundary between "what
 * the library can do" and "what AXON chooses to do" stays visible. That boundary
 * is where D1's argument lives: this file is the raw-GBNF access that Llamatik
 * does not expose.
 *
 * `internal`, because nothing outside this module should hold a native handle.
 */
internal object NativeLlama {

    init {
        System.loadLibrary("axon_llama")
    }

    /** Initialise the ggml backend and route llama.cpp logging into logcat. */
    external fun nativeInit()

    external fun nativeFreeBackend()

    /**
     * Compiled-in backends, one per line: `name|description|type|totalMemory`.
     *
     * Reported for decision D8 so the CPU-vs-Vulkan comparison rests on what the
     * loaded `.so` actually offers rather than on which flag was passed.
     */
    external fun nativeBackends(): String

    /**
     * Load a GGUF and create its context. Returns an opaque session handle.
     *
     * Weights are mapped, not copied — the native side sets
     * `LLAMA_LOAD_MODE_MMAP` explicitly rather than accepting the library's
     * AUTO default, which disables mmap on integrated GPUs (§7.3).
     *
     * @throws IllegalStateException if the model or context fails to load.
     */
    external fun nativeLoadModel(
        path: String,
        nGpuLayers: Int,
        nCtx: Int,
        nThreads: Int,
        nThreadsBatch: Int,
    ): Long

    external fun nativeFreeModel(handle: Long)

    external fun nativeModelDesc(handle: Long): String

    /** A GGUF metadata value, e.g. `general.name`. Empty string if absent. */
    external fun nativeModelMeta(handle: Long, key: String): String

    /**
     * Format a turn with the template embedded in the loaded GGUF, falling back
     * to a named built-in template.
     *
     * The fallback matters: the Gemma 4 E2B GGUF ships no `tokenizer.chat_template`
     * key, so the embedded lookup returns nothing. Without a fallback the prompt
     * would reach the model with no turn markers — which does not error, it just
     * silently degrades every measurement taken afterwards.
     *
     * @param fallbackTemplate a llama.cpp built-in name such as `gemma`, or "".
     */
    external fun nativeApplyChatTemplate(
        handle: Long,
        system: String,
        user: String,
        fallbackTemplate: String,
    ): String

    /**
     * Generate, optionally under a GBNF grammar.
     *
     * @param grammar GBNF source, or `null` for unconstrained generation. When
     *   present, the grammar sampler is installed at the **head** of the sampler
     *   chain so the constraint holds regardless of temperature or top-p.
     * @param stats caller-owned out-array of at least [Stats.SIZE] longs, filled
     *   with the counters in [Stats]. Passing an array avoids constructing a
     *   Java object from C++ for every generation.
     * @throws IllegalStateException if the grammar fails to parse — never
     *   silently downgraded to unconstrained, because that would make an
     *   unconstrained run indistinguishable from a constrained one and quietly
     *   invalidate the §14.3 ablation.
     */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        grammar: String?,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        seed: Int,
        stats: LongArray,
    ): String

    /** Does this GBNF parse, with `root` as the start symbol? */
    external fun nativeValidateGrammar(handle: Long, grammar: String): Boolean

    /**
     * Indices into the stats array. Must stay in step with the `Stat` enum in
     * `axon_llama.cpp` — the two are one layout described in two languages.
     */
    object Stats {
        const val PROMPT_TOKENS = 0
        const val COMPLETION_TOKENS = 1
        const val PREFILL_MS = 2
        const val DECODE_MS = 3
        const val TOTAL_MS = 4
        const val TRUNCATED = 5
        const val STOPPED_ON_EOG = 6

        /** Milliseconds spent inside grammar-constrained sampling. */
        const val GRAMMAR_MS = 7

        const val SIZE = 8

        fun newArray(): LongArray = LongArray(SIZE)
    }
}
