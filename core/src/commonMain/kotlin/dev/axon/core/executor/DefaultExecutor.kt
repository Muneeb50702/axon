package dev.axon.core.executor

import dev.axon.core.driver.DeviceDriver
import dev.axon.core.model.ActResult
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.MismatchKind
import dev.axon.core.model.StepOutcome
import dev.axon.core.model.UiTree
import dev.axon.core.model.VerifyResult
import dev.axon.core.verifier.PostConditionEvaluator
import kotlinx.coroutines.delay

/**
 * The deterministic execution state machine (spec §7.5, §9.2).
 *
 * Contains **no LLM calls** — ever (§8). Given an action, it gates, dispatches,
 * re-observes and reports. What to do next is the planner's problem; whether to
 * heal is the runtime's. This object only knows how to carry out one action
 * honestly.
 *
 * ## The three gates, in order
 *
 * ```
 *   budget    → is there a step left to spend?          (§7.5 action budget)
 *   precondition → does the target exist, live?          (§7.5 gate)
 *   ACT       → dispatch via the driver
 *   settle    → give the UI time to change
 *   verify    → did the stated post-condition hold?      (§7.6, C2)
 * ```
 *
 * Each answers a different question, and each can stop the step. The ordering is
 * chosen so that the cheapest and safest checks run first: a budget check costs
 * nothing, a gate check costs one comparison against a tree already in hand, and
 * only after both pass does anything touch the device.
 *
 * ## Why it re-observes rather than trusting `ActResult`
 *
 * [ActResult.Dispatched] means the OS accepted a gesture. It does not mean the
 * screen changed, and treating it as success is the specific mistake that makes
 * naive agents derail — they proceed from a state they do not occupy, and every
 * later step is planned against fiction.
 *
 * So after acting, the executor captures a *fresh* tree and asks the verifier.
 * The comparison against the pre-action tree is what distinguishes "the screen
 * changed, just not as predicted" from "nothing happened at all", and those two
 * failures deserve different recoveries: the first wants a different expectation,
 * the second wants a different target.
 */
public class DefaultExecutor(
    private val driver: DeviceDriver,
    private val budget: Int = DEFAULT_BUDGET,

    /**
     * How long to let the UI settle before verifying.
     *
     * Not a fudge factor — it is the difference between measuring the outcome and
     * measuring the animation. Android transitions run 200–400 ms, and verifying
     * immediately after a tap reliably observes the *old* screen, producing a
     * mismatch that triggers a heal for a step that actually worked. A false
     * failure is worse than a slow one: it burns the heal budget and can escalate
     * a working task to the user for nothing.
     */
    private val settleMs: Long = DEFAULT_SETTLE_MS,

    private val nowMs: () -> Long,
) : Executor {

    private var spent: Int = 0

    /** Steps used so far against [budget]. */
    public val stepsSpent: Int get() = spent

    /** Reset between tasks. The budget is per-task (§7.5), not per-process. */
    public fun reset() {
        spent = 0
    }

    override suspend fun run(action: DeviceAction, state: UiTree): StepOutcome {
        val started = nowMs()

        // ---- gate 1: budget -------------------------------------------------
        if (spent >= budget) {
            return rejected(
                action, state,
                PreconditionFailure.BudgetExhausted(budget),
                started,
            )
        }
        spent++

        // ---- gate 2: precondition ------------------------------------------
        when (val gate = PreconditionGate.check(action, state)) {
            is GateResult.Rejected -> return rejected(action, state, gate.failure, started, gate)
            is GateResult.Allowed -> Unit
        }

        // ---- act ------------------------------------------------------------
        val actResult = driver.act(action)
        if (actResult !is ActResult.Dispatched) {
            // Failed or refused before anything could change. No point verifying
            // a post-condition for an action that never happened.
            return StepOutcome(
                action = action,
                preOk = true,
                postOk = false,
                actResult = actResult,
                verifyResult = VerifyResult.Mismatch(
                    expected = action.expect.describe(),
                    observed = when (actResult) {
                        is ActResult.Failed -> actResult.reason
                        is ActResult.Refused -> "refused: ${actResult.reason}"
                        else -> "not dispatched"
                    },
                    kind = MismatchKind.CONDITION_UNMET,
                ),
                latencyMs = nowMs() - started,
                stateHashBefore = state.contentHash,
                stateHashAfter = state.contentHash,
            )
        }

        // ---- settle then verify ---------------------------------------------
        delay(settleMs)
        val after = driver.observe()
        val verdict = verify(action, state, after)

        return StepOutcome(
            action = action,
            preOk = true,
            postOk = verdict is VerifyResult.Match,
            actResult = actResult,
            verifyResult = verdict,
            latencyMs = nowMs() - started,
            stateHashBefore = state.contentHash,
            stateHashAfter = after.contentHash,
        )
    }

    /**
     * Decide whether the world changed as the action predicted.
     *
     * The unchanged-tree check runs *first*, and its verdict is more useful than
     * a bare "condition not met": if the screen is byte-identical, the action hit
     * nothing at all, and the right response is a different target rather than a
     * retry of the same one. Reporting that distinction is what lets self-healing
     * (§7.6) do something other than repeat itself.
     */
    private fun verify(action: DeviceAction, before: UiTree, after: UiTree): VerifyResult {
        val held = PostConditionEvaluator.evaluate(action.expect, after)
        if (held) return VerifyResult.Match

        val unchanged = before.contentHash == after.contentHash
        return VerifyResult.Mismatch(
            expected = action.expect.describe(),
            observed = if (unchanged) {
                "the screen did not change at all"
            } else {
                describeScreen(after)
            },
            kind = if (unchanged) MismatchKind.NO_CHANGE else MismatchKind.CONDITION_UNMET,
        )
    }

    /**
     * What the screen actually shows, for the re-plan prompt (§7.6).
     *
     * Concrete rather than diagnostic. "Expected the Send button to disappear;
     * the screen now shows Attach, Camera, Voice message" is something a 1B model
     * can act on. "Post-condition failed" is not.
     */
    private fun describeScreen(tree: UiTree): String {
        val labels = tree.interactable().mapNotNull { it.label }.distinct().take(8)
        return when {
            labels.isEmpty() -> "a screen with no readable elements (${tree.foregroundPackage})"
            else -> "${tree.foregroundPackage} showing: ${labels.joinToString(", ")}"
        }
    }

    private fun rejected(
        action: DeviceAction,
        state: UiTree,
        failure: PreconditionFailure,
        started: Long,
        gate: GateResult.Rejected? = null,
    ) = StepOutcome(
        action = action,
        preOk = false,
        // Null, not false: the post-condition was never evaluated, because the
        // action never ran. Recording it as failed would make the trace claim the
        // world was checked and found wanting, when nothing was checked at all.
        postOk = null,
        actResult = ActResult.Failed(gate?.explain() ?: failure.explanation),
        verifyResult = null,
        latencyMs = started.let { nowMs() - it },
        stateHashBefore = state.contentHash,
        stateHashAfter = state.contentHash,
    )

    public companion object {
        /** §7.5: hard cap on actions per task. Runaway protection, not a heuristic. */
        public const val DEFAULT_BUDGET: Int = 15

        /** Long enough for an Android transition (200–400 ms) to finish. */
        public const val DEFAULT_SETTLE_MS: Long = 500
    }
}
