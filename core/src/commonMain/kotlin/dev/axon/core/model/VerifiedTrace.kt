package dev.axon.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A recorded task run (spec §10.5) — episodic memory (§7.8) and the compiler's
 * input (§7.7).
 *
 * This record is doing three jobs at once, which is why it carries more than the
 * loop strictly needs:
 *
 *  1. **Compiler input (C1).** A trace with `outcome == SUCCESS` and every step
 *     verified is a candidate for freezing into a [CompiledSkill].
 *  2. **Evaluation record (C5).** Every §14.2 metric — step efficiency, LLM
 *     calls, latency, recovery rate — is computed from these fields, so a
 *     benchmark run is a set of traces and the results table is a fold over them.
 *  3. **Audit log (§16).** The user must be able to see everything the agent
 *     did. That is the same data, so it is stored once rather than duplicated
 *     into a separate logging path that could drift from reality.
 */
@Serializable
data class VerifiedTrace(
    @SerialName("trace_id") val traceId: String,
    val goal: String,
    val params: Map<String, String> = emptyMap(),
    val steps: List<TraceStep>,
    val outcome: TaskOutcome,

    @SerialName("llm_calls") val llmCalls: Int,
    @SerialName("total_ms") val totalMs: Long,

    /** Device family — see [SkillManifest.deviceFamily]. */
    val device: String,

    /** Model identifier, e.g. `gemma-4-e2b-it-q4_k_m`. */
    val model: String,

    /** Which ablation config produced this (§14.3). Lets one store hold all runs. */
    val config: String = "D",

    @SerialName("started_at_ms") val startedAtMs: Long = 0,
) {
    /**
     * Is this trace safe to compile into a skill?
     *
     * Strict on purpose. A trace that limped to the goal through failed steps and
     * heals encodes a *wrong* path; freezing it would compile the mistakes in and
     * replay them forever. Only clean runs are compiled — a trace that needed
     * healing is still valuable as evaluation data, just not as a script.
     */
    val isCompilable: Boolean
        get() = outcome == TaskOutcome.SUCCESS &&
            steps.isNotEmpty() &&
            steps.all { it.preOk && it.postOk } &&
            steps.none { it.healed }

    /** Steps that needed healing — the C2 numerator (§14.2 recovery rate). */
    val healedSteps: Int get() = steps.count { it.healed }
}

/** One action's worth of trace (§10.5 `steps[]`). */
@Serializable
data class TraceStep(
    val action: DeviceAction,

    /** Did the precondition gate pass (§7.5)? */
    @SerialName("pre_ok") val preOk: Boolean,

    /** Did the post-condition hold (§7.6)? */
    @SerialName("post_ok") val postOk: Boolean,

    @SerialName("latency_ms") val latencyMs: Long,

    /** True if this step succeeded only after a self-heal (§7.6). */
    val healed: Boolean = false,

    /** LLM calls this step cost. Zero on the replay path — the C1 measurement. */
    @SerialName("llm_calls") val llmCalls: Int = 0,

    /**
     * The screen this action was chosen from, hashed.
     *
     * Kept so the compiler can tell a genuine loop ("we returned to a screen we
     * have already acted on") from legitimate repetition ("we tapped the same
     * kind of row twice on different screens"). Storing the whole tree per step
     * would be more informative but makes episodic memory grow without bound on
     * a phone; a hash is 4 bytes and answers the question the compiler asks.
     */
    @SerialName("state_hash_before") val stateHashBefore: Int = 0,

    @SerialName("state_hash_after") val stateHashAfter: Int = 0,

    /** Verifier's explanation when this step failed. Null on success. */
    @SerialName("failure_reason") val failureReason: String? = null,

    /**
     * The handles the gate's matched node actually carried (E26).
     *
     * Not the whole [UiNode]: only the three fields a selector can be built
     * from. Episodic memory grows without bound on a phone, and a full node per
     * step — bounds, role, flags, children — would multiply the trace table's
     * size for information the compiler never reads.
     */
    @SerialName("resolved_handles") val resolvedHandles: ResolvedHandles? = null,
)

/**
 * What a matched element could be addressed by (§7.7 selector robustness, E26).
 *
 * The model picks a selector from what it can see in the prompt, which is
 * usually the visible text. The *gate* resolves that to a real node, and that
 * node often carries a much more stable handle — an Android view id, which the
 * app's developer controls and which survives translation and copy changes.
 *
 * Recording all three lets the compiler freeze the most drift-resistant one
 * available rather than whatever the model said. It cannot invent a handle that
 * was never observed; it can stop discarding one that was.
 */
@Serializable
data class ResolvedHandles(
    @SerialName("view_id") val viewId: String? = null,
    @SerialName("content_desc") val contentDescription: String? = null,
    val text: String? = null,
) {
    val isEmpty: Boolean get() = viewId == null && contentDescription == null && text == null
}
