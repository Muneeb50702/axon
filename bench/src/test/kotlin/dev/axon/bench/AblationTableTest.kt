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
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * The §14.3 results renderer — contribution **C5**.
 *
 * The tests that matter here are not about formatting. They are about whether
 * this table can be trusted, and the central one is that it **reports the
 * headline claim failing**. A renderer that can only print success is a
 * renderer nobody should believe, and every number this project publishes rests
 * on a reader accepting that the other outcome would have been printed.
 *
 * The rest guard the denominators: an arm cut short by the OEM power manager
 * (E6) must be visibly cut short rather than silently averaged, because on this
 * hardware partial runs are the normal case.
 */
class AblationTableTest {

    private fun task(id: String, optimal: Int = 3) = BenchTask(
        id = id,
        tier = BenchTier.CORE,
        goal = "do $id",
        initialCondition = InitialCondition(),
        successOracle = listOf(PostCondition(PostConditionType.NODE_PRESENT, "done")),
        optimalSteps = optimal,
    )

    private fun trace(llmCalls: Int, steps: Int = 3, totalMs: Long = 60_000) = VerifiedTrace(
        traceId = "t",
        goal = "g",
        steps = (0 until steps).map {
            TraceStep(
                action = DeviceAction.Tap(
                    Target(TargetBy.CONTENT_DESC, "x"),
                    PostCondition(PostConditionType.NODE_PRESENT, "done"),
                ),
                preOk = true, postOk = true, latencyMs = 10,
            )
        },
        outcome = TaskOutcome.SUCCESS,
        llmCalls = llmCalls,
        totalMs = totalMs,
        device = "test",
        model = "test",
    )

    private fun scores(n: Int, passing: Int, llmCalls: Int) = (0 until n).map { i ->
        TaskScore(
            task = task("t$i"),
            trace = trace(llmCalls),
            oraclePassed = i < passing,
            actionsEmitted = 3,
            validActions = 3,
        )
    }

    // ---------------------------------------------------------------------

    @Test
    fun `the renderer reports the headline claim FAILING when it fails`() {
        // The test this file exists for. If D loses to E, the table must say so
        // in the same words it would use for success. Anything else makes every
        // reported result unfalsifiable, and therefore worthless as evidence.
        val table = AblationTable.render(
            mapOf(
                // D succeeds on 2 of 10; E on 9 of 10. The claim does not hold.
                AblationConfig.D_FULL_AXON to scores(10, passing = 2, llmCalls = 0),
                AblationConfig.E_NAIVE_LARGE to scores(10, passing = 9, llmCalls = 6),
            ),
        )

        assertContains(table, "DID NOT HOLD")
        assertTrue(
            "task success" in table,
            "the failing comparison must name which claim broke",
        )
    }

    @Test
    fun `the renderer reports the claim holding when it holds`() {
        val table = AblationTable.render(
            mapOf(
                AblationConfig.D_FULL_AXON to scores(10, passing = 9, llmCalls = 0),
                AblationConfig.E_NAIVE_LARGE to scores(10, passing = 6, llmCalls = 6),
            ),
        )

        assertContains(table, "HELD")
        assertTrue("DID NOT HOLD" !in table, "nothing should be reported as failing here")
    }

    @Test
    fun `a claim with a missing arm is not evaluable rather than assumed`() {
        // Reporting "D wins" with no E to compare against would be the easiest
        // possible way to publish a false result.
        val table = AblationTable.render(
            mapOf(AblationConfig.D_FULL_AXON to scores(10, passing = 9, llmCalls = 0)),
        )

        assertContains(table, "not evaluable")
    }

    @Test
    fun `an arm cut short is shown as incomplete, not silently averaged`() {
        // E6: the OEM power manager terminates sustained foreground compute, so
        // an arm completing 3 of 20 is the normal case here. Printing its rate
        // beside a complete arm's, with no coverage stated, would compare a
        // sample of three against a sample of twenty as though they were alike.
        val table = AblationTable.render(
            mapOf(
                AblationConfig.D_FULL_AXON to scores(3, passing = 3, llmCalls = 0),
                AblationConfig.E_NAIVE_LARGE to scores(3, passing = 1, llmCalls = 6),
            ),
        )

        assertContains(table, "incomplete arms")
        assertContains(table, "E6")
    }

    @Test
    fun `an arm that completed nothing is printed, not dropped`() {
        // An arm killed before its first case is a fact about the deployment
        // substrate (C5). Omitting it would turn a finding into a gap in the
        // table that a reader would read as "not run".
        val table = AblationTable.render(
            mapOf(
                AblationConfig.D_FULL_AXON to scores(5, passing = 5, llmCalls = 0),
                AblationConfig.E_NAIVE_LARGE to emptyList(),
            ),
        )

        assertContains(table, "\nE   ")
        assertContains(table, "not evaluable")
    }

    @Test
    fun `skipped tasks are disclosed`() {
        val withSkip = scores(5, passing = 5, llmCalls = 0) +
            TaskScore(task("missing"), trace(0, steps = 0), oraclePassed = false, skipped = true)

        val table = AblationTable.render(mapOf(AblationConfig.D_FULL_AXON to withSkip))
        assertContains(table, "skipped")
        assertContains(table, "not failed")
    }

    @Test
    fun `the caveats travel with the table`() {
        // A table gets pasted into a slide; its prose does not follow it. The
        // things that would make a reader misinterpret these numbers are printed
        // beneath them.
        val table = AblationTable.render(
            mapOf(AblationConfig.D_FULL_AXON to scores(20, passing = 18, llmCalls = 0)),
        )

        assertContains(table, "never the runtime's self-reported outcome")
        assertContains(table, "gated tasks")
    }
}
