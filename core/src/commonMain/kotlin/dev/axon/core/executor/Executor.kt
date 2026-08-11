package dev.axon.core.executor

import dev.axon.core.model.DeviceAction
import dev.axon.core.model.StepOutcome
import dev.axon.core.model.UiTree

/**
 * Performs actions (spec §9.2, §7.5). Contains **no LLM calls** — ever (§8).
 *
 * The executor owns the doing: gate the action against live state, dispatch it,
 * handle retries, record the step. It never decides *what* to do; that is the
 * planner's job, and the separation is what makes the majority of the loop
 * testable in CI with no model and no phone.
 *
 * ## The precondition gate is the point
 *
 * §7.5: *"A hallucinated 'tap Send button' with no Send node present is rejected
 * here, before harm."*
 *
 * This is the second of the three defences that let a 2B model drive a phone,
 * and it catches a different class of error than the other two:
 *
 *  - the **grammar** (C3) stops output that is malformed — wrong shape;
 *  - the **gate** stops output that is well-formed but references something that
 *    is not on screen — right shape, wrong world;
 *  - the **verifier** (C2) stops actions that were plausible and still did not
 *    work — right shape, right world, wrong outcome.
 *
 * A hallucinated target is the most common small-model failure, and it is the
 * only one of the three that can be caught *before* the device is touched. That
 * matters beyond reliability: an ungated agent that mis-resolves "Send" can tap
 * whatever occupies those coordinates instead, which is a safety property, not
 * just an accuracy one.
 */
public interface Executor {

    /**
     * Gate, dispatch and record one action.
     *
     * @param action already grammar-valid, not yet known to be *possible*.
     * @param state the tree the planner chose from. Passed explicitly rather
     *   than re-observed so the gate checks the action against the world the
     *   planner actually saw. Re-observing here would silently gate against a
     *   newer screen and turn an ordinary race into an unexplainable rejection.
     */
    public suspend fun run(action: DeviceAction, state: UiTree): StepOutcome
}

/** Why the precondition gate rejected an action (§7.5). */
public sealed interface PreconditionFailure {

    /** The message shown to the planner as an invalid-action signal. */
    public val explanation: String

    /** No node in the live tree satisfies the selector — the classic hallucination. */
    public data class TargetNotFound(val selector: String) : PreconditionFailure {
        override val explanation: String
            get() = "no element matching $selector exists on this screen"
    }

    /** The node exists but is disabled, so tapping it cannot do anything. */
    public data class TargetDisabled(val selector: String) : PreconditionFailure {
        override val explanation: String
            get() = "the element $selector exists but is disabled"
    }

    /** Wrong kind of element for the action — typing into a button, say. */
    public data class WrongAffordance(
        val selector: String,
        val required: String,
    ) : PreconditionFailure {
        override val explanation: String
            get() = "the element $selector is not $required"
    }

    /** More than one node matches, so the action is ambiguous. */
    public data class AmbiguousTarget(
        val selector: String,
        val matches: Int,
    ) : PreconditionFailure {
        override val explanation: String
            get() = "$matches elements match $selector; the target is ambiguous"
    }

    /** Blocked by the capability sandbox (§7.10). */
    public data class CapabilityDenied(val capability: String) : PreconditionFailure {
        override val explanation: String
            get() = "capability '$capability' has not been granted"
    }

    /**
     * The step budget is spent (§7.5).
     *
     * A hard cap, not a heuristic: a small model that oscillates between two
     * screens will do so indefinitely, and a phone has a battery.
     */
    public data class BudgetExhausted(val budget: Int) : PreconditionFailure {
        override val explanation: String
            get() = "step budget of $budget actions is exhausted"
    }
}
