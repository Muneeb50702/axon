package dev.axon.core.model

import kotlinx.serialization.Serializable

/**
 * Raw outcome of dispatching an action to the device (spec §9.1 `act()`).
 *
 * Deliberately *not* a success judgement. The driver reports only whether the
 * gesture was delivered — "the tap was dispatched at (540, 1820)". Whether the
 * tap achieved anything is the verifier's call, made from the resulting tree
 * (§7.6). Keeping these apart is what stops the system from believing a tap
 * "worked" because the OS accepted the gesture, which is the failure mode that
 * makes naive agents confidently derail.
 */
@Serializable
sealed interface ActResult {
    /** The gesture was delivered. Says nothing about its effect. */
    @Serializable
    data class Dispatched(val detail: String = "") : ActResult

    /** The driver could not deliver it — no such node, gesture refused, timeout. */
    @Serializable
    data class Failed(val reason: String) : ActResult

    /**
     * Refused by the capability sandbox (§7.10) or a §16 guardrail.
     *
     * Distinct from [Failed] because the response differs: a failure is worth
     * re-planning around, a refusal is not — retrying it is guaranteed waste and
     * the user must be asked instead.
     */
    @Serializable
    data class Refused(val capability: String, val reason: String) : ActResult
}

/** Verifier verdict on one post-condition (§9.2, §7.6). */
@Serializable
sealed interface VerifyResult {
    @Serializable
    data object Match : VerifyResult

    /**
     * The post-condition did not hold.
     *
     * [expected] and [observed] are both carried because self-healing depends on
     * telling the planner *what the screen actually shows*, not merely that it
     * failed. "Expected the Send button to disappear; the screen still shows
     * Send, Attach, Camera" is a re-plannable signal. "Step 3 failed" is not.
     */
    @Serializable
    data class Mismatch(
        val expected: String,
        val observed: String,
        val kind: MismatchKind = MismatchKind.CONDITION_UNMET,
    ) : VerifyResult
}

@Serializable
enum class MismatchKind {
    /** The screen changed, just not as predicted. Usually a wrong-target tap. */
    CONDITION_UNMET,

    /**
     * The tree is byte-identical to before the action ([UiTree.contentHash]
     * unchanged). Strong evidence the action hit nothing at all, which calls for
     * a different target rather than a retry of the same one.
     */
    NO_CHANGE,

    /** The condition did not hold within the allotted wait. */
    TIMEOUT,

    /** An unexpected screen appeared: a permission dialog, an interstitial, an ad. */
    UNEXPECTED_SCREEN,
}

/** Result of the executor running one action end to end (§9.2). */
@Serializable
data class StepOutcome(
    val action: DeviceAction,

    /** Did the precondition gate pass — did the target resolve in the live tree? */
    val preOk: Boolean,

    /** Did the post-condition hold afterwards? `null` if the gate rejected first. */
    val postOk: Boolean?,

    val actResult: ActResult,
    val verifyResult: VerifyResult?,
    val latencyMs: Long,

    /** Tree hash before/after — lets the compiler recognise repeated screens. */
    val stateHashBefore: Int = 0,
    val stateHashAfter: Int = 0,

    /**
     * The node the precondition gate actually matched (E26).
     *
     * `null` for device-directed actions, which address no element.
     *
     * Recorded because the *model* chose the selector and the **gate found the
     * node**, and those are different pieces of information. A model that asks
     * for `text="Send"` may have matched a node that also carries
     * `viewId="com.whatsapp:id/send"` — a far more drift-resistant handle that
     * nothing downstream could previously see. Without this the compiler can
     * only freeze whatever the model happened to say, which is why
     * `preferStableSelector` was a no-op: it had nothing better to choose from.
     */
    val resolved: UiNode? = null,
) {
    /** A step is only committed to the trace when both gates passed. */
    val committed: Boolean get() = preOk && postOk == true
}

/** Terminal result of a whole task (§9.2 `AgentRuntime.execute`). */
@Serializable
data class TaskResult(
    val goal: Goal,
    val outcome: TaskOutcome,
    val steps: List<StepOutcome>,

    /**
     * Model invocations for this task.
     *
     * The headline metric of contribution C1 (§14.2). Cold PLAN runs spend one
     * or two calls per step; a replayed compiled skill spends zero. This field is
     * what makes "0 LLM calls on replay" a measurement rather than a claim.
     */
    val llmCalls: Int,

    val totalMs: Long,

    /** True when a compiled skill served this task instead of the planner (§7.7). */
    val servedBySkill: String? = null,

    /** Heal attempts made (§7.6). Denominator of the C2 recovery-rate metric. */
    val healAttempts: Int = 0,

    /** Heal attempts that recovered the run. Numerator of recovery rate. */
    val healsSucceeded: Int = 0,
)

@Serializable
enum class TaskOutcome {
    SUCCESS,

    /** The step budget ran out (§7.5). */
    BUDGET_EXHAUSTED,

    /** Heal budget ran out on a failed step; the user was asked (§7.6). */
    ESCALATED,

    /** A required capability was not granted (§7.10). */
    REFUSED,

    /** The driver or engine broke in a way the loop could not model. */
    ERROR,
}
