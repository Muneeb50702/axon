package dev.axon.bench

import dev.axon.core.driver.DeviceDriver
import dev.axon.core.model.ActResult
import dev.axon.core.model.Bounds
import dev.axon.core.model.Capability
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.Goal
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.model.TaskOutcome
import dev.axon.core.model.TraceStep
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree
import dev.axon.core.model.VerifiedTrace
import dev.axon.core.verifier.PostConditionEvaluator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The benchmark harness, end to end, with no phone attached.
 *
 * This is the C4 dividend made concrete: the whole scoring path — run a task,
 * check its oracle against the device, fold into §14.2 metrics, render §14.3 —
 * exercised in CI. A reviewer without the handset can run it, which is unusual
 * in on-device agent work and is the reason `:bench` is a plain JVM module.
 *
 * The tests below are about the harness surviving reality rather than about
 * arithmetic: a task that throws, an app that is not installed, a run that
 * *claims* success while the oracle disagrees. On this hardware every one of
 * those is a case that actually happens.
 */
class BenchRunnerTest {

    private val doneNode = UiNode(
        index = 0, role = "text", text = "done", contentDescription = "done",
        bounds = Bounds(0, 0, 100, 40),
    )

    /** A device showing whatever the test says it shows. */
    private class FakeDevice(var tree: UiTree) : DeviceDriver {
        override suspend fun observe() = tree
        override suspend fun act(action: DeviceAction) = ActResult.Dispatched()
        override suspend fun assert(condition: PostCondition) =
            PostConditionEvaluator.evaluate(condition, tree)
        override fun capabilities() = setOf(Capability.UI_GESTURE, Capability.UI_OBSERVE)
        override val deviceFamily = "bench/fake"
    }

    private fun screen(vararg nodes: UiNode, pkg: String = "com.example") =
        UiTree(pkg, null, nodes.toList(), capturedAtMs = 0)

    private fun task(
        id: String,
        oracle: List<PostCondition> = listOf(PostCondition(PostConditionType.NODE_PRESENT, "done")),
        requiredApps: List<String> = emptyList(),
    ) = BenchTask(
        id = id,
        tier = BenchTier.CORE,
        goal = "do $id",
        initialCondition = InitialCondition(),
        successOracle = oracle,
        optimalSteps = 2,
        requiredApps = requiredApps,
    )

    private fun trace(goal: String, steps: Int = 2, outcome: TaskOutcome = TaskOutcome.SUCCESS) =
        VerifiedTrace(
            traceId = "t-$goal",
            goal = goal,
            steps = (0 until steps).map {
                TraceStep(
                    action = DeviceAction.Tap(
                        Target(TargetBy.CONTENT_DESC, "x"),
                        PostCondition(PostConditionType.NODE_PRESENT, "done"),
                    ),
                    preOk = true, postOk = true, latencyMs = 10,
                )
            },
            outcome = outcome,
            llmCalls = steps,
            totalMs = 1_000,
            device = "bench/fake",
            model = "fake",
        )

    // ---------------------------------------------------------------------

    @Test
    fun `a task whose oracle holds on the device scores as a success`() = runTest {
        val device = FakeDevice(screen(doneNode))
        val runner = BenchRunner(device, execute = { trace(it.utterance) })

        val score = runner.runTask(task("t1"))

        assertTrue(score.oraclePassed)
        assertFalse(score.skipped)
    }

    @Test
    fun `a run that claims success but fails its oracle is a failure`() = runTest {
        // The gap C2 exists to expose, and the one a benchmark must not paper
        // over. The trace says SUCCESS — the loop's own termination test was
        // satisfied — while the device does not show what the task required.
        val device = FakeDevice(screen()) // nothing on screen
        val runner = BenchRunner(device, execute = { trace(it.utterance) })

        val score = runner.runTask(task("t1"))

        assertFalse(score.oraclePassed, "the oracle is the verdict, not trace.outcome")
        assertEquals(TaskOutcome.SUCCESS, score.trace.outcome)
        assertTrue(score.selfAssessmentWrong, "a false positive, and it must be visible as one")
    }

    @Test
    fun `every oracle condition must hold, not merely one`() = runTest {
        // A task with two post-conditions makes two claims about what "done"
        // means. Satisfying one of them is not finishing the task.
        val device = FakeDevice(screen(doneNode))
        val runner = BenchRunner(device, execute = { trace(it.utterance) })

        val score = runner.runTask(
            task(
                "t1",
                oracle = listOf(
                    PostCondition(PostConditionType.NODE_PRESENT, "done"),
                    PostCondition(PostConditionType.NODE_PRESENT, "also required"),
                ),
            ),
        )

        assertFalse(score.oraclePassed)
    }

    @Test
    fun `a task needing an absent app is skipped, not failed`() = runTest {
        val device = FakeDevice(screen(doneNode))
        val runner = BenchRunner(
            device,
            execute = { error("must not run a task whose app is missing") },
            installedApps = setOf("com.example"),
        )

        val score = runner.runTask(task("t1", requiredApps = listOf("com.whatsapp")))

        assertTrue(score.skipped)
        assertFalse(score.oraclePassed)
    }

    @Test
    fun `a task that throws fails without taking the run down`() = runTest {
        // E6: the OEM power manager terminates sustained compute, so a task
        // dying mid-run is the normal case. The cases that did finish must still
        // be reported.
        val device = FakeDevice(screen(doneNode))
        var n = 0
        val runner = BenchRunner(device, execute = {
            if (++n == 2) error("killed") else trace(it.utterance)
        })

        val scores = runner.run(listOf(task("a"), task("boom"), task("c")))

        assertEquals(3, scores.size)
        assertTrue(scores[0].oraclePassed)
        assertFalse(scores[1].oraclePassed)
        assertEquals(TaskOutcome.ERROR, scores[1].trace.outcome)
        assertTrue(scores[2].oraclePassed, "the run continues past a dead task")
    }

    @Test
    fun `the whole path produces a renderable ablation table`() = runTest {
        // The C4 claim, demonstrated rather than asserted: run tasks, score them
        // against a device, fold to §14.2, render §14.3 — with no phone.
        val device = FakeDevice(screen(doneNode))
        val tasks = listOf(task("a"), task("b"), task("c"))

        val full = BenchRunner(device, execute = { trace(it.utterance, steps = 2) })
            .run(tasks)
        val naive = BenchRunner(device, execute = { trace(it.utterance, steps = 6) })
            .run(tasks)

        val table = AblationTable.render(
            mapOf(
                AblationConfig.D_FULL_AXON to full,
                AblationConfig.E_NAIVE_LARGE to naive,
            ),
        )

        assertTrue("ABLATION" in table)
        assertTrue("HEADLINE CLAIM" in table)
        assertTrue("\nD   " in table && "\nE   " in table)
        // Not measured here — the harness must not invent the C3 column.
        assertTrue("n/m" in table)
    }
}
