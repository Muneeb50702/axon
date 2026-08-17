package dev.axon.core.runtime

import dev.axon.core.driver.DeviceDriver
import dev.axon.core.executor.ConfirmationGate
import dev.axon.core.executor.DefaultExecutor
import dev.axon.core.memory.CompilationPolicy
import dev.axon.core.memory.InMemoryTraceStore
import dev.axon.core.memory.TraceRecorder
import dev.axon.core.model.ActResult
import dev.axon.core.model.ActionMenu
import dev.axon.core.model.Bounds
import dev.axon.core.model.Capability
import dev.axon.core.model.CompactState
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.Goal
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.model.TaskOutcome
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree
import dev.axon.core.planner.FailureContext
import dev.axon.core.planner.PlanDecision
import dev.axon.core.planner.Planner
import dev.axon.core.skills.DefaultSkillCompiler
import dev.axon.core.skills.InMemorySkillStore
import dev.axon.core.verifier.PostConditionEvaluator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cold arm: plan a task the system already knows (§14.3, **E24c**).
 *
 * ## What this is protecting
 *
 * Every claim that a mechanism helps is a comparison against the same system
 * with that mechanism off, and the *comparison* is the fragile part. E24b
 * measured a compound goal composed from two learned skills at 0 model calls in
 * 4.4 s. Answering "what did that cost before it was learned" had, until this,
 * only methodologically broken options — delete the skills the phone spent
 * minutes learning, swap the database under a live connection (an earlier attempt
 * did exactly that and had to be voided), or compare against a *different* goal
 * and quietly change two variables at once.
 *
 * So the arm is a property of the request, not of the stored state. The two
 * assertions that make that true are the ones below about the store being
 * **unchanged** afterwards: a measurement that consumes the thing it measures
 * cannot be repeated, and an experiment that cannot be repeated is an anecdote.
 */
class RunConfigTest {

    private fun screen(vararg labels: String) = UiTree(
        "com.whatsapp", null,
        labels.mapIndexed { i, l ->
            UiNode(
                i, "button", text = l, contentDescription = l, clickable = true,
                bounds = Bounds(0, i * 60, 200, i * 60 + 50),
            )
        },
        capturedAtMs = 0,
    )

    private class StepDriver(private val sequence: List<UiTree>) : DeviceDriver {
        private var index = 0
        override suspend fun observe(): UiTree = sequence[minOf(index, sequence.size - 1)]
        override suspend fun act(action: DeviceAction): ActResult {
            index++
            return ActResult.Dispatched()
        }
        override suspend fun assert(condition: PostCondition) =
            PostConditionEvaluator.evaluate(condition, observe())
        override fun capabilities() = setOf(
            Capability.UI_OBSERVE, Capability.UI_GESTURE, Capability.APP_LAUNCH,
        )
        override val deviceFamily = "fake/test"
        fun rewind() { index = 0 }
    }

    private class CountingPlanner(private val action: DeviceAction) : Planner {
        var calls = 0
        override suspend fun nextAction(
            state: CompactState, goal: Goal, menu: ActionMenu, failure: FailureContext?,
        ): PlanDecision {
            calls++
            return PlanDecision(action, llmCalls = 1)
        }
    }

    private val goal = Goal("send hello on whatsapp")

    /** A store that has already learned [goal], plus everything needed to run it. */
    private class Fixture {
        val driver = StepDriver(listOf(UiTree("com.whatsapp", null, listOf(), 0)))
        val skills = InMemorySkillStore()
        val traces = InMemoryTraceStore()
        val planner = CountingPlanner(
            DeviceAction.Tap(
                Target(TargetBy.CONTENT_DESC, "Send"),
                PostCondition(PostConditionType.NODE_PRESENT, "Sent"),
            ),
        )
        var id = 0
    }

    private fun runtimeOver(f: Fixture, driver: StepDriver) = AxonRuntime(
        driver = driver,
        planner = f.planner,
        executor = DefaultExecutor(
            driver, settleMs = 0, nowMs = { 0L },
            confirmation = ConfirmationGate.ALLOW_FOR_TESTING,
        ),
        skills = f.skills,
        traces = f.traces,
        compiler = DefaultSkillCompiler(),
        recorder = TraceRecorder(
            device = "fake/test", model = "test",
            newId = { "t${f.id++}" }, nowMs = { 0L },
        ),
        nowMs = { 0L },
        compilationPolicy = CompilationPolicy(minCleanRuns = 2),
        goalReached = { s -> s.elements.any { it.label == "Sent" } },
    )

    /** Teach the fixture the goal, the ordinary way: two clean planned runs. */
    private suspend fun teach(f: Fixture): StepDriver {
        val driver = StepDriver(listOf(screen("Send"), screen("Sent")))
        runtimeOver(f, driver).execute(goal)
        driver.rewind()
        runtimeOver(f, driver).execute(goal)
        driver.rewind()
        return driver
    }

    // ------------------------------------------------------------ the arm ----

    @Test
    fun `the cold arm plans a goal a skill would have served`() = runTest {
        val f = Fixture()
        val driver = teach(f)
        assertEquals(1, f.skills.all().size, "precondition: the goal is learned")

        // Control: with the shipping config this replays for free.
        val warm = runtimeOver(f, driver).execute(goal)
        assertEquals(ExecutionPath.REPLAY, warm.path)
        assertEquals(0, warm.result.llmCalls)

        driver.rewind()
        val callsBefore = f.planner.calls
        val cold = runtimeOver(f, driver).execute(goal, RunConfig.COLD)

        assertEquals(ExecutionPath.PLAN, cold.path, "the cold arm must not replay")
        assertTrue(cold.result.llmCalls > 0, "the cold arm must consult the planner")
        assertTrue(f.planner.calls > callsBefore)
    }

    @Test
    fun `the cold arm leaves the store exactly as it found it`() = runTest {
        // The load-bearing assertion. If measuring the cold cost of a task
        // consumed the skill, the arm could be run once per learned task and the
        // user would pay for every measurement in re-learning.
        val f = Fixture()
        val driver = teach(f)

        val before = f.skills.all().single()
        driver.rewind()
        runtimeOver(f, driver).execute(goal, RunConfig.COLD)
        val after = f.skills.all().single()

        assertEquals(before.manifest.id, after.manifest.id, "the skill must survive")
        assertEquals(
            before.replayCount, after.replayCount,
            "a cold run did not replay, so it must not be counted as a replay",
        )
    }

    @Test
    fun `the cold arm still records its trace, stamped with the arm`() = runTest {
        // Learning is what the cold path *produces*; the arm controls whether
        // learning is *consumed*. Suppressing the recording would make the
        // measurement destructive in the other direction — the user's phone would
        // do a full minute of planning and be forbidden from keeping what it
        // learned, purely because an experiment was running.
        val f = Fixture()
        val driver = teach(f)
        val tracesBefore = f.traces.all().size

        driver.rewind()
        runtimeOver(f, driver).execute(goal, RunConfig.COLD)

        val added = f.traces.all().size - tracesBefore
        assertEquals(1, added, "a cold run is a real run and belongs in the audit log")
        assertEquals(
            "COLD", f.traces.all().first { it.config != "D" }.config,
            "the arm must be recoverable from the trace, or its latency pollutes D's",
        )
    }

    @Test
    fun `the default config is the shipping behaviour`() = runTest {
        // Guards against the arm leaking into normal use. If RunConfig.DEFAULT
        // ever stopped meaning "everything on", every user would silently be
        // running an ablation.
        val f = Fixture()
        val driver = teach(f)
        driver.rewind()

        val outcome = runtimeOver(f, driver).execute(goal)
        assertEquals(ExecutionPath.REPLAY, outcome.path)
        assertEquals(0, outcome.result.llmCalls)
        assertEquals(TaskOutcome.SUCCESS, outcome.result.outcome)
    }

    // ---------------------------------------------------------- parsing ------

    @Test
    fun `an unrecognised arm falls back to shipping rather than failing`() {
        // Reachable from an adb extra. A typo should run the real configuration,
        // not crash the gateway mid-experiment — and the caller logs what it
        // resolved, so the fallback is visible even though it is silent here.
        assertEquals(RunConfig.DEFAULT, RunConfig.of("nonsense"))
        assertEquals(RunConfig.DEFAULT, RunConfig.of(null))
        assertEquals(RunConfig.DEFAULT, RunConfig.of(""))
        assertEquals(RunConfig.DEFAULT, RunConfig.of("d"))

        assertTrue(!RunConfig.of("cold").skillReplay)
        assertTrue(!RunConfig.of("A").skillReplay)
        assertEquals("A", RunConfig.of("a").id, "the arm id is recorded as asked for")
    }
}
