package dev.axon.bench

import dev.axon.core.model.DeviceAction
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.model.TaskOutcome
import dev.axon.core.model.TraceStep
import dev.axon.core.model.VerifiedTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §14.2 metrics — contribution **C5**.
 *
 * These tests are mostly about *what must not be counted*. Every rate here has a
 * denominator, and each one has a way of being quietly wrong that would flatter
 * the system:
 *
 * - counting skipped tasks as failures makes TSR depend on which apps are
 *   installed rather than on the agent;
 * - averaging step efficiency over failures rewards giving up early;
 * - reporting 0% recovery when nothing needed recovering makes a flawless run
 *   look broken;
 * - taking the loop's own SUCCESS as the benchmark verdict measures the agent's
 *   self-assessment, which is the thing C2 exists to replace.
 *
 * A results table is only worth publishing if its denominators are defensible,
 * so the denominators are what is tested.
 */
class BenchMetricsTest {

    private val expect = PostCondition(PostConditionType.NODE_PRESENT, "Sent")

    private fun task(id: String, optimal: Int = 3) = BenchTask(
        id = id,
        tier = BenchTier.CORE,
        goal = "send a message",
        initialCondition = InitialCondition(),
        successOracle = listOf(expect),
        optimalSteps = optimal,
    )

    private fun trace(
        steps: Int = 3,
        outcome: TaskOutcome = TaskOutcome.SUCCESS,
        llmCalls: Int = 3,
        totalMs: Long = 60_000,
        failedAt: Set<Int> = emptySet(),
        healedAt: Set<Int> = emptySet(),
    ) = VerifiedTrace(
        traceId = "t",
        goal = "send a message",
        steps = (0 until steps).map { i ->
            TraceStep(
                action = DeviceAction.Tap(Target(TargetBy.CONTENT_DESC, "Send"), expect),
                preOk = i !in failedAt,
                postOk = i !in failedAt,
                latencyMs = 100,
                healed = i in healedAt,
            )
        },
        outcome = outcome,
        llmCalls = llmCalls,
        totalMs = totalMs,
        device = "test",
        model = "test",
    )

    private fun score(
        id: String = "t1",
        passed: Boolean = true,
        skipped: Boolean = false,
        optimal: Int = 3,
        emitted: Int = 3,
        valid: Int = 3,
        t: VerifiedTrace = trace(),
    ) = TaskScore(task(id, optimal), t, passed, skipped, emitted, valid)

    // ---------------------------------------------------------------------

    @Test
    fun `skipped tasks are excluded from every rate, not failed`() {
        // A task whose app is not installed says nothing about the agent.
        // Counting it as a failure makes TSR a function of the handset's app
        // list, and the §14.3 comparison between configs stops being about the
        // configs.
        val m = BenchMetrics.of("D", listOf(
            score("a", passed = true),
            score("b", passed = true),
            score("c", skipped = true),
        ))

        assertEquals(2, m.attempted)
        assertEquals(1, m.skipped)
        assertEquals(1.0, m.taskSuccessRate, "2 of 2 attempted — not 2 of 3")
    }

    @Test
    fun `step efficiency ignores failures so giving up early is not rewarded`() {
        // A task that failed after one step would score 1/3 = 0.33 — "efficient"
        // — and a config that fails more would look better than one that
        // finishes. Efficiency is only meaningful for work that completed.
        val m = BenchMetrics.of("D", listOf(
            score("good", passed = true, optimal = 3, t = trace(steps = 3)),
            score("quit", passed = false, optimal = 3, t = trace(steps = 1, outcome = TaskOutcome.ESCALATED)),
        ))

        assertEquals(1.0, m.stepEfficiency!!, 1e-9,
            "only the successful task counts; the abandoned one must not drag it down")
    }

    @Test
    fun `recovery rate is null when nothing needed recovering`() {
        // Not 0%. A config that never had to recover has no recovery rate, and
        // reporting zero would make a flawless run indistinguishable from one
        // whose healing is broken.
        val clean = BenchMetrics.of("D", listOf(score(passed = true)))
        assertNull(clean.recoveryRate)

        val recovered = BenchMetrics.of("C", listOf(
            score("x", passed = true, t = trace(steps = 4, failedAt = setOf(1), healedAt = setOf(1))),
        ))
        assertEquals(1.0, recovered.recoveryRate!!, 1e-9)
    }

    @Test
    fun `TSR follows the oracle, never the runtime's own verdict`() {
        // The distinction C2 exists for. A run can terminate believing it
        // succeeded and fail the task's post-conditions; if TSR read
        // `trace.outcome`, a system that declares victory early would score
        // perfectly, and self-declared success is precisely what this project
        // replaced with a deterministic verifier.
        val selfCongratulating = score(
            "wrong", passed = false, t = trace(outcome = TaskOutcome.SUCCESS),
        )
        val m = BenchMetrics.of("A", listOf(selfCongratulating))

        assertEquals(0.0, m.taskSuccessRate)
        assertTrue(selfCongratulating.selfAssessmentWrong,
            "a false positive — the agent believed it was done and was not")
    }

    @Test
    fun `a correct self-assessment is not flagged`() {
        assertTrue(!score(passed = true, t = trace(outcome = TaskOutcome.SUCCESS)).selfAssessmentWrong)
        assertTrue(!score(passed = false, t = trace(outcome = TaskOutcome.ESCALATED)).selfAssessmentWrong)
    }

    @Test
    fun `an unmeasured valid-action rate is null, never zero`() {
        // The C3 column. It cannot be derived from a trace — a malformed
        // generation never becomes a step, so counting steps would report 100%
        // for every arm including the unconstrained one whose entire purpose is
        // to emit malformed output. When the planner did not report generation
        // counts there is no number, and printing 0% would fabricate a result in
        // the column that carries the contribution.
        val m = BenchMetrics.of("D", listOf(score(emitted = 0, valid = 0)))
        assertNull(m.validActionRate)
    }

    @Test
    fun `valid-action rate is over actions, not over tasks`() {
        // The C3 numerator. Under config B this must be 100% by construction —
        // a lower value means the grammar was not actually installed, which is
        // D9's failure mode: a constraint that is silently absent rather than
        // loudly broken.
        val m = BenchMetrics.of("A", listOf(
            score("a", emitted = 4, valid = 2),
            score("b", emitted = 6, valid = 4),
        ))
        assertEquals(6.0 / 10.0, m.validActionRate!!, 1e-9)
    }

    @Test
    fun `quantiles report a latency that actually occurred`() {
        // Nearest-rank, not interpolated. With samples in the tens, an
        // interpolated p90 reports a number no run produced, and every figure in
        // a results table should be a thing that happened.
        val ms = listOf(10_000L, 20_000L, 30_000L, 40_000L, 100_000L)
        val m = BenchMetrics.of("D", ms.map { score(passed = true, t = trace(totalMs = it)) })

        assertEquals(30_000L, m.medianLatencyMs)
        assertEquals(100_000L, m.p90LatencyMs)
    }

    @Test
    fun `an arm killed before its first case aggregates to zeroes, not a crash`() {
        // E6: the OEM power manager terminates sustained foreground compute. An
        // arm that died before recording anything must appear in the table as an
        // arm with no data, rather than taking down the aggregation of the arms
        // that did finish.
        val m = BenchMetrics.of("E", emptyList())
        assertEquals(0, m.attempted)
        assertEquals(0.0, m.taskSuccessRate)
        assertNull(m.stepEfficiency)
        assertEquals(0L, m.medianLatencyMs)
    }

    @Test
    fun `the rendered row is one line and names its config`() {
        val row = BenchMetrics.of("D", listOf(score(passed = true))).render()
        assertTrue(row.startsWith("D"), row)
        assertTrue("TSR" in row && "valid" in row && "LLM/task" in row, row)
        assertTrue("\n" !in row, "a table row must not wrap")
    }
}
