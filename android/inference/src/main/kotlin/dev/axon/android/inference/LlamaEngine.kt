package dev.axon.android.inference

import dev.axon.core.inference.GenerationResult
import dev.axon.core.inference.Gbnf
import dev.axon.core.inference.InferenceEngine
import dev.axon.core.model.ThermalState

/**
 * llama.cpp-backed inference engine (spec §7.3, §9.3). Implemented in Phase 1.
 *
 * The JNI surface this will bind to, decided now so Phase 1 is transcription
 * rather than research:
 *
 * ```
 * llama_model_load_from_file   with params.use_mmap = true          (§7.3)
 * llama_sampler_chain_init     temp → top_k → top_p → dist
 * llama_sampler_init_grammar   inserted at the head when constrained (§7.4, C3)
 * llama_decode                 batched prefill, then token-by-token
 * ```
 *
 * The grammar sampler goes at the *head* of the chain, which matters: it masks
 * disallowed tokens before temperature and top-p reshape the distribution, so
 * the constraint holds regardless of sampling settings. Placed after them, a
 * high temperature could surface a token the grammar had already excluded.
 *
 * `use_mmap` is not a tuning knob (§7.3): mapped weights let the OS page them out
 * under memory pressure, whereas heap-allocated weights make the app the kernel's
 * first choice to kill. On the 6 GB phones this project exists to serve, that is
 * the difference between a slow task and a dead process.
 */
public class LlamaEngine : InferenceEngine {

    override suspend fun generate(
        prompt: String,
        grammar: Gbnf?,
        maxTokens: Int,
    ): GenerationResult =
        TODO("Phase 1: JNI to llama.cpp; attach llama_sampler_init_grammar when grammar != null")

    override fun thermalState(): ThermalState =
        TODO("Phase 1: map PowerManager.getCurrentThermalStatus() onto ThermalState")

    override val modelId: String
        get() = TODO("Phase 1: read from the loaded GGUF metadata")
}
