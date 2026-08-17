package dev.axon.bench

import dev.axon.bench.RecoveryStudy.FailureMode
import dev.axon.bench.RecoveryStudy.PlannerKind
import dev.axon.core.model.TaskOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E9's result, pinned — and the regression that made measuring it necessary.
 *
 * `TaskResult.healsSucceeded` is documented as *"the numerator of recovery
 * rate"*. It was declared, initialised to zero, threaded through every return of
 * `DefaultAgentRuntime.execute` and written into the result — and **never
 * incremented**. C2's headline metric was structurally pinned at 0%, so the
 * mechanism could have worked perfectly and the number would have said it never
 * did.
 *
 * Nothing failed while that was true, because nothing asserted a non-zero
 * recovery. This file does.
 */
class RecoveryStudyTest {

    @Test
    fun `a heal that works is counted`() = runTest {
        // THE REGRESSION. If healsSucceeded stops incrementing, this is the test
        // that says so — before the recovery rate is quoted in a paper.
        val r = RecoveryStudy.run()

        for (mode in FailureMode.entries) {
            val c = r.cell(mode, PlannerKind.RECOVERING)
            assertEquals(TaskOutcome.SUCCESS, c.outcome, "$mode should be recoverable")
            assertTrue(c.healAttempts > 0, "$mode must actually have failed first")
            assertTrue(
                c.healsSucceeded > 0,
                "$mode recovered but healsSucceeded is ${c.healsSucceeded} — " +
                    "the numerator of the C2 metric is not being incremented",
            )
        }
    }

    @Test
    fun `the loop detects all three failure modes`() = runTest {
        // Each exercises a different defence: the gate refuses a stale selector
        // before dispatch, the verifier catches a dead tap after it, and a wrong
        // expectation fails an assertion that no action could satisfy. A loop
        // that recovered from only one of these would report a flattering
        // ceiling.
        val r = RecoveryStudy.run()
        assertEquals(
            FailureMode.entries.size,
            FailureMode.entries.count { r.cell(it, PlannerKind.RECOVERING).recovered },
        )
    }

    @Test
    fun `a planner that repeats itself escalates instead of looping`() = runTest {
        // E18 measured a 1B planner re-proposing an identical rejected action
        // three times. The floor arm reproduces that, and what matters is that
        // the run *ends*: budget spent on planning, not on dispatching the same
        // doomed gesture over and over.
        val r = RecoveryStudy.run()

        for (mode in FailureMode.entries) {
            val c = r.cell(mode, PlannerKind.STUBBORN)
            assertEquals(TaskOutcome.ESCALATED, c.outcome, "$mode should escalate, not hang")
            assertEquals(0, c.healsSucceeded)
            assertTrue(
                c.steps <= 1,
                "the repetition guard should stop the repeat reaching the device; " +
                    "$mode dispatched ${c.steps} steps",
            )
        }
    }

    @Test
    fun `the two arms bound the metric from both sides`() = runTest {
        val r = RecoveryStudy.run()
        val ceiling = r.recoveryRate(PlannerKind.RECOVERING)
        val floor = r.recoveryRate(PlannerKind.STUBBORN)

        assertEquals(1.0, ceiling, "the loop should not drop a recovery the planner offers")
        assertEquals(0.0, floor)
        assertTrue(ceiling!! > floor!!, "bounds must be ordered or they are not bounds")
    }
}
