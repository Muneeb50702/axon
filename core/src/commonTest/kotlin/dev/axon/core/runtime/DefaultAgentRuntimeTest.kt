package dev.axon.core.runtime

import dev.axon.core.driver.DeviceDriver
import dev.axon.core.executor.DefaultExecutor
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
import dev.axon.core.verifier.PostConditionEvaluator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The §7.2 control loop, driven by a scripted planner and a scripted device.
 *
 * The property under test is **termination**. Everything else about an agent is
 * negotiable; a loop that drives a phone and cannot be guaranteed to stop is not.
 * A small model that proposes the same rejected action forever is not a
 * hypothetical failure — it is the characteristic one — and the tests below
 * establish that each of the three independent stops actually fires.
 *
 * None of this needs a model or a device, which is the point: the loop's safety
 * properties are checked on every commit rather than observed anecdotally in a
 * demo.
 */
class DefaultAgentRuntimeTest {

    private fun node(i: Int, label: String) = UiNode(
        index = i, role = "button", text = label, contentDescription = label,
        bounds = Bounds(0, i * 100, 200, i * 100 + 80), clickable = true,
    )

    private fun screen(vararg labels: String, pkg: String = "com.whatsapp") =
        UiTree(pkg, "Chat", labels.mapIndexed { i, l -> node(i, l) }, capturedAtMs = 0)

    /** A device whose screen never changes unless the script says so. */
    private class ScriptedDriver(
        private var current: UiTree,
        private val onAct: (DeviceAction) -> UiTree? = { null },
    ) : DeviceDriver {
        var actCount = 0
        override suspend fun observe(): UiTree = current
        override suspend fun act(action: DeviceAction): ActResult {
            actCount++
            onAct(action)?.let { current = it }
            return ActResult.Dispatched()
        }
        override suspend fun assert(condition: PostCondition): Boolean =
            PostConditionEvaluator.evaluate(condition, current)
        override fun capabilities() = setOf(Capability.UI_OBSERVE, Capability.UI_GESTURE)
        override val deviceFamily = "fake/test"
    }

    /** A planner that returns whatever it is told to, and counts its calls. */
    private class ScriptedPlanner(
        private val actions: List<DeviceAction>,
    ) : Planner {
        var calls = 0
        var lastFailure: FailureContext? = null

        override suspend fun nextAction(
            state: CompactState,
            goal: Goal,
            menu: ActionMenu,
            failure: FailureContext?,
        ): PlanDecision {
            lastFailure = failure
            val action = actions[minOf(calls, actions.size - 1)]
            calls++
            return PlanDecision(action, llmCalls = 1)
        }
    }

    private fun tapOn(label: String, expect: PostCondition) =
        DeviceAction.Tap(Target(TargetBy.TEXT, label), expect)

    private fun runtime(
        driver: DeviceDriver,
        planner: Planner,
        goalReached: suspend (CompactState) -> Boolean = { false },
        budget: Int = 15,
    ) = DefaultAgentRuntime(
        driver = driver,
        planner = planner,
        executor = DefaultExecutor(
            driver, budget = budget, settleMs = 0, nowMs = { 0L },
            confirmation = dev.axon.core.executor.ConfirmationGate.ALLOW_FOR_TESTING,
        ),
        nowMs = { 0L },
        goalReached = goalReached,
    )

    // -----------------------------------------------------------------

    @Test
    fun `a task that reaches its goal reports success`() = runTest {
        val driver = ScriptedDriver(screen("Send")) { screen("Sent") }
        val planner = ScriptedPlanner(
            listOf(tapOn("Send", PostCondition(PostConditionType.NODE_PRESENT, "Sent"))),
        )

        val result = runtime(
            driver, planner,
            goalReached = { state -> state.elements.any { it.label == "Sent" } },
        ).execute(Goal("send a message"))

        assertEquals(TaskOutcome.SUCCESS, result.outcome)
        assertTrue(result.llmCalls > 0)
    }

    @Test
    fun `the goal is checked before planning, so an already-satisfied task costs no LLM calls`() = runTest {
        // Matters more than it looks: this is the shape the skill-replay path
        // (C1′) relies on, and it establishes that zero-LLM-call outcomes are
        // representable rather than a special case bolted on later.
        val driver = ScriptedDriver(screen("Sent"))
        val planner = ScriptedPlanner(listOf(tapOn("x", PostCondition(PostConditionType.NODE_PRESENT, "y"))))

        val result = runtime(
            driver, planner,
            goalReached = { state -> state.elements.any { it.label == "Sent" } },
        ).execute(Goal("send a message"))

        assertEquals(TaskOutcome.SUCCESS, result.outcome)
        assertEquals(0, result.llmCalls, "the planner should never have been consulted")
        assertEquals(0, planner.calls)
    }

    @Test
    fun `a planner that repeats one impossible action escalates rather than burning the budget`() = runTest {
        // The characteristic small-model failure. Without a per-step heal cap the
        // run consumes its entire step budget re-attempting one impossible thing,
        // and the user learns only that it "ran out of steps".
        val driver = ScriptedDriver(screen("Attach", "Camera"))
        val planner = ScriptedPlanner(
            listOf(tapOn("Send", PostCondition(PostConditionType.NODE_PRESENT, "Sent"))),
        )

        val result = runtime(driver, planner, budget = 15)
            .execute(Goal("send a message", healBudget = 2))

        assertEquals(TaskOutcome.ESCALATED, result.outcome)
        assertTrue(
            result.steps.size <= 4,
            "escalation should be prompt, not after the whole budget: ${result.steps.size} steps",
        )
        assertEquals(0, driver.actCount, "the device was touched despite every action being rejected")
    }

    @Test
    fun `the failure context tells the planner what was already tried`() = runTest {
        // Without this the retry proposes the same action — it looked best the
        // first time and the prompt has barely changed. Listing the dead ends is
        // what makes the second attempt genuinely different from the first.
        val driver = ScriptedDriver(screen("Attach"))
        val planner = ScriptedPlanner(
            listOf(tapOn("Send", PostCondition(PostConditionType.NODE_PRESENT, "Sent"))),
        )

        runtime(driver, planner).execute(Goal("send a message", healBudget = 2))

        val failure = planner.lastFailure
        assertTrue(failure != null, "the planner was re-invoked without failure context")
        assertTrue(failure.exhausted.isNotEmpty(), "exhausted actions were not reported")
        assertTrue("Send" in failure.render(), "the rendered context should name the failed attempt")
    }

    @Test
    fun `an unreachable goal stops at the step budget`() = runTest {
        // Every action succeeds mechanically but the goal is never satisfied —
        // the "wandering" case. The step budget is the only thing that stops it.
        val driver = ScriptedDriver(screen("A", "B"))
        val planner = ScriptedPlanner(
            listOf(tapOn("A", PostCondition(PostConditionType.NODE_PRESENT, "A"))),
        )

        val result = runtime(driver, planner, budget = 5)
            .execute(Goal("something impossible", stepBudget = 5))

        assertEquals(TaskOutcome.BUDGET_EXHAUSTED, result.outcome)
        assertEquals(5, result.steps.size)
    }

    @Test
    fun `llm calls are accumulated across steps`() = runTest {
        // The C1′ headline metric. If this did not accumulate correctly, "replay
        // costs zero LLM calls" would be unmeasurable.
        val driver = ScriptedDriver(screen("A"))
        val planner = ScriptedPlanner(
            listOf(tapOn("A", PostCondition(PostConditionType.NODE_PRESENT, "A"))),
        )

        val result = runtime(driver, planner, budget = 3).execute(Goal("x", stepBudget = 3))

        assertEquals(3, result.llmCalls)
    }

    @Test
    fun `cancel stops the loop at the next step boundary`() = runTest {
        // §16 requires the user to be able to stop the agent mid-task. Stopping
        // at a *step boundary* rather than mid-gesture is deliberate: aborting a
        // half-dispatched swipe would leave the UI somewhere neither the user nor
        // the agent expects, which is worse than finishing the current action.
        //
        // Cancellation is therefore tested from inside a running loop. An earlier
        // version of this test called cancel() *before* execute() and expected the
        // task never to start, which was testing the wrong thing — execute()
        // deliberately clears the flag so that a cancelled runtime can be reused
        // for the next task rather than being poisoned forever.
        val driver = ScriptedDriver(screen("A"))
        lateinit var rt: DefaultAgentRuntime

        val planner = object : Planner {
            var calls = 0
            override suspend fun nextAction(
                state: CompactState,
                goal: Goal,
                menu: ActionMenu,
                failure: FailureContext?,
            ): PlanDecision {
                calls++
                // The user presses stop while the first step is being planned.
                if (calls == 1) rt.cancel()
                return PlanDecision(
                    tapOn("A", PostCondition(PostConditionType.NODE_PRESENT, "A")),
                    llmCalls = 1,
                )
            }
        }

        rt = runtime(driver, planner, budget = 15) as DefaultAgentRuntime
        val result = rt.execute(Goal("x", stepBudget = 10))

        // The in-flight step completes; the loop then stops rather than
        // continuing to the second step.
        assertEquals(1, result.steps.size, "the loop continued past cancellation")
        assertEquals(1, planner.calls, "the planner was consulted after cancellation")
    }

    @Test
    fun `a cancelled runtime can be reused for the next task`() = runTest {
        // The mirror of the above. If cancel() poisoned the runtime permanently,
        // stopping one task would silently disable the agent, and the user would
        // have no way to tell that from it simply not working.
        val driver = ScriptedDriver(screen("Sent"))
        val planner = ScriptedPlanner(
            listOf(tapOn("Sent", PostCondition(PostConditionType.NODE_PRESENT, "Sent"))),
        )
        val rt = runtime(
            driver, planner,
            goalReached = { state -> state.elements.any { it.label == "Sent" } },
        )

        rt.cancel()
        val result = rt.execute(Goal("send"))

        assertEquals(TaskOutcome.SUCCESS, result.outcome)
    }
}
