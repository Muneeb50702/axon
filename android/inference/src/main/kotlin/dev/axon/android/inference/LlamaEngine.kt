package dev.axon.android.inference

import android.content.Context
import android.os.Build
import android.os.PowerManager
import dev.axon.core.inference.Gbnf
import dev.axon.core.inference.GenerationResult
import dev.axon.core.inference.InferenceEngine
import dev.axon.core.model.ThermalState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/**
 * llama.cpp-backed [InferenceEngine] (spec §7.3, §9.3) — decision D1.
 *
 * Owns the model for the process lifetime. Loading a 1.3 GB GGUF costs seconds
 * even mmapped, so the engine is created once and shared; the agent loop calls
 * [generate] many times against one resident model.
 */
class LlamaEngine private constructor(
    private val handle: Long,
    private val powerManager: PowerManager?,
    override val modelId: String,
    val backends: List<BackendInfo>,
    private val config: Config,
) : InferenceEngine, Closeable {

    /**
     * Inference runs on a dedicated single thread, not on `Dispatchers.Default`.
     *
     * Two reasons, both specific to running on a phone. The native context is
     * not thread-safe, so generation must be serialised — the native side takes
     * a mutex, but queueing here means callers wait on a coroutine rather than
     * blocking a shared pool thread. And a generation on this device occupies a
     * core for tens of seconds; letting that land on `Default` would starve the
     * pool that the rest of the app, including the UI's state flows, depends on.
     */
    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "axon-inference") }
            .asCoroutineDispatcher()

    private var closed = false

    override suspend fun generate(
        prompt: String,
        grammar: Gbnf?,
        maxTokens: Int,
    ): GenerationResult = withContext(dispatcher) {
        check(!closed) { "LlamaEngine is closed" }

        // §7.3 thermal budget. Refusing here rather than deep in native code
        // means the caller gets an actionable failure and the phone gets a
        // chance to cool, instead of the OS throttling mid-generation and
        // producing a latency number that measures heat rather than the model.
        val thermal = thermalState()
        check(thermal.allowsAnyWork) {
            "device thermal state is $thermal; refusing to run inference"
        }

        val stats = NativeLlama.Stats.newArray()
        val text = NativeLlama.nativeGenerate(
            handle = handle,
            prompt = prompt,
            grammar = grammar?.source,
            maxTokens = maxTokens,
            temperature = config.temperature,
            topK = config.topK,
            topP = config.topP,
            seed = config.seed,
            stats = stats,
        )

        GenerationResult(
            text = text,
            promptTokens = stats[NativeLlama.Stats.PROMPT_TOKENS].toInt(),
            completionTokens = stats[NativeLlama.Stats.COMPLETION_TOKENS].toInt(),
            latencyMs = stats[NativeLlama.Stats.TOTAL_MS],
            truncated = stats[NativeLlama.Stats.TRUNCATED] == 1L,
            constrained = grammar != null,
        )
    }

    /**
     * Generate and return the full native counters.
     *
     * [generate] satisfies the §9.3 contract; the agent loop needs nothing more.
     * The benchmark does: §14.2 reports latency, and separating prefill from
     * decode is what turns "it is slow" into "prefill is 85% of the cost, so
     * cache the prompt prefix" — which is the D8 mitigation. [GrammarTiming]
     * likewise makes the cost of constrained decoding a measured quantity rather
     * than an assumption, which is what C3 needs to be an honest claim.
     */
    suspend fun generateInstrumented(
        prompt: String,
        grammar: Gbnf?,
        maxTokens: Int = InferenceEngine.DEFAULT_MAX_TOKENS,
    ): InstrumentedResult = withContext(dispatcher) {
        check(!closed) { "LlamaEngine is closed" }

        val stats = NativeLlama.Stats.newArray()
        val text = NativeLlama.nativeGenerate(
            handle, prompt, grammar?.source, maxTokens,
            config.temperature, config.topK, config.topP, config.seed, stats,
        )

        InstrumentedResult(
            result = GenerationResult(
                text = text,
                promptTokens = stats[NativeLlama.Stats.PROMPT_TOKENS].toInt(),
                completionTokens = stats[NativeLlama.Stats.COMPLETION_TOKENS].toInt(),
                latencyMs = stats[NativeLlama.Stats.TOTAL_MS],
                truncated = stats[NativeLlama.Stats.TRUNCATED] == 1L,
                constrained = grammar != null,
            ),
            prefillMs = stats[NativeLlama.Stats.PREFILL_MS],
            decodeMs = stats[NativeLlama.Stats.DECODE_MS],
            grammarMs = stats[NativeLlama.Stats.GRAMMAR_MS],
            stoppedOnEog = stats[NativeLlama.Stats.STOPPED_ON_EOG] == 1L,
            thermalAfter = thermalState(),
        )
    }

    /** Format a turn with the loaded model's chat template. */
    fun applyChatTemplate(system: String, user: String): String =
        NativeLlama.nativeApplyChatTemplate(handle, system, user, config.chatTemplate)

    /** Does this GBNF parse? Used to validate the action grammar at startup. */
    fun validateGrammar(grammar: Gbnf): Boolean =
        NativeLlama.nativeValidateGrammar(handle, grammar.source)

    /**
     * Current thermal headroom (§7.3).
     *
     * `getCurrentThermalStatus` needs API 29; on 26–28 the query does not exist,
     * and AXON reports [ThermalState.NONE] rather than guessing. Stated plainly
     * because it is a real limitation of the low-end target: on the oldest
     * supported devices the adaptive throttling in §7.3 has nothing to read, and
     * the thesis should not claim thermal awareness it does not have there. The
     * test device is Android 14, so the benchmark numbers are unaffected.
     */
    override fun thermalState(): ThermalState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalState.NONE
        val pm = powerManager ?: return ThermalState.NONE
        // PowerManager.THERMAL_STATUS_* is ordinal-compatible with ThermalState.
        return ThermalState.entries.getOrElse(pm.currentThermalStatus) { ThermalState.NONE }
    }

    override fun close() {
        if (closed) return
        closed = true
        NativeLlama.nativeFreeModel(handle)
    }

    /** Sampling and context settings. */
    data class Config(
        val nCtx: Int = DEFAULT_CTX,
        val nThreads: Int = defaultDecodeThreads(),
        val nThreadsBatch: Int = defaultPrefillThreads(),
        val nGpuLayers: Int = 0,
        val temperature: Float = 0.0f,
        val topK: Int = 40,
        val topP: Float = 0.95f,
        val seed: Int = 1234,

        /**
         * llama.cpp built-in chat template to use when the GGUF carries none.
         *
         * Gemma 4 E2B ships without a `tokenizer.chat_template` key, so this is
         * not optional for the primary model. Empty string means "no fallback".
         */
        val chatTemplate: String = "gemma",
    ) {
        companion object {
            /**
             * Context window.
             *
             * 4096 fits a rendered `CompactState`, the goal, the action menu and
             * failure context with room to spare, and keeps the KV cache small —
             * which matters on 8 GB shared with the rest of Android. Raise only
             * with evidence from AXON-Bench that prompts are actually being
             * clipped.
             */
            const val DEFAULT_CTX = 4096

            /**
             * Threads for generation.
             *
             * The target SoC is 2x Cortex-A75 @ 2.0 GHz + 6x A55 @ 1.8 GHz. Using
             * all 8 is usually *slower* than using the big cores alone: the A55s
             * finish their share late, and every matmul waits on the slowest
             * thread. Defaulting to the number of big cores, floored at 2, is the
             * standard heuristic for big.LITTLE and the right starting point —
             * Phase 1 sweeps it and records what actually wins on this device.
             */
            /**
             * Threads for single-token decode.
             *
             * Decode is memory-bound and gated by the slowest thread, so piling
             * on little cores hurts. Measured on the Helio G85 target: 4 threads
             * gave 2.0 tok/s, 8 threads only 1.5.
             */
            fun defaultDecodeThreads(): Int =
                (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

            /**
             * Threads for prompt prefill.
             *
             * Prefill is compute-bound and parallelises across every core, so it
             * wants them all. Same device: 4 threads gave 12.6 tok/s, 8 gave
             * 15.6. Prefill is ~2/3 of a planning step's wall-clock, so this is
             * the setting that matters most.
             */
            fun defaultPrefillThreads(): Int =
                Runtime.getRuntime().availableProcessors().coerceAtMost(8)
        }
    }

    /** Full native counters for one generation. */
    data class InstrumentedResult(
        val result: GenerationResult,
        val prefillMs: Long,
        val decodeMs: Long,
        val grammarMs: Long,
        val stoppedOnEog: Boolean,
        val thermalAfter: ThermalState,
    ) {
        /** Prompt-processing throughput, tokens/sec. */
        val prefillTokensPerSec: Double
            get() = if (prefillMs == 0L) 0.0 else result.promptTokens * 1000.0 / prefillMs

        /** Generation throughput, tokens/sec. */
        val decodeTokensPerSec: Double
            get() = if (decodeMs == 0L) 0.0 else result.completionTokens * 1000.0 / decodeMs

        /**
         * Share of wall-clock spent on prefill.
         *
         * The number that decides whether prompt-prefix caching is worth
         * building. If this is high — which is expected on 2x A75 — then most of
         * every planning step is re-processing a prompt that barely changed.
         */
        val prefillShare: Double
            get() = if (result.latencyMs == 0L) 0.0 else prefillMs.toDouble() / result.latencyMs
    }

    /** One compiled-in ggml backend, from `nativeBackends()`. */
    data class BackendInfo(
        val name: String,
        val description: String,
        val type: Int,
        val totalMemory: Long,
    ) {
        /** ggml device type 1 is GPU. */
        val isGpu: Boolean get() = type == 1
    }

    companion object {

        /**
         * Load a GGUF and build an engine.
         *
         * @param modelFile a GGUF on local storage. Not downloaded by this app —
         *   §16 and decision D7 mean AXON holds no `INTERNET` permission at all,
         *   so weights are side-loaded. That is the cost of making "screen
         *   contents cannot leave the device" an OS-enforced property rather than
         *   a promise, and it is the right trade.
         */
        fun load(
            context: Context?,
            modelFile: File,
            config: Config = Config(),
        ): LlamaEngine {
            require(modelFile.isFile) { "model not found: ${modelFile.absolutePath}" }

            NativeLlama.nativeInit()

            val handle = NativeLlama.nativeLoadModel(
                path = modelFile.absolutePath,
                nGpuLayers = config.nGpuLayers,
                nCtx = config.nCtx,
                nThreads = config.nThreads,
                nThreadsBatch = config.nThreadsBatch,
            )
            check(handle != 0L) { "failed to load model: ${modelFile.absolutePath}" }

            // Prefer the GGUF's own identity over the filename: §10.5 records the
            // model on every trace, and a renamed file must not be able to
            // mislabel a whole benchmark run.
            val metaName = NativeLlama.nativeModelMeta(handle, "general.name")
            val modelId = metaName.ifBlank { modelFile.nameWithoutExtension }

            return LlamaEngine(
                handle = handle,
                powerManager = context?.getSystemService(PowerManager::class.java),
                modelId = modelId,
                backends = parseBackends(NativeLlama.nativeBackends()),
                config = config,
            )
        }

        /** Backends compiled into the loaded `.so`, without loading a model. */
        fun availableBackends(): List<BackendInfo> {
            NativeLlama.nativeInit()
            return parseBackends(NativeLlama.nativeBackends())
        }

        private fun parseBackends(raw: String): List<BackendInfo> =
            raw.lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split('|')
                    if (parts.size < 4) return@mapNotNull null
                    BackendInfo(
                        name = parts[0],
                        description = parts[1],
                        type = parts[2].toIntOrNull() ?: -1,
                        totalMemory = parts[3].toLongOrNull() ?: 0L,
                    )
                }
                .toList()
    }
}
