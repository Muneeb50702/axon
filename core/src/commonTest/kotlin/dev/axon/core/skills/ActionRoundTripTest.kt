package dev.axon.core.skills

import dev.axon.core.model.CompiledSkill
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.DeviceKey
import dev.axon.core.model.Direction
import dev.axon.core.model.Goal
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.SkillManifest
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.model.TaskOutcome
import dev.axon.core.model.TraceStep
import dev.axon.core.model.VerifiedTrace
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Every action type must survive compile → replay (E22c).**
 *
 * This is the test that was missing, and its absence cost more than any single
 * wrong line. The compiler derived a step's whole payload from `action.target`,
 * which is `null` by definition for `launch_app`, `press_key` and `wait`, and
 * which never held the direction for `swipe` or `scroll`. Only `tap` and
 * `long_press` compiled intact. Everything else lost its payload silently.
 *
 * It went unnoticed because every existing test — and E17, the headline
 * measurement — exercised `tap`, one of the two types that happened to work.
 *
 * Measured on device: "open whatsapp" compiled to a step with no package, the
 * replayer could not rebuild the action, and the run fell back to a 66-second
 * cold plan *while `replay_count` incremented* — the store recording a replay
 * that never happened.
 *
 * Two failures were worse than failing. Missing a payload, the replayer filled
 * in a default: `press_key` → BACK, `swipe` → UP, `scroll` → DOWN. A skill that
 * recorded "press HOME" replayed "press BACK" — a wrong action dispatched
 * confidently at a live device, which is the exact outcome the grammar, the gate
 * and the verifier all exist to make impossible. A compiled skill answers to
 * none of the three, so its correctness has to come from here.
 *
 * The parameterised loop matters more than any individual assertion: a new
 * [DeviceAction] variant cannot be added without appearing in it.
 */
class ActionRoundTripTest {

    private val expect = PostCondition(PostConditionType.NODE_PRESENT, "done")
    private val selector = Target(TargetBy.CONTENT_DESC, "Send")

    /** One of every action in §10.1. */
    private val everyAction: List<DeviceAction> = listOf(
        DeviceAction.Tap(selector, expect),
        DeviceAction.LongPress(selector, expect),
        DeviceAction.InputText(selector, "on my way", expect),
        DeviceAction.LaunchApp("com.whatsapp", expect),
        DeviceAction.PressKey(DeviceKey.HOME, expect),
        DeviceAction.Swipe(Direction.LEFT, expect = expect),
        DeviceAction.Scroll(Direction.UP, expect = expect),
        DeviceAction.Wait(expect, timeoutMs = 3_500),
    )

    private fun traceOf(action: DeviceAction) = VerifiedTrace(
        traceId = "t-round-trip",
        goal = "do the thing",
        steps = listOf(
            TraceStep(action = action, preOk = true, postOk = true, latencyMs = 10),
        ),
        outcome = TaskOutcome.SUCCESS,
        llmCalls = 1,
        totalMs = 100,
        device = "test",
        model = "test",
    )

    /**
     * Rebuild the action from its compiled form.
     *
     * Mirrors `SkillReplayer.materialise`, which is private. Duplicating its
     * logic would let the two drift and the test would then verify itself, so
     * this drives the real replayer through a driver that records what it was
     * asked to dispatch.
     */
    private suspend fun replayed(action: DeviceAction): DeviceAction? {
        val compiled = DefaultSkillCompiler().compile(traceOf(action))
        assertIs<CompileResult.Compiled>(compiled, "compiling ${action::class.simpleName}")

        val recorder = RecordingDriver()
        val replayer = SkillReplayer(
            driver = recorder,
            executor = dev.axon.core.executor.DefaultExecutor(
                recorder,
                settleMs = 0,
                nowMs = { 0 },
                confirmation = dev.axon.core.executor.ConfirmationGate.ALLOW_FOR_TESTING,
            ),
            nowMs = { 0 },
            planner = null,
        )
        replayer.replay(compiled.skill, Goal("do the thing"), emptyMap())
        return recorder.dispatched.firstOrNull()
    }

    @Test
    fun `every action type survives compile and replay unchanged`() = runTest {
        for (original in everyAction) {
            val rebuilt = replayed(original)
            assertNotNull(rebuilt, "${original::class.simpleName} did not survive compilation")
            assertEquals(
                original, rebuilt,
                "${original::class.simpleName} changed across compile → replay",
            )
        }
    }

    @Test
    fun `the corpus covers every action in the wire format`() {
        // Without this, a new action type could be added to §10.1 and simply not
        // be tested — which is exactly how the original bug survived.
        val covered = everyAction.map { it.wireName() }.toSet()
        assertEquals(
            DeviceAction.ACTION_TYPES.toSet(), covered,
            "every §10.1 action must appear in the round-trip corpus",
        )
    }

    @Test
    fun `launch_app keeps its package`() = runTest {
        // The case measured on device. A compiled step with no package produced
        // a replay that could not run, a fall-back to a 66-second cold plan, and
        // a replay_count that incremented for a replay that never happened.
        val rebuilt = replayed(DeviceAction.LaunchApp("com.whatsapp", expect))
        assertIs<DeviceAction.LaunchApp>(rebuilt)
        assertEquals("com.whatsapp", rebuilt.app)
    }

    @Test
    fun `a payload-less key step refuses to act rather than pressing BACK`() = runTest {
        // Previously this replayed as BACK. Dispatching the wrong key is worse
        // than dispatching nothing: a compiled skill is checked by no grammar,
        // no gate and no planner, so "fail closed" is the only defence it has.
        val broken = CompiledSkill(
            manifest = SkillManifest(id = "broken", name = "broken", goalPattern = "do the thing"),
            steps = listOf(
                dev.axon.core.model.CompiledStep(
                    step = 1,
                    selector = null,
                    action = "press_key",
                    expect = expect,
                    // args deliberately empty — a skill from before E22c.
                ),
            ),
        )

        val recorder = RecordingDriver()
        val replayer = SkillReplayer(
            driver = recorder,
            executor = dev.axon.core.executor.DefaultExecutor(
                recorder,
                settleMs = 0,
                nowMs = { 0 },
                confirmation = dev.axon.core.executor.ConfirmationGate.ALLOW_FOR_TESTING,
            ),
            nowMs = { 0 },
            planner = null,
        )
        val result = replayer.replay(broken, Goal("do the thing"), emptyMap())

        assertTrue(recorder.dispatched.isEmpty(), "nothing may be dispatched without a payload")
        assertTrue(result.result.outcome != TaskOutcome.SUCCESS, "and the replay must not claim success")
    }

    /** Records what it was asked to do; satisfies every assertion. */
    private class RecordingDriver : dev.axon.core.driver.DeviceDriver {
        val dispatched = mutableListOf<DeviceAction>()

        // Presents the element the actions target, so the precondition gate
        // approves and the round trip actually reaches dispatch. An empty tree
        // would make every case fail at the gate and the test would pass
        // vacuously for the types it is meant to check.
        override suspend fun observe() = dev.axon.core.model.UiTree(
            foregroundPackage = "com.whatsapp",
            screenTitle = null,
            nodes = listOf(
                dev.axon.core.model.UiNode(
                    index = 0,
                    role = "button",
                    text = "Send",
                    contentDescription = "Send",
                    clickable = true,
                    editable = true,
                    bounds = dev.axon.core.model.Bounds(0, 0, 100, 50),
                ),
            ),
            capturedAtMs = 0,
        )

        override suspend fun act(action: DeviceAction): dev.axon.core.model.ActResult {
            dispatched += action
            return dev.axon.core.model.ActResult.Dispatched()
        }

        override suspend fun assert(condition: PostCondition) = true

        override fun capabilities() = setOf(
            dev.axon.core.model.Capability.UI_GESTURE,
            dev.axon.core.model.Capability.UI_OBSERVE,
            dev.axon.core.model.Capability.APP_LAUNCH,
        )

        override val deviceFamily = "test"
    }
}

/** The §10.1 discriminator for this action. */
private fun DeviceAction.wireName(): String = when (this) {
    is DeviceAction.Tap -> "tap"
    is DeviceAction.LongPress -> "long_press"
    is DeviceAction.InputText -> "input_text"
    is DeviceAction.Swipe -> "swipe"
    is DeviceAction.Scroll -> "scroll"
    is DeviceAction.LaunchApp -> "launch_app"
    is DeviceAction.PressKey -> "press_key"
    is DeviceAction.Wait -> "wait"
}
