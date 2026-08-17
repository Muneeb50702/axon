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

    /**
     * Approves irreversible actions before they are dispatched (§16).
     *
     * Defaults to [ConfirmationGate.DENY]. An executor built without one cannot
     * perform irreversible actions at all, which is the correct failure
     * direction: forgetting to wire this up yields an agent that declines to
     * send messages, not one that sends them silently.
     */
    private val confirmation: ConfirmationGate = ConfirmationGate.DENY,
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
        val resolved = when (val gate = PreconditionGate.check(action, state)) {
            is GateResult.Rejected -> return rejected(action, state, gate.failure, started, gate)
            is GateResult.Allowed -> gate.node
        }

        // ---- gate 3: §16 irreversibility ------------------------------------
        //
        // Added after E18b, where the agent opened a dialer with five steps of
        // unconstrained budget left. Nothing had gone wrong; nothing was stopping
        // the next step from being `tap "Call"`.
        //
        // The capability sandbox already covers the skill path, where a manifest
        // declares what a skill does. On the PLAN path there is no manifest —
        // the planner proposes a tap and nothing in the action says whether that
        // element sends money. Irreversibility is therefore inferred from the
        // target, deterministically, before dispatch.
        val reason = ConfirmationPolicy.requiresConfirmation(action, resolved, state)
        if (reason != null && !confirmation.confirm(reason)) {
            return StepOutcome(
                action = action,
                preOk = true,
                // Never evaluated: the action did not happen. Distinct from a
                // failure, and the trace must not claim the world was checked.
                postOk = null,
                actResult = ActResult.Refused(reason.verb, "user did not approve: ${reason.effect}"),
                verifyResult = null,
                latencyMs = nowMs() - started,
                stateHashBefore = state.contentHash,
                stateHashAfter = state.contentHash,
            )
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
        val (after, verdict) = settleAndVerify(action, state)

        return StepOutcome(
            action = action,
            preOk = true,
            postOk = verdict is VerifyResult.Match,
            actResult = actResult,
            verifyResult = verdict,
            latencyMs = nowMs() - started,
            stateHashBefore = state.contentHash,
            stateHashAfter = after.contentHash,
            // E26: what the gate matched, not what the model asked for. The
            // compiler uses it to freeze a more drift-resistant selector than
            // the model happened to choose.
            resolved = resolved,
        )
    }

    /**
     * Wait for the post-condition to hold, up to the action's deadline (E25).
     *
     * ## The bug this replaces
     *
     * Verification used to be one observation after a fixed 500 ms. That is
     * exactly right for an Android transition (200–400 ms) and badly wrong for
     * an app cold start on a Helio G85.
     *
     * Measured: "go to LinkedIn" dispatched `launch_app` correctly — the gate
     * passed, the resolver had found `com.linkedin.android`, the app *was*
     * starting — and the verifier looked 500 ms later, saw LinkedIn's splash
     * with an empty accessibility tree, and recorded
     * *"a screen with no readable elements (unknown)"* as a failure. The run then
     * spent two further steps hunting for a "LinkedIn" element on the launcher
     * and escalated after 203 seconds. **The task had already succeeded at
     * step 0.**
     *
     * WhatsApp starts fast enough to beat 500 ms, which is why every earlier
     * experiment passed and this only appeared when a heavier app was tried.
     * That is the C5 substrate story again: on low-end hardware the timings a
     * developer assumes are the ones that break.
     *
     * ## Polling rather than a longer fixed wait
     *
     * A fixed 5-second settle would fix LinkedIn and make every tap five seconds
     * slower — on a device where a six-step task is already minutes. Polling
     * returns the instant the condition holds, so a fast action pays one interval
     * and a slow one pays only what it needs.
     *
     * A false failure is the expensive direction here: it burns the heal budget
     * and can escalate a working task to the user, which is precisely what
     * happened to LinkedIn.
     */
    private suspend fun settleAndVerify(
        action: DeviceAction,
        before: UiTree,
    ): Pair<UiTree, VerifyResult> {
        val deadline = nowMs() + deadlineFor(action)

        // The first wait is unconditional: verifying before the UI has had any
        // chance to change reliably observes the *old* screen, and a "no change"
        // verdict on a working action is worse than one interval of latency.
        delay(settleMs)

        var after = driver.observe()
        var verdict = verify(action, before, after)

        while (verdict !is VerifyResult.Match && stillRendering(action, after) && nowMs() < deadline) {
            delay(settleMs.coerceAtLeast(MIN_POLL_MS))
            after = driver.observe()
            verdict = verify(action, before, after)
        }
        return after to verdict
    }

    /**
     * Is the screen still coming up, as opposed to up and simply wrong?
     *
     * This predicate is what keeps polling honest, and getting it wrong in the
     * permissive direction is expensive. Retrying on *any* unmet condition means
     * every genuine mismatch — a tap that hit the wrong button, an assertion
     * that will never hold — waits out the full deadline before failing, and
     * re-observes the device each time. The first version of this did exactly
     * that and turned the test suite from seconds into seven minutes.
     *
     * So retry only on positive evidence that the UI has not finished:
     *
     * - **An empty tree.** No real screen has zero readable elements; this is an
     *   app mid-launch, and it is precisely what LinkedIn showed (E25).
     * - **A launch whose app is not yet in front.** The splash may have nodes
     *   while the target package still is not foreground.
     *
     * A screen that is populated and simply does not satisfy the condition is a
     * genuine mismatch. Waiting longer cannot change it, and the verifier's
     * verdict is more useful delivered promptly.
     */
    private fun stillRendering(action: DeviceAction, after: UiTree): Boolean {
        if (after.nodes.isEmpty()) return true
        return action is DeviceAction.LaunchApp && after.foregroundPackage != action.app
    }

    /**
     * How long this action's post-condition is allowed to take.
     *
     * Per-action rather than one constant, because the operations differ by an
     * order of magnitude and a single number would either be too short for a
     * cold app start or too slow for everything else.
     */
    private fun deadlineFor(action: DeviceAction): Long = when (action) {
        // Launching an app on this hardware means a process start, a splash
        // screen and a first layout pass. Seconds, not milliseconds.
        is DeviceAction.LaunchApp -> LAUNCH_TIMEOUT_MS

        // `wait` exists to let a post-condition become true; honouring its own
        // stated timeout is the whole point of the action.
        is DeviceAction.Wait -> action.timeoutMs

        // Everything else is an in-app transition.
        else -> TRANSITION_TIMEOUT_MS
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

        /**
         * Ceiling for an in-app transition to satisfy its post-condition.
         *
         * Generous against the 200–400 ms an Android transition takes, because
         * the cost of waiting is one extra poll and the cost of giving up early
         * is a heal for a step that worked.
         */
        public const val TRANSITION_TIMEOUT_MS: Long = 3_000

        /**
         * Ceiling for an app launch (E25).
         *
         * A cold start on a Helio G85 is a process fork, a splash screen and a
         * first layout pass. LinkedIn exceeded 500 ms comfortably and the run
         * escalated after 203 s having actually succeeded at step 0. Eight
         * seconds covers the heavy apps measured so far; a launch that genuinely
         * fails still costs only this once, against ~60 s to re-plan it.
         */
        public const val LAUNCH_TIMEOUT_MS: Long = 8_000

        /**
         * Floor on the polling interval.
         *
         * `settleMs` is 0 in tests, where a zero-interval poll would spin
         * against a scripted driver until the deadline. Only the *repeat*
         * interval is clamped — the first settle still honours `settleMs`, so a
         * test asking for no delay still gets an immediate first check.
         */
        public const val MIN_POLL_MS: Long = 250
    }
}
