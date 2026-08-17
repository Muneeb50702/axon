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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The learning loop, end to end: plan → record → compile → replay.
 *
 * This is the test that demonstrates C1′ as a *system property* rather than as
 * two components that each work. The headline assertion is the last one: the
 * same goal, asked a third time, costs **zero model calls** — because the first
 * two runs taught the system how to do it.
 *
 * On the target device those two runs cost ~60 s and ~83 J per step (E2, E15).
 * The third costs a gesture.
 */
class AxonRuntimeTest {

    private fun screen(vararg labels: String, pkg: String = "com.whatsapp") = UiTree(
        pkg, null,
        labels.mapIndexed { i, l ->
            UiNode(i, "button", text = l, contentDescription = l, clickable = true,
                bounds = Bounds(0, i * 60, 200, i * 60 + 50))
        },
        capturedAtMs = 0,
    )

    /** A device that advances through a fixed sequence of screens as it is acted on. */
    private class StepDriver(private val sequence: List<UiTree>) : DeviceDriver {
        private var index = 0
        var acted = 0
        override suspend fun observe(): UiTree = sequence[minOf(index, sequence.size - 1)]
        override suspend fun act(action: DeviceAction): ActResult {
            acted++
            index++
            return ActResult.Dispatched()
        }
        override suspend fun assert(condition: PostCondition) =
            PostConditionEvaluator.evaluate(condition, observe())
        override fun capabilities() = setOf(
            Capability.UI_OBSERVE, Capability.UI_GESTURE, Capability.APP_LAUNCH,
        )
        override val deviceFamily = "fake/test"
        fun rewind() { index = 0; acted = 0 }
    }

    /** Plans one fixed action and counts how often it is consulted. */
    private class CountingPlanner(private val action: DeviceAction) : Planner {
        var calls = 0
        override suspend fun nextAction(
            state: CompactState, goal: Goal, menu: ActionMenu, failure: FailureContext?,
        ): PlanDecision {
            calls++
            return PlanDecision(action, llmCalls = 1)
        }
    }

    @Test
    fun `plan, record, compile, then replay for free`() = runTest {
        // The two screens: before the tap, and after.
        val before = screen("Send")
        val after = screen("Sent")
        val driver = StepDriver(listOf(before, after))

        val action = DeviceAction.Tap(
            Target(TargetBy.CONTENT_DESC, "Send"),
            PostCondition(PostConditionType.NODE_PRESENT, "Sent"),
        )
        val planner = CountingPlanner(action)

        val skills = InMemorySkillStore()
        val traces = InMemoryTraceStore()
        var id = 0

        fun runtime() = AxonRuntime(
            driver = driver,
            planner = planner,
            executor = DefaultExecutor(
                driver, settleMs = 0, nowMs = { 0L },
                confirmation = ConfirmationGate.ALLOW_FOR_TESTING,
            ),
            skills = skills,
            traces = traces,
            compiler = DefaultSkillCompiler(),
            recorder = TraceRecorder(
                device = "fake/test", model = "test",
                newId = { "t${id++}" }, nowMs = { 0L },
            ),
            nowMs = { 0L },
            compilationPolicy = CompilationPolicy(minCleanRuns = 2),
            goalReached = { s -> s.elements.any { it.label == "Sent" } },
        )

        val goal = Goal("send hello on whatsapp")

        // --- run 1: cold. Plans, succeeds, records. Not yet compiled: one clean
        // run is not evidence of repetition, and compiling one-offs fills the
        // store with single-use skills that dilute matching.
        val first = runtime().execute(goal)
        assertEquals(TaskOutcome.SUCCESS, first.result.outcome)
        assertEquals(ExecutionPath.PLAN, first.path)
        assertTrue(first.result.llmCalls > 0)
        assertNull(first.compiled, "one clean run should not compile yet")

        // --- run 2: cold again, and now there is evidence of repetition, so the
        // trace is compiled into a skill.
        driver.rewind()
        val second = runtime().execute(goal)
        assertEquals(TaskOutcome.SUCCESS, second.result.outcome)
        assertEquals(ExecutionPath.PLAN, second.path)
        assertNotNull(second.compiled, "a second clean run should compile a skill")
        assertEquals(1, skills.all().size)

        // --- run 3: the skill matches. This is the claim.
        driver.rewind()
        val callsBefore = planner.calls
        val third = runtime().execute(goal)

        assertEquals(TaskOutcome.SUCCESS, third.result.outcome)
        assertEquals(ExecutionPath.REPLAY, third.path)
        assertEquals(0, third.result.llmCalls, "replay must cost zero model calls")
        assertEquals(callsBefore, planner.calls, "the planner was consulted during replay")
        assertTrue(third.wasFree)
        assertEquals("send_hello_on_whatsapp", third.result.servedBySkill)
    }

    @Test
    fun `a failed run is recorded but never compiled`() = runTest {
        // Freezing a run that did not reach the goal would compile its mistakes
        // in and replay them forever. The trace is still kept — it is evaluation
        // data and audit-log material (§16) — just not a script.
        val driver = StepDriver(listOf(screen("Attach")))
        val planner = CountingPlanner(
            DeviceAction.Tap(
                Target(TargetBy.CONTENT_DESC, "Send"),
                PostCondition(PostConditionType.NODE_PRESENT, "Sent"),
            ),
        )
        val skills = InMemorySkillStore()
        val traces = InMemoryTraceStore()

        val result = AxonRuntime(
            driver = driver,
            planner = planner,
            executor = DefaultExecutor(
                driver, settleMs = 0, nowMs = { 0L },
                confirmation = ConfirmationGate.ALLOW_FOR_TESTING,
            ),
            skills = skills,
            traces = traces,
            compiler = DefaultSkillCompiler(),
            recorder = TraceRecorder("fake", "test", { "t" }, { 0L }),
            nowMs = { 0L },
        ).execute(Goal("send hello on whatsapp", healBudget = 1))

        assertTrue(result.result.outcome != TaskOutcome.SUCCESS)
        assertNull(result.compiled)
        assertEquals(0, skills.all().size)
        assertEquals(1, traces.all().size, "the failed run should still be recorded")
        assertEquals(0, traces.compilable().size)
    }
}
