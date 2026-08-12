package dev.axon.core.executor

import dev.axon.core.driver.DeviceDriver
import dev.axon.core.model.ActResult
import dev.axon.core.model.Capability
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.MismatchKind
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree
import dev.axon.core.model.VerifyResult
import dev.axon.core.verifier.PostConditionEvaluator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The full act-and-verify cycle, exercised with no device attached.
 *
 * This is the clearest demonstration of what the C4 portability seam buys beyond
 * portability. [FakeDriver] is a `DeviceDriver` whose "screen" is a list of
 * trees; swapping it for the Android one changes nothing above the seam. So the
 * behaviour that actually protects the user — refusing impossible actions,
 * refusing to call a dispatched gesture a success, telling a no-op apart from a
 * wrong outcome — is pinned down in CI on every commit, and a reviewer can check
 * it without owning the handset.
 */
class DefaultExecutorTest {

    private val expectSent = PostCondition(PostConditionType.NODE_PRESENT, "Sent")

    private fun button(index: Int, label: String, enabled: Boolean = true) = UiNode(
        index = index, role = "button", text = label, contentDescription = label,
        bounds = dev.axon.core.model.Bounds(0, index * 100, 200, index * 100 + 80),
        clickable = true, enabled = enabled,
    )

    private fun screen(vararg nodes: UiNode, pkg: String = "com.whatsapp") =
        UiTree(pkg, "Ammi", nodes.toList(), capturedAtMs = 0)

    /**
     * A scripted device.
     *
     * [screens] is consumed one per `observe()`, so a test states the sequence of
     * worlds the executor will meet. [dispatch] decides what the driver reports,
     * which lets a test separate "the gesture failed" from "the gesture worked
     * but achieved nothing" — two cases the executor must handle differently and
     * that are hard to provoke on a real phone.
     */
    private class FakeDriver(
        private val screens: MutableList<UiTree>,
        private val dispatch: (DeviceAction) -> ActResult = { ActResult.Dispatched() },
    ) : DeviceDriver {
        var actions = mutableListOf<DeviceAction>()

        override suspend fun observe(): UiTree =
            if (screens.size > 1) screens.removeAt(0) else screens.first()

        override suspend fun act(action: DeviceAction): ActResult {
            actions += action
            return dispatch(action)
        }

        override suspend fun assert(condition: PostCondition): Boolean =
            PostConditionEvaluator.evaluate(condition, observe())

        override fun capabilities(): Set<Capability> =
            setOf(Capability.UI_OBSERVE, Capability.UI_GESTURE)

        override val deviceFamily: String = "fake/test"
    }

    private fun executor(driver: DeviceDriver, budget: Int = 15) =
        DefaultExecutor(driver, budget = budget, settleMs = 0, nowMs = { 0L })

    // -----------------------------------------------------------------

    @Test
    fun `a successful action is committed`() = runTest {
        val before = screen(button(0, "Send"))
        val after = screen(button(0, "Sent"))
        val driver = FakeDriver(mutableListOf(after))

        val outcome = executor(driver).run(
            DeviceAction.Tap(Target(TargetBy.TEXT, "Send"), expectSent),
            before,
        )

        assertTrue(outcome.preOk)
        assertEquals(true, outcome.postOk)
        assertTrue(outcome.committed)
        assertIs<VerifyResult.Match>(outcome.verifyResult)
    }

    @Test
    fun `a hallucinated action never reaches the device`() = runTest {
        // The §7.5 guarantee, stated as a test: not merely that the step is
        // marked failed, but that `act` was never called. An ungated agent that
        // mis-resolves "Send" does not do nothing — it taps whatever occupies
        // those coordinates.
        val before = screen(button(0, "Attach"))
        val driver = FakeDriver(mutableListOf(before))

        val outcome = executor(driver).run(
            DeviceAction.Tap(Target(TargetBy.TEXT, "Send"), expectSent),
            before,
        )

        assertFalse(outcome.preOk)
        assertTrue(driver.actions.isEmpty(), "the device was touched despite a failed gate")
    }

    @Test
    fun `a rejected action records postOk as null, not false`() = runTest {
        // The post-condition was never evaluated, because nothing ran. Recording
        // it as `false` would make the trace claim the world was checked and
        // found wanting, when it was not checked at all — and traces are the
        // compiler's input and the evaluation's ground truth.
        val before = screen(button(0, "Attach"))
        val outcome = executor(FakeDriver(mutableListOf(before))).run(
            DeviceAction.Tap(Target(TargetBy.TEXT, "Send"), expectSent),
            before,
        )

        assertNull(outcome.postOk)
    }

    @Test
    fun `a dispatched gesture that changes nothing is not a success`() = runTest {
        // The core C2 case. The platform accepted the tap; the screen is
        // identical. A naive agent proceeds believing it opened something.
        val before = screen(button(0, "Send"))
        val driver = FakeDriver(mutableListOf(before))   // same screen returned after

        val outcome = executor(driver).run(
            DeviceAction.Tap(Target(TargetBy.TEXT, "Send"), expectSent),
            before,
        )

        assertTrue(outcome.preOk, "the gate should have allowed this")
        assertEquals(false, outcome.postOk)
        assertIs<ActResult.Dispatched>(outcome.actResult)

        val mismatch = assertIs<VerifyResult.Mismatch>(outcome.verifyResult)
        assertEquals(MismatchKind.NO_CHANGE, mismatch.kind)
    }

    @Test
    fun `a changed screen that misses the expectation is distinguishable from a no-op`() = runTest {
        // "Nothing happened" wants a different target; "something happened, just
        // not that" wants a different expectation. Self-healing can only choose
        // between them if the executor reports which occurred.
        val before = screen(button(0, "Send"))
        val after = screen(button(0, "Error"), button(1, "Retry"))
        val driver = FakeDriver(mutableListOf(after))

        val outcome = executor(driver).run(
            DeviceAction.Tap(Target(TargetBy.TEXT, "Send"), expectSent),
            before,
        )

        val mismatch = assertIs<VerifyResult.Mismatch>(outcome.verifyResult)
        assertEquals(MismatchKind.CONDITION_UNMET, mismatch.kind)
        assertTrue("Error" in mismatch.observed, "observed should name what is on screen: ${mismatch.observed}")
        assertTrue("Retry" in mismatch.observed)
    }

    @Test
    fun `a failed dispatch skips verification entirely`() = runTest {
        // Verifying a post-condition for an action that never happened would
        // report a mismatch, which reads as "the action was wrong" rather than
        // "the action could not be performed".
        val before = screen(button(0, "Send"))
        val driver = FakeDriver(
            mutableListOf(before),
            dispatch = { ActResult.Failed("gesture refused by the platform") },
        )

        val outcome = executor(driver).run(
            DeviceAction.Tap(Target(TargetBy.TEXT, "Send"), expectSent),
            before,
        )

        assertTrue(outcome.preOk)
        assertEquals(false, outcome.postOk)
        assertIs<ActResult.Failed>(outcome.actResult)
    }

    @Test
    fun `the action budget is enforced`() = runTest {
        // §7.5: a hard cap, not a heuristic. A small model that oscillates
        // between two screens will do so until the battery dies.
        val before = screen(button(0, "Send"))
        val after = screen(button(0, "Sent"))
        val driver = FakeDriver(mutableListOf(after))
        val exec = executor(driver, budget = 2)

        val action = DeviceAction.Tap(Target(TargetBy.TEXT, "Send"), expectSent)
        exec.run(action, before)
        exec.run(action, before)
        val third = exec.run(action, before)

        assertFalse(third.preOk)
        assertTrue("budget" in third.actResult.let { (it as ActResult.Failed).reason })
        assertEquals(2, driver.actions.size, "the device was touched after the budget ran out")
    }

    @Test
    fun `reset restores the budget between tasks`() = runTest {
        val before = screen(button(0, "Send"))
        val driver = FakeDriver(mutableListOf(screen(button(0, "Sent"))))
        val exec = executor(driver, budget = 1)
        val action = DeviceAction.Tap(Target(TargetBy.TEXT, "Send"), expectSent)

        exec.run(action, before)
        assertFalse(exec.run(action, before).preOk)

        exec.reset()
        assertTrue(exec.run(action, before).preOk, "budget should be per-task, not per-process")
    }

    @Test
    fun `device-directed actions run without a target`() = runTest {
        val before = UiTree.empty("com.example.game", capturedAtMs = 0)
        val after = screen(button(0, "Home"), pkg = "com.android.launcher3")
        val driver = FakeDriver(mutableListOf(after))

        val outcome = executor(driver).run(
            DeviceAction.PressKey(
                dev.axon.core.model.DeviceKey.HOME,
                PostCondition(PostConditionType.APP_FOREGROUND, "com.android.launcher3"),
            ),
            before,
        )

        assertTrue(outcome.preOk)
        assertEquals(true, outcome.postOk)
    }

    @Test
    fun `state hashes bracket the step`() = runTest {
        // The compiler (§7.7) uses these to tell a genuine loop from legitimate
        // repetition, so they must record the actual before/after, not the same
        // tree twice.
        val before = screen(button(0, "Send"))
        val after = screen(button(0, "Sent"))
        val driver = FakeDriver(mutableListOf(after))

        val outcome = executor(driver).run(
            DeviceAction.Tap(Target(TargetBy.TEXT, "Send"), expectSent),
            before,
        )

        assertEquals(before.contentHash, outcome.stateHashBefore)
        assertEquals(after.contentHash, outcome.stateHashAfter)
        assertTrue(outcome.stateHashBefore != outcome.stateHashAfter)
    }
}
