package dev.axon.core.skills

import dev.axon.core.driver.DeviceDriver
import dev.axon.core.executor.ConfirmationGate
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
import dev.axon.core.model.TraceStep
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree
import dev.axon.core.model.VerifiedTrace
import dev.axon.core.planner.FailureContext
import dev.axon.core.planner.PlanDecision
import dev.axon.core.planner.Planner
import dev.axon.core.verifier.PostConditionEvaluator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Trace-to-skill compilation and replay — **C1′**.
 *
 * The headline assertion is `replay costs zero LLM calls`. On the target device a
 * planning step costs ~60 s and ~83 J (E2, E15), so that number is the whole
 * argument: it is the difference between a six-step task taking six minutes and
 * taking milliseconds, and between ~138 tasks per battery charge and effectively
 * unbounded.
 *
 * All of it runs on the JVM with a scripted driver. The C1′ claim is therefore
 * checkable by a reviewer who does not own the handset — unusual in this area,
 * and a direct dividend of the C4 seam.
 */
class SkillCompilerTest {

    private val compiler = DefaultSkillCompiler()

    private fun tap(label: String, expect: String) = DeviceAction.Tap(
        Target(TargetBy.CONTENT_DESC, label),
        PostCondition(PostConditionType.NODE_PRESENT, expect),
    )

    private fun step(action: DeviceAction, ok: Boolean = true, healed: Boolean = false) =
        TraceStep(action = action, preOk = ok, postOk = ok, latencyMs = 50, healed = healed)

    private fun trace(
        goal: String = "send on my way to ammi on whatsapp",
        params: Map<String, String> = mapOf("contact" to "Ammi", "message" to "on my way"),
        steps: List<TraceStep> = listOf(
            step(DeviceAction.LaunchApp("com.whatsapp", PostCondition(PostConditionType.APP_FOREGROUND, "com.whatsapp"))),
            step(tap("Ammi", "Message")),
            step(
                DeviceAction.InputText(
                    Target(TargetBy.CONTENT_DESC, "Message"),
                    "on my way",
                    PostCondition(PostConditionType.NODE_PRESENT, "on my way"),
                ),
            ),
            step(tap("Send", "Sent")),
        ),
        outcome: TaskOutcome = TaskOutcome.SUCCESS,
    ) = VerifiedTrace(
        traceId = "t1",
        goal = goal,
        params = params,
        steps = steps,
        outcome = outcome,
        llmCalls = 6,
        totalMs = 5_400,
        device = "tecno-ck6n/android-34",
        model = "gemma-3-1b-it-q4_k_m",
    )

    // -----------------------------------------------------------------
    // Compilation
    // -----------------------------------------------------------------

    @Test
    fun `a clean trace compiles`() = runTest {
        val result = assertIs<CompileResult.Compiled>(compiler.compile(trace()))
        assertEquals(4, result.skill.steps.size)
        assertEquals(listOf("t1"), result.skill.sourceTraces)
    }

    @Test
    fun `parameters become slots and the goal becomes a pattern`() = runTest {
        // The step §7.7 calls the hard part: deciding what was variable. A skill
        // whose goal pattern still contained "Ammi" could only ever match that
        // one request.
        val skill = assertIs<CompileResult.Compiled>(compiler.compile(trace())).skill

        assertTrue("{contact}" in skill.manifest.goalPattern, skill.manifest.goalPattern)
        assertTrue("{message}" in skill.manifest.goalPattern, skill.manifest.goalPattern)
        assertFalse("ammi" in skill.manifest.goalPattern.lowercase())

        assertEquals(
            setOf("contact", "message"),
            skill.manifest.parameters.map { it.name }.toSet(),
        )
        assertEquals("contact_name", skill.manifest.parameters.first { it.name == "contact" }.type)
    }

    @Test
    fun `parameterised steps carry bindings, structural steps do not`() = runTest {
        // The distinction that separates a skill from a macro. Tapping "Ammi" is
        // a parameter occurrence; tapping "Send" is structure. Confusing them
        // either freezes the skill to one contact or types the contact's name
        // into the message box.
        val skill = assertIs<CompileResult.Compiled>(compiler.compile(trace())).skill

        val ammiStep = skill.steps.first { it.selector?.value == "Ammi" }
        assertEquals("contact", ammiStep.bindings["selector"])

        val sendStep = skill.steps.first { it.selector?.value == "Send" }
        assertTrue(sendStep.bindings.isEmpty(), "Send is structure, not a parameter")

        val typeStep = skill.steps.first { it.action == "input_text" }
        assertEquals("message", typeStep.bindings["text"])
    }

    @Test
    fun `structurally fixed steps disallow LLM fallback`() = runTest {
        // launch_app either works or something is wrong the model cannot fix by
        // guessing. Calling it would spend ~60 s re-deriving an action that was
        // never in doubt.
        val skill = assertIs<CompileResult.Compiled>(compiler.compile(trace())).skill

        assertFalse(skill.steps.first { it.action == "launch_app" }.llmFallback)
        assertTrue(skill.steps.first { it.selector?.value == "Send" }.llmFallback)
    }

    @Test
    fun `a healed trace is refused`() = runTest {
        // A run that limped to the goal encodes the mistakes alongside the
        // solution. Freezing it would replay them forever. Still valuable as
        // evaluation data — just not as a script.
        val dirty = trace(
            steps = listOf(step(tap("Ammi", "Message")), step(tap("Send", "Sent"), healed = true)),
        )
        val result = assertIs<CompileResult.Rejected>(compiler.compile(dirty))
        assertTrue("healed" in result.reason, result.reason)
    }

    @Test
    fun `a failed trace is refused`() = runTest {
        val failed = trace(outcome = TaskOutcome.ESCALATED)
        assertIs<CompileResult.Rejected>(compiler.compile(failed))
    }

    @Test
    fun `compilation is deterministic`() = runTest {
        // A compiled skill must be diffable and reproducible, or it is an opaque
        // artefact of one lucky run rather than a research result.
        val a = assertIs<CompileResult.Compiled>(compiler.compile(trace())).skill
        val b = assertIs<CompileResult.Compiled>(compiler.compile(trace())).skill
        assertEquals(a, b)
    }

    @Test
    fun `refining with a second trace reveals a variable step`() = runTest {
        // §7.7's intended mechanism: two runs of the same goal differing only in
        // parameter values reveal exactly which positions were variable. This is
        // evidence, where single-trace inference is a heuristic.
        val first = assertIs<CompileResult.Compiled>(compiler.compile(trace())).skill

        val second = trace(
            goal = "send running late to baba on whatsapp",
            params = mapOf("contact" to "Baba", "message" to "running late"),
            steps = listOf(
                step(DeviceAction.LaunchApp("com.whatsapp", PostCondition(PostConditionType.APP_FOREGROUND, "com.whatsapp"))),
                step(tap("Baba", "Message")),
                step(
                    DeviceAction.InputText(
                        Target(TargetBy.CONTENT_DESC, "Message"),
                        "running late",
                        PostCondition(PostConditionType.NODE_PRESENT, "running late"),
                    ),
                ),
                step(tap("Send", "Sent")),
            ),
        )

        val refined = assertIs<CompileResult.Compiled>(compiler.refine(first, second)).skill
        assertEquals(2, refined.sourceTraces.size)
    }

    @Test
    fun `refining refuses a trace that took a different route`() = runTest {
        // Different step count means a different path to the goal, not a
        // parameter variation. Merging would produce a skill matching neither.
        val first = assertIs<CompileResult.Compiled>(compiler.compile(trace())).skill
        val shorter = trace(steps = listOf(step(tap("Ammi", "Message")), step(tap("Send", "Sent"))))

        val result = assertIs<CompileResult.Rejected>(compiler.refine(first, shorter))
        assertTrue("route" in result.reason, result.reason)
    }

    // -----------------------------------------------------------------
    // Replay — the C1′ headline
    // -----------------------------------------------------------------

    private class ScriptedDriver(private val screens: MutableList<UiTree>) : DeviceDriver {
        var acted = 0

        /** What was dispatched, so a test can assert on *which* action ran. */
        val dispatched = mutableListOf<DeviceAction>()

        override suspend fun observe(): UiTree =
            if (screens.size > 1) screens.removeAt(0) else screens.first()
        override suspend fun act(action: DeviceAction): ActResult {
            acted++
            dispatched += action
            return ActResult.Dispatched()
        }
        override suspend fun assert(condition: PostCondition) =
            PostConditionEvaluator.evaluate(condition, observe())
        override fun capabilities() = setOf(Capability.UI_GESTURE)
        override val deviceFamily = "fake/test"
    }

    private fun screen(vararg labels: String, pkg: String = "com.whatsapp") = UiTree(
        pkg, null,
        labels.mapIndexed { i, l ->
            UiNode(i, "button", text = l, contentDescription = l, clickable = true,
                editable = l == "Message",
                bounds = Bounds(0, i * 60, 200, i * 60 + 50))
        },
        capturedAtMs = 0,
    )

    /** A planner that must never be called. Fails the test loudly if it is. */
    private class ForbiddenPlanner : Planner {
        var called = 0
        override suspend fun nextAction(
            state: CompactState, goal: Goal, menu: ActionMenu, failure: FailureContext?,
        ): PlanDecision {
            called++
            error("the planner was consulted during a clean replay")
        }
    }

    @Test
    fun `replaying a compiled skill costs zero LLM calls`() = runTest {
        // The C1′ claim, asserted rather than described. On the target device
        // this is the difference between ~60 s per step and a gesture.
        val skill = assertIs<CompileResult.Compiled>(
            compiler.compile(
                trace(
                    params = emptyMap(),
                    steps = listOf(step(tap("Ammi", "Ammi")), step(tap("Send", "Send"))),
                ),
            ),
        ).skill

        val driver = ScriptedDriver(mutableListOf(screen("Ammi", "Send")))
        val planner = ForbiddenPlanner()

        val replayer = SkillReplayer(
            driver = driver,
            executor = DefaultExecutor(
                driver, settleMs = 0, nowMs = { 0L },
                confirmation = ConfirmationGate.ALLOW_FOR_TESTING,
            ),
            nowMs = { 0L },
            planner = planner,
        )

        val result = replayer.replay(skill, Goal("send a message"), emptyMap())

        assertEquals(TaskOutcome.SUCCESS, result.result.outcome)
        assertEquals(0, result.result.llmCalls, "replay must cost zero model calls")
        assertEquals(0, planner.called, "the planner was consulted")
        assertTrue(result.wasFullyDeterministic)
        assertEquals(2, driver.acted)
    }

    @Test
    fun `replay still asserts every step`() = runTest {
        // Replay is not blind playback. A skill that has stopped working must say
        // so at the step that broke, rather than driving through a changed UI —
        // that property is what makes a compiled skill safe to run unattended,
        // and what a macro recorder does not have.
        val skill = assertIs<CompileResult.Compiled>(
            compiler.compile(
                trace(params = emptyMap(), steps = listOf(step(tap("Send", "Sent")))),
            ),
        ).skill

        // The screen never shows "Sent", so the assertion cannot hold.
        val driver = ScriptedDriver(mutableListOf(screen("Send")))
        val replayer = SkillReplayer(
            driver,
            DefaultExecutor(driver, settleMs = 0, nowMs = { 0L },
                confirmation = ConfirmationGate.ALLOW_FOR_TESTING),
            { 0L },
            planner = null,
        )

        val result = replayer.replay(skill, Goal("send"), emptyMap())

        assertEquals(TaskOutcome.ESCALATED, result.result.outcome)
        assertEquals(1, result.failedAtStep)
    }

    @Test
    fun `a missing parameter fails cleanly instead of tapping the placeholder`() = runTest {
        val skill = assertIs<CompileResult.Compiled>(compiler.compile(trace())).skill
        val driver = ScriptedDriver(mutableListOf(screen("Ammi", "Send")))

        val result = SkillReplayer(
            driver,
            DefaultExecutor(driver, settleMs = 0, nowMs = { 0L },
                confirmation = ConfirmationGate.ALLOW_FOR_TESTING),
            { 0L },
        ).replay(skill, Goal("send"), params = emptyMap())

        assertEquals(TaskOutcome.ERROR, result.result.outcome)

        // Asserts on *taps*, not on total dispatches, and the distinction is
        // the point.
        //
        // This test used to read `assertEquals(0, driver.acted)` and passed —
        // for the wrong reason. The trace's first step is `launch_app`, and the
        // compiler was silently dropping its package (E22c), so the replay died
        // at step one and never reached the tap this test is actually about. The
        // assertion was measuring a bug rather than the behaviour it named.
        //
        // With launch_app compiling correctly, step one legitimately runs. What
        // must still never happen is a tap on the literal string "{contact}".
        assertTrue(
            driver.dispatched.none { it is DeviceAction.Tap },
            "no tap may be dispatched when its parameter is missing; got \${driver.dispatched}",
        )
    }

    @Test
    fun `a replayer with no planner is fully deterministic`() = runTest {
        // The configuration the §14.3 ablation needs to measure replay in
        // isolation, and the one a device with no model loaded can still run.
        val skill = assertIs<CompileResult.Compiled>(
            compiler.compile(trace(params = emptyMap(), steps = listOf(step(tap("Ammi", "Ammi"))))),
        ).skill

        val driver = ScriptedDriver(mutableListOf(screen("Ammi")))
        val result = SkillReplayer(
            driver,
            DefaultExecutor(driver, settleMs = 0, nowMs = { 0L },
                confirmation = ConfirmationGate.ALLOW_FOR_TESTING),
            { 0L },
            planner = null,
        ).replay(skill, Goal("x"), emptyMap())

        assertEquals(0, result.result.llmCalls)
        assertTrue(result.wasFullyDeterministic)
    }
}
