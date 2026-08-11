package dev.axon.core.inference

import dev.axon.core.model.ThermalState

/**
 * On-device model access (spec §9.3, §7.3).
 *
 * The only component permitted to run a model. Everything else in the loop —
 * executor, verifier, skill store, compiler — is deterministic by design (§8),
 * and only the planner (§7.4) holds a reference to this.
 */
public interface InferenceEngine {

    /**
     * Generate text, optionally constrained by a GBNF grammar.
     *
     * When [grammar] is non-null, the sampler masks every token that would take
     * the output outside the grammar (§7.4). Malformed output is then impossible
     * *by construction* rather than by validation-and-retry — that is the C3
     * mechanism, and the reason a 2B model can be a reliable action emitter.
     *
     * Two caveats from §7.4 that implementations must honour:
     *
     *  - The grammar is **not** injected into the prompt. The prompt describes
     *    the available actions and elements in natural language; the grammar
     *    enforces shape at the sampler. Callers must not paste the grammar in.
     *  - A grammar constrains *shape*, not *completion*. If the model runs out of
     *    tokens mid-object the result is a valid prefix of a valid action and
     *    nothing more, so [maxTokens] must be generous and the caller must treat
     *    a truncated parse as a retryable failure.
     */
    public suspend fun generate(
        prompt: String,
        grammar: Gbnf? = null,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
    ): GenerationResult

    /** Current thermal headroom (§7.3). Drives adaptive throttling. */
    public fun thermalState(): ThermalState

    /** Identifier recorded on every trace, e.g. `gemma-4-e2b-it-q4_k_m`. */
    public val modelId: String

    public companion object {
        /**
         * Token ceiling for one constrained action.
         *
         * Generous on purpose, per the truncation caveat above: a `launch_app`
         * with a long package name and a `text_matches` expectation is well
         * under this, and the cost of a too-tight limit is a wasted retry, while
         * the cost of a loose one is nothing — the grammar stops generation at
         * the closing brace regardless.
         */
        public const val DEFAULT_MAX_TOKENS: Int = 256
    }
}

/**
 * One generation, with the accounting the evaluation needs.
 *
 * Latency and token counts are returned rather than logged because §14.2 makes
 * them reported metrics; a planner that could not attribute cost per call would
 * make "LLM calls per task" — the C1 headline — unmeasurable.
 */
public data class GenerationResult(
    val text: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val latencyMs: Long,

    /** True if generation stopped on the token cap rather than naturally. */
    val truncated: Boolean = false,

    /** True if a grammar was in force. Recorded so ablation arms are auditable. */
    val constrained: Boolean = false,
)

/**
 * A GBNF grammar (§10.6).
 *
 * A value class rather than a raw `String` so a grammar cannot be passed where a
 * prompt is expected — which would silently paste the grammar into the context
 * and violate the "grammar is not injected into the prompt" rule above.
 */
@JvmInline
public value class Gbnf(public val source: String) {
    init {
        require(source.isNotBlank()) { "GBNF source must not be blank" }
    }
}
