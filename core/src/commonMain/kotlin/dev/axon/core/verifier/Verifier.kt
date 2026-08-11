package dev.axon.core.verifier

import dev.axon.core.model.PostCondition
import dev.axon.core.model.UiTree
import dev.axon.core.model.VerifyResult

/**
 * Decides whether the world changed as intended (spec §9.2, §7.6) — **C2**.
 *
 * Contains no LLM calls, and that is the contribution rather than an
 * implementation detail. §7.6: *"small models are unreliable self-judges, so
 * success/failure is decided by deterministic tree assertions."*
 *
 * The standard agent design asks the model "did that work?" after each step. A
 * 2B model answers that question wrongly often enough to poison a multi-step
 * task in a specific, compounding way: a false *success* is unrecoverable,
 * because the agent proceeds from a state it does not actually occupy, and every
 * later step is planned against fiction. Replacing that judgement with a tree
 * query makes verification free, repeatable, and immune to being talked into the
 * wrong answer.
 *
 * The cost of the trade is real and the thesis should state it: a deterministic
 * verifier can only check what the planner thought to assert. AXON pays that by
 * making [dev.axon.core.model.PostCondition] mandatory on every action, so an
 * unassertable action cannot be expressed.
 */
public interface Verifier {

    /**
     * Evaluate [expected] against the post-action tree.
     *
     * @param actual a tree captured *after* the action, not the one the planner
     *   planned from.
     */
    public suspend fun verify(expected: PostCondition, actual: UiTree): VerifyResult

    /**
     * Verify with the pre-action tree available for comparison.
     *
     * The extra argument buys a distinction the single-tree form cannot make: if
     * the tree is unchanged, the action hit nothing, which calls for a different
     * target rather than a retry of the same one. §9.2's two-argument shape is
     * the normative one and remains above; this is the overload the loop actually
     * uses, because "nothing happened" and "something happened, but not that"
     * deserve different recovery strategies (§10 `MismatchKind`).
     */
    public suspend fun verify(
        expected: PostCondition,
        before: UiTree,
        actual: UiTree,
    ): VerifyResult = verify(expected, actual)
}

/**
 * How AXON recovers from a mismatch (§7.6).
 *
 * Rollback is attempted first, and the choice of strategy is per-action rather
 * than global because rolling back is not free — on Android, `back` from a
 * half-filled form can discard a draft, so a blind rollback can destroy work the
 * next attempt needs. The policy therefore prefers the *cheapest* action that
 * restores a known-good screen.
 */
public enum class HealStrategy {
    /** Re-observe and re-plan from where we are. Cheapest; no rollback needed. */
    REPLAN_IN_PLACE,

    /** Press back to return to the previous screen, then re-plan. */
    ROLLBACK_AND_REPLAN,

    /** Relaunch the target app to reach a known-good screen, then re-plan. */
    RESTART_APP,

    /**
     * Stop and ask the user (§7.6 escalation after N heals; §16 keeps the human
     * in the loop for anything the agent cannot resolve).
     */
    ESCALATE,
}
