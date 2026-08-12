package dev.axon.core.runtime

import dev.axon.core.driver.DeviceDriver
import dev.axon.core.executor.DefaultExecutor
import dev.axon.core.model.ActionMenu
import dev.axon.core.model.CompactState
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.Goal
import dev.axon.core.model.MismatchKind
import dev.axon.core.model.StepOutcome
import dev.axon.core.model.TaskOutcome
import dev.axon.core.model.TaskResult
import dev.axon.core.model.VerifyResult
import dev.axon.core.planner.FailureContext
import dev.axon.core.planner.Planner
import dev.axon.core.verifier.HealStrategy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The control loop of spec §7.2 (§9.2).
 *
 * ```
 * loop until the goal holds or the budget is spent:
 *   1. PERCEIVE   observe() → CompactState
 *   2. PLAN       one grammar-constrained action
 *   3. VALIDATE   precondition gate  ─┐
 *   4. ACT        dispatch            ├─ DefaultExecutor
 *   5. VERIFY     re-observe + diff  ─┘
 *        MATCH    → commit, continue
 *        MISMATCH → self-heal (§7.6)
 * ```
 *
 * Deliberately thin. It sequences; it does not decide. Every judgement belongs to
 * a component with one responsibility (§8), and that discipline is what keeps the
 * §14.3 ablation honest: turning the verifier off removes stage 5 and nothing
 * else, so the measured difference is attributable to the verifier rather than to
 * some tangle of behaviour that changed alongside it.
 *
 * ## Termination
 *
 * A loop that drives a phone must be unable to run forever, and there are three
 * independent stops rather than one:
 *
 *  - the **goal condition** holds — the task is done;
 *  - the **step budget** is spent (§7.5) — the executor refuses to act;
 *  - the **heal budget** is spent on a single step (§7.6) — escalate to the user.
 *
 * The third exists because the first two do not cover the characteristic
 * small-model failure: proposing the same rejected action repeatedly. Without a
 * per-step heal cap, such a run consumes its entire step budget re-attempting one
 * impossible thing, and the user learns only that it "ran out of steps".
 */
public class DefaultAgentRuntime(
    private val driver: DeviceDriver,
    private val planner: Planner,
    private val executor: DefaultExecutor,
    private val nowMs: () -> Long,

    /**
     * Decides whether the task is finished.
     *
     * Injected rather than inferred. A benchmark task carries a machine-checkable
     * success oracle (§14.1); an interactive request does not, and inventing one
     * would mean asking the model whether it had succeeded — precisely the
     * self-assessment C2 exists to avoid. Callers with no oracle pass one that
     * always returns false and rely on the budgets, which is honest about the
     * fact that AXON does not know.
     */
    private val goalReached: suspend (CompactState) -> Boolean = { false },
) : AgentRuntime {

    private var cancelled = false
    private val repetition = RepetitionGuard()

    override suspend fun execute(goal: Goal): TaskResult {
        val steps = mutableListOf<StepOutcome>()
        var llmCalls = 0
        var heals = 0
        var healsSucceeded = 0
        val started = nowMs()

        executor.reset()
        repetition.reset()
        cancelled = false

        var failure: FailureContext? = null
        val exhausted = mutableListOf<DeviceAction>()

        while (steps.size < goal.stepBudget && !cancelled) {
            // 1. PERCEIVE
            val tree = driver.observe()
            val state = CompactState.from(tree)

            if (goalReached(state)) {
                return result(goal, steps, llmCalls, started, TaskOutcome.SUCCESS, heals, healsSucceeded)
            }

            // 2. PLAN
            val decision = planner.nextAction(state, goal, ActionMenu.forScreen(state), failure)
            llmCalls += decision.llmCalls

            // 2b. REPETITION GUARD (E18)
            //
            // Measured, not defensive: asked to open an app, the planner proposed
            // the identical rejected action three times despite the failure
            // context naming it and asking for something different. Prompt-based
            // healing does not hold at ~1B, so repetition is made structurally
            // impossible rather than discouraged.
            //
            // `wait` is exempt — waiting twice on an unchanged screen is the
            // whole point of waiting.
            if (decision.action !is DeviceAction.Wait &&
                repetition.isBlocked(tree.contentHash, decision.action)
            ) {
                heals++
                exhausted += decision.action
                failure = FailureContext(
                    attemptedAction = decision.action,
                    expected = decision.action.expect,
                    observed = "you already tried this on this exact screen and it failed",
                    attempt = exhausted.size,
                    exhausted = exhausted.toList(),
                )
                if (exhausted.size > goal.healBudget) {
                    return result(goal, steps, llmCalls, started, TaskOutcome.ESCALATED, heals, healsSucceeded)
                }
                continue
            }

            // 3-5. VALIDATE → ACT → VERIFY
            val outcome = executor.run(decision.action, tree)
            steps += outcome

            if (outcome.committed) {
                // Progress. Clear the failure context so the next step is planned
                // from the world rather than from an old grievance.
                failure = null
                exhausted.clear()
                continue
            }

            // --- self-heal (§7.6) ---
            repetition.recordFailure(tree.contentHash, decision.action)
            heals++
            if (heals > goal.healBudget * goal.stepBudget) {
                return result(goal, steps, llmCalls, started, TaskOutcome.ESCALATED, heals, healsSucceeded)
            }

            exhausted += decision.action
            failure = FailureContext(
                attemptedAction = decision.action,
                expected = decision.action.expect,
                observed = observedFrom(outcome),
                attempt = exhausted.size,
                exhausted = exhausted.toList(),
            )

            // Escalate when one step has resisted its whole heal budget. The
            // alternative — carrying on until the step budget runs out — spends
            // every remaining action on the same impossible thing and tells the
            // user nothing about why.
            if (exhausted.size > goal.healBudget) {
                return result(goal, steps, llmCalls, started, TaskOutcome.ESCALATED, heals, healsSucceeded)
            }

            if (strategyFor(outcome) == HealStrategy.ESCALATE) {
                return result(goal, steps, llmCalls, started, TaskOutcome.ESCALATED, heals, healsSucceeded)
            }
        }

        val finalState = CompactState.from(driver.observe())
        val outcome = when {
            cancelled -> TaskOutcome.ERROR
            goalReached(finalState) -> TaskOutcome.SUCCESS
            else -> TaskOutcome.BUDGET_EXHAUSTED
        }
        return result(goal, steps, llmCalls, started, outcome, heals, healsSucceeded)
    }

    override fun events(goal: Goal): Flow<AgentEvent> = flow {
        emit(AgentEvent.TaskStarted(goal, ExecutionPath.PLAN))
        val result = execute(goal)
        emit(AgentEvent.TaskFinished(result))
    }

    override suspend fun cancel() {
        cancelled = true
    }

    /**
     * Pick a recovery strategy from *why* the step failed.
     *
     * The distinction the executor preserved is spent here. An unchanged screen
     * means the action hit nothing, so re-planning in place with a different
     * target is right. A changed screen that missed the expectation may mean an
     * interstitial appeared, where going back is right. Rolling back
     * unconditionally would be worse than useless — on Android, `back` from a
     * half-filled form discards the draft the next attempt needs.
     */
    private fun strategyFor(outcome: StepOutcome): HealStrategy = when {
        !outcome.preOk -> HealStrategy.REPLAN_IN_PLACE
        (outcome.verifyResult as? VerifyResult.Mismatch)?.kind == MismatchKind.NO_CHANGE ->
            HealStrategy.REPLAN_IN_PLACE
        (outcome.verifyResult as? VerifyResult.Mismatch)?.kind == MismatchKind.UNEXPECTED_SCREEN ->
            HealStrategy.ROLLBACK_AND_REPLAN
        else -> HealStrategy.REPLAN_IN_PLACE
    }

    private fun observedFrom(outcome: StepOutcome): String =
        (outcome.verifyResult as? VerifyResult.Mismatch)?.observed
            ?: (outcome.actResult as? dev.axon.core.model.ActResult.Failed)?.reason
            ?: "the step did not complete"

    private fun result(
        goal: Goal,
        steps: List<StepOutcome>,
        llmCalls: Int,
        started: Long,
        outcome: TaskOutcome,
        heals: Int,
        healsSucceeded: Int,
    ) = TaskResult(
        goal = goal,
        outcome = outcome,
        steps = steps,
        llmCalls = llmCalls,
        totalMs = nowMs() - started,
        healAttempts = heals,
        healsSucceeded = healsSucceeded,
    )
}
