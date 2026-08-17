package dev.axon.core.executor

import dev.axon.core.driver.DeviceDriver
import dev.axon.core.model.ActResult
import dev.axon.core.model.Bounds
import dev.axon.core.model.Capability
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.DeviceKey
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree
import dev.axon.core.verifier.PostConditionEvaluator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §16's irreversible-action gate.
 *
 * Written after E18b, where the agent opened a dialer with five steps of
 * unconstrained budget remaining. Nothing had gone wrong; nothing was stopping
 * the next action from being `tap "Call"`.
 *
 * The property that matters is the last test in this file: an executor with no
 * confirmation gate wired up **refuses** irreversible actions rather than
 * performing them. Failing closed means a wiring mistake produces an agent that
 * declines to send messages, not one that sends them silently — and only the
 * second failure is unrecoverable.
 */
class ConfirmationPolicyTest {

    private val expect = PostCondition(PostConditionType.NODE_PRESENT, "x")

    private fun node(label: String) = UiNode(
        index = 0, role = "button", text = label, contentDescription = label,
        bounds = Bounds(0, 0, 100, 50), clickable = true,
    )

    private fun tree(pkg: String = "com.whatsapp", vararg labels: String) = UiTree(
        foregroundPackage = pkg,
        screenTitle = null,
        nodes = labels.mapIndexed { i, l ->
            UiNode(i, "button", text = l, contentDescription = l, clickable = true,
                bounds = Bounds(0, i * 60, 100, i * 60 + 50))
        },
        capturedAtMs = 0,
    )

    private fun tapOn(label: String) = DeviceAction.Tap(Target(TargetBy.TEXT, label), expect)

    // -----------------------------------------------------------------

    @Test
    fun `the E18b case — a dialer call button is held`() {
        val reason = ConfirmationPolicy.requiresConfirmation(
            tapOn("Call"),
            node("Call"),
            tree(pkg = "com.sh.smart.caller", "Call"),
        )

        assertNotNull(reason, "tapping Call must require confirmation")
        assertTrue("call" in reason.effect, reason.effect)
    }

    @Test
    fun `sending is held`() {
        for (label in listOf("Send", "Post", "Share", "Publish", "Submit", "Reply")) {
            assertNotNull(
                ConfirmationPolicy.requiresConfirmation(tapOn(label), node(label), tree()),
                "'$label' should require confirmation",
            )
        }
    }

    @Test
    fun `spending money is held`() {
        for (label in listOf("Pay", "Send money", "Transfer", "Buy", "Checkout", "Place order")) {
            assertNotNull(
                ConfirmationPolicy.requiresConfirmation(tapOn(label), node(label), tree()),
                "'$label' should require confirmation",
            )
        }
    }

    @Test
    fun `destroying data is held`() {
        for (label in listOf("Delete", "Remove", "Erase", "Uninstall", "Leave group", "Block")) {
            assertNotNull(
                ConfirmationPolicy.requiresConfirmation(tapOn(label), node(label), tree()),
                "'$label' should require confirmation",
            )
        }
    }

    @Test
    fun `urdu and roman-urdu terms are held`() {
        // §2.1 names South Asian users. A policy that only recognises English
        // "Send" would fail exactly the population the project claims to serve,
        // on exactly the actions where failure is irreversible.
        for (label in listOf("Bhejo", "Paisay bhejo", "Hataao", "Call kar")) {
            assertNotNull(
                ConfirmationPolicy.requiresConfirmation(tapOn(label), node(label), tree()),
                "'$label' should require confirmation",
            )
        }
    }

    @Test
    fun `ordinary navigation is not held`() {
        // The false-positive side. Over-asking is the safer error but not a free
        // one: an agent that prompts on every tap trains the user to approve
        // without reading, which is worse than not asking at all.
        for (label in listOf("Search", "Settings", "Back", "Camera", "Attach", "Contacts", "Gallery")) {
            assertNull(
                ConfirmationPolicy.requiresConfirmation(tapOn(label), node(label), tree()),
                "'$label' should NOT require confirmation",
            )
        }
    }

    @Test
    fun `a generic OK is held only inside a sensitive app`() {
        // "OK" in a calculator is noise. "OK" in a wallet is the last thing
        // between the user and a transfer.
        assertNull(
            ConfirmationPolicy.requiresConfirmation(
                tapOn("OK"), node("OK"), tree(pkg = "com.android.calculator2", "OK"),
            ),
        )
        assertNotNull(
            ConfirmationPolicy.requiresConfirmation(
                tapOn("OK"), node("OK"), tree(pkg = "com.easypaisa.wallet", "OK"),
            ),
        )
    }

    @Test
    fun `device-directed and navigational actions are never held`() {
        // Pressing back, waiting and scrolling change no data and post nothing.
        // Holding them would make the gate meaningless through sheer volume.
        val empty = UiTree.empty("com.example", capturedAtMs = 0)
        for (action in listOf<DeviceAction>(
            DeviceAction.PressKey(DeviceKey.BACK, expect),
            DeviceAction.Wait(expect),
            DeviceAction.Scroll(dev.axon.core.model.Direction.DOWN, expect = expect),
        )) {
            assertNull(
                ConfirmationPolicy.requiresConfirmation(action, null, empty),
                "${action::class.simpleName} should not require confirmation",
            )
        }
    }

    // -----------------------------------------------------------------
    // Enforcement
    // -----------------------------------------------------------------

    private class RecordingDriver(private val screen: UiTree) : DeviceDriver {
        val actions = mutableListOf<DeviceAction>()
        override suspend fun observe() = screen
        override suspend fun act(action: DeviceAction): ActResult {
            actions += action
            return ActResult.Dispatched()
        }
        override suspend fun assert(condition: PostCondition) =
            PostConditionEvaluator.evaluate(condition, screen)
        override fun capabilities() = setOf(
            Capability.UI_OBSERVE, Capability.UI_GESTURE, Capability.APP_LAUNCH,
        )
        override val deviceFamily = "fake/test"
    }

    @Test
    fun `a denied confirmation stops the action reaching the device`() = runTest {
        val screen = tree(pkg = "com.sh.smart.caller", "Call")
        val driver = RecordingDriver(screen)

        val outcome = DefaultExecutor(
            driver, settleMs = 0, nowMs = { 0L },
            confirmation = ConfirmationGate { false },
        ).run(tapOn("Call"), screen)

        assertTrue(outcome.preOk, "the precondition gate should have passed")
        assertIs<ActResult.Refused>(outcome.actResult)
        assertTrue(driver.actions.isEmpty(), "the call was dispatched despite being refused")
        // Never evaluated, because nothing happened.
        assertNull(outcome.postOk)
    }

    @Test
    fun `an approved confirmation lets the action through`() = runTest {
        val screen = tree(pkg = "com.sh.smart.caller", "Call")
        val driver = RecordingDriver(screen)

        DefaultExecutor(
            driver, settleMs = 0, nowMs = { 0L },
            confirmation = ConfirmationGate { true },
        ).run(tapOn("Call"), screen)

        assertTrue(driver.actions.isNotEmpty(), "approval should have let the action through")
    }

    @Test
    fun `an executor with no gate wired up refuses irreversible actions`() = runTest {
        // The property this whole file exists for. Forgetting to wire the gate
        // must yield an agent that declines to send messages, not one that sends
        // them silently — only the second failure is unrecoverable, and it is the
        // one a hurried refactor would produce.
        val screen = tree(pkg = "com.whatsapp", "Send")
        val driver = RecordingDriver(screen)

        val outcome = DefaultExecutor(driver, settleMs = 0, nowMs = { 0L })
            .run(tapOn("Send"), screen)

        assertIs<ActResult.Refused>(outcome.actResult)
        assertTrue(driver.actions.isEmpty(), "default-open: an irreversible action was dispatched")
    }

    @Test
    fun `harmless actions pass without a gate`() = runTest {
        // Fail-closed must not mean fail-useless. With no gate wired, navigation
        // still works; only irreversible actions are withheld.
        val screen = tree(pkg = "com.whatsapp", "Search")
        val driver = RecordingDriver(screen)

        DefaultExecutor(driver, settleMs = 0, nowMs = { 0L }).run(tapOn("Search"), screen)

        assertFalse(driver.actions.isEmpty(), "a harmless tap should not need approval")
    }
}
