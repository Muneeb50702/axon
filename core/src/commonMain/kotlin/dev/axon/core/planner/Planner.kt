package dev.axon.core.planner

import dev.axon.core.model.ActionMenu
import dev.axon.core.model.CompactState
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.Goal
import dev.axon.core.model.PostCondition

/**
 * Chooses the next action (spec §9.2, §7.4). The **only** LLM caller in the loop.
 *
 * §7.4 is emphatic: *the planner never executes*. It selects. Execution, retries
 * and state transitions belong to the executor. That split is what lets the
 * deterministic majority of the system be tested without a model, and what stops
 * a hallucinated action from reaching the device — because the thing that
 * decides and the thing that acts are different objects with different rules.
 *
 * ### Deviation from §9.2, and why
 *
 * §9.2 gives the signature as returning `DeviceAction`; §9 states "types are
 * illustrative but the shape is required". [nextAction] keeps the shape and
 * returns [PlanDecision], a superset carrying the action plus what the call
 * cost. The accounting is not optional: "LLM calls per task" is the headline
 * metric of contribution C1 (§14.2) and the entire point of the replay path is
 * that it drives that number to zero. A planner that could not report its own
 * cost would make the project's central claim unmeasurable.
 */
public interface Planner {

    /**
     * Select one action for the current screen.
     *
     * @param state pruned screen view (§10.2).
     * @param goal what the user asked for, plus budgets.
     * @param menu what is available here, in natural language (§7.4 — the
     *   grammar is *not* injected into the prompt; this is what goes in instead).
     * @param failure set when re-planning after a verifier mismatch (§7.6). The
     *   planner injects it into context so the retry knows what was expected and
     *   what the screen actually showed. Without it, self-healing degrades into
     *   retrying the same failing action until the budget runs out.
     */
    public suspend fun nextAction(
        state: CompactState,
        goal: Goal,
        menu: ActionMenu,
        failure: FailureContext? = null,
    ): PlanDecision
}

/** A chosen action plus the cost of choosing it. */
public data class PlanDecision(
    val action: DeviceAction,

    /**
     * Model invocations this decision consumed.
     *
     * Usually 1. Two for the §7.4 two-call pattern on hard steps: a short
     * unconstrained "reason about the next step" call, then a constrained
     * extract-to-action call. §7.4 notes heavy constraint can slightly dent
     * reasoning, and this is the stated mitigation — spend a second call where
     * it buys accuracy, stay single-call where it does not.
     */
    val llmCalls: Int = 1,

    val latencyMs: Long = 0,

    /** Free-text rationale from the unconstrained pass, when there was one. */
    val reasoning: String? = null,

    /** Retries spent on truncated or unparseable output (§7.4 truncation caveat). */
    val retries: Int = 0,
)

/**
 * Why the previous attempt failed, in a form a small model can act on (§7.6).
 *
 * Structured rather than free text so the prompt phrasing lives in one place and
 * can be changed without touching the verifier, and so the same record serves the
 * audit log (§16) unchanged.
 */
public data class FailureContext(
    val attemptedAction: DeviceAction,
    val expected: PostCondition,

    /** What the tree actually showed — concrete, not "it failed". */
    val observed: String,

    /** Which heal attempt this is, 1-based, against [Goal.healBudget]. */
    val attempt: Int,

    /**
     * Actions already tried and failed on this screen.
     *
     * Carried because the characteristic small-model failure under re-planning is
     * to propose the same action again — it looked best the first time and the
     * prompt has not changed much. Listing the dead ends explicitly is what makes
     * the second attempt genuinely different from the first.
     */
    val exhausted: List<DeviceAction> = emptyList(),
) {
    /** Render for the planner prompt. */
    public fun render(): String = buildString {
        append("PREVIOUS ATTEMPT FAILED (attempt ").append(attempt).append(")\n")
        append("  you tried: ").append(attemptedAction.describeForPrompt()).append('\n')
        append("  you expected: ").append(expected.describe()).append('\n')
        append("  what actually happened: ").append(observed).append('\n')
        if (exhausted.isNotEmpty()) {
            append("  already tried and failed on this screen:\n")
            for (a in exhausted) append("    - ").append(a.describeForPrompt()).append('\n')
            append("  choose a DIFFERENT action.\n")
        }
    }
}

/** One-line description of an action for prompts and the audit log. */
public fun DeviceAction.describeForPrompt(): String = when (this) {
    is DeviceAction.Tap -> "tap ${target.by.name.lowercase()}=\"${target.value}\""
    is DeviceAction.LongPress -> "long-press ${target.by.name.lowercase()}=\"${target.value}\""
    is DeviceAction.InputText -> "type \"$text\" into ${target.by.name.lowercase()}=\"${target.value}\""
    is DeviceAction.Swipe -> "swipe ${direction.name.lowercase()}"
    is DeviceAction.Scroll -> "scroll ${direction.name.lowercase()}"
    is DeviceAction.LaunchApp -> "launch app $app"
    is DeviceAction.PressKey -> "press ${key.name.lowercase()}"
    is DeviceAction.Wait -> "wait ${timeoutMs}ms"
}
