package dev.axon.core.executor

import dev.axon.core.model.DeviceAction
import dev.axon.core.model.Direction
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The §7.5 precondition gate, tested with hand-built trees and no device.
 *
 * This is the payoff of the C4 portability seam being real: the defence that
 * stops a hallucinated action reaching the phone is pure Kotlin over a data
 * structure, so its behaviour is pinned down in CI on every commit rather than
 * inferred from watching a demo.
 */
class PreconditionGateTest {

    private val expect = PostCondition(PostConditionType.NODE_PRESENT, "whatever")

    private fun tree(vararg nodes: UiNode) = UiTree(
        foregroundPackage = "com.whatsapp",
        screenTitle = "Ammi",
        nodes = nodes.toList(),
        capturedAtMs = 0,
    )

    private fun button(index: Int, label: String, enabled: Boolean = true) = UiNode(
        index = index, role = "button", text = label, contentDescription = label,
        clickable = true, enabled = enabled,
    )

    private fun field(index: Int, label: String) = UiNode(
        index = index, role = "edit_text", contentDescription = label,
        clickable = true, editable = true,
    )

    private fun label(index: Int, text: String) = UiNode(
        index = index, role = "text", text = text,
    )

    // -----------------------------------------------------------------
    // The case the gate exists for
    // -----------------------------------------------------------------

    @Test
    fun `a hallucinated target is rejected before the device is touched`() {
        // §7.5's own example. The planner emits a perfectly grammar-valid action
        // naming a button that is not on screen. Nothing about the JSON is wrong;
        // it simply refers to a world that does not exist.
        val state = tree(button(0, "Attach"), button(1, "Camera"))
        val action = DeviceAction.Tap(Target(TargetBy.TEXT, "Send"), expect)

        val result = PreconditionGate.check(action, state)

        val rejected = assertIs<GateResult.Rejected>(result)
        assertIs<PreconditionFailure.TargetNotFound>(rejected.failure)
    }

    @Test
    fun `rejection names what is actually on screen`() {
        // A bare "target not found" invites the model to retry the same action.
        // Naming the near-misses is what makes the re-plan (§7.6) differ from the
        // attempt that just failed.
        val state = tree(button(0, "Send message"), button(1, "Attach"))
        val action = DeviceAction.Tap(Target(TargetBy.TEXT, "Send"), expect)

        val rejected = assertIs<GateResult.Rejected>(PreconditionGate.check(action, state))

        assertTrue("Send message" in rejected.alternatives, "expected the near-miss to be offered")
        assertTrue("Send message" in rejected.explain(), rejected.explain())
    }

    @Test
    fun `a resolvable target is allowed and the resolved node is returned`() {
        val send = button(2, "Send")
        val state = tree(button(0, "Attach"), button(1, "Camera"), send)
        val action = DeviceAction.Tap(Target(TargetBy.CONTENT_DESC, "Send"), expect)

        val allowed = assertIs<GateResult.Allowed>(PreconditionGate.check(action, state))

        // The node itself is returned so the driver acts on what the gate
        // approved, rather than re-resolving and possibly picking another.
        assertEquals(2, allowed.node?.index)
    }

    // -----------------------------------------------------------------
    // Disabled, ambiguous, wrong kind
    // -----------------------------------------------------------------

    @Test
    fun `a disabled target is rejected as disabled, not as missing`() {
        // The distinction matters to the planner: absent means "look elsewhere",
        // disabled means "something else must happen first".
        val state = tree(button(0, "Send", enabled = false))
        val action = DeviceAction.Tap(Target(TargetBy.TEXT, "Send"), expect)

        val rejected = assertIs<GateResult.Rejected>(PreconditionGate.check(action, state))
        assertIs<PreconditionFailure.TargetDisabled>(rejected.failure)
    }

    @Test
    fun `two equally valid targets are refused as ambiguous`() {
        // Guessing between two live "Delete" buttons is not a recoverable error.
        val state = tree(button(0, "Delete"), button(1, "Delete"))
        val action = DeviceAction.Tap(Target(TargetBy.TEXT, "Delete"), expect)

        val rejected = assertIs<GateResult.Rejected>(PreconditionGate.check(action, state))
        val failure = assertIs<PreconditionFailure.AmbiguousTarget>(rejected.failure)
        assertEquals(2, failure.matches)
    }

    @Test
    fun `a heading sharing a button's label does not create ambiguity`() {
        // Extremely common on real screens: a section heading and its control
        // carry the same words. Only one can receive a tap, so the action is
        // determinate and refusing it would be a false rejection.
        val state = tree(label(0, "Send"), button(1, "Send"))
        val action = DeviceAction.Tap(Target(TargetBy.TEXT, "Send"), expect)

        val allowed = assertIs<GateResult.Allowed>(PreconditionGate.check(action, state))
        assertEquals(1, allowed.node?.index)
    }

    @Test
    fun `typing into a button is refused`() {
        // Strict on purpose: unlike a missed tap, a mis-aimed input_text puts
        // keystrokes somewhere the user did not intend.
        val state = tree(button(0, "Message"))
        val action = DeviceAction.InputText(Target(TargetBy.TEXT, "Message"), "on my way", expect)

        val rejected = assertIs<GateResult.Rejected>(PreconditionGate.check(action, state))
        val failure = assertIs<PreconditionFailure.WrongAffordance>(rejected.failure)
        assertTrue("text field" in failure.explanation, failure.explanation)
    }

    @Test
    fun `typing into a text field is allowed`() {
        val state = tree(button(0, "Attach"), field(1, "Message"))
        val action = DeviceAction.InputText(Target(TargetBy.CONTENT_DESC, "Message"), "hi", expect)

        val allowed = assertIs<GateResult.Allowed>(PreconditionGate.check(action, state))
        assertEquals(1, allowed.node?.index)
    }

    @Test
    fun `a row that never declares clickable is still tappable`() {
        // Android's accessibility flags are advisory and frequently wrong; plenty
        // of tappable list rows never set `clickable`. Insisting on the flag would
        // make AXON fail on real apps for reasons unrelated to the model, so a
        // node that declares nothing gets the benefit of the doubt.
        val row = UiNode(index = 0, role = "list_item", text = "Ammi")
        val action = DeviceAction.Tap(Target(TargetBy.TEXT, "Ammi"), expect)

        val allowed = assertIs<GateResult.Allowed>(PreconditionGate.check(action, tree(row)))
        assertEquals(0, allowed.node?.index)
    }

    // -----------------------------------------------------------------
    // Device-directed actions
    // -----------------------------------------------------------------

    @Test
    fun `device-directed actions need no target`() {
        // launch_app, press_key and wait address the device, not an element, so
        // there is nothing to resolve and nothing to reject.
        val empty = UiTree.empty("com.example.game", capturedAtMs = 0)

        for (action in listOf<DeviceAction>(
            DeviceAction.LaunchApp("com.whatsapp", expect),
            DeviceAction.PressKey(dev.axon.core.model.DeviceKey.BACK, expect),
            DeviceAction.Wait(expect),
        )) {
            val allowed = assertIs<GateResult.Allowed>(
                PreconditionGate.check(action, empty),
                "${action::class.simpleName} should not require a target",
            )
            assertEquals(null, allowed.node)
        }
    }

    @Test
    fun `an untargeted swipe is allowed on an empty screen`() {
        // §17's canvas/DRM case: the tree is empty and the only sane moves are
        // device-directed. The gate must not block them, or the agent has no way
        // out of a screen it cannot read.
        val empty = UiTree.empty("com.example.game", capturedAtMs = 0)
        val action = DeviceAction.Swipe(Direction.UP, expect = expect)

        assertIs<GateResult.Allowed>(PreconditionGate.check(action, empty))
    }

    @Test
    fun `scrolling a non-scrollable element is refused`() {
        val state = tree(button(0, "Attach"))
        val action = DeviceAction.Scroll(
            Direction.DOWN,
            Target(TargetBy.TEXT, "Attach"),
            expect,
        )

        val rejected = assertIs<GateResult.Rejected>(PreconditionGate.check(action, state))
        assertIs<PreconditionFailure.WrongAffordance>(rejected.failure)
    }

    @Test
    fun `coordinate targets resolve through node bounds`() {
        // TargetBy.COORD is last-resort but must still work; the compiler (§7.7)
        // penalises it precisely because it does not survive a layout change.
        val node = UiNode(
            index = 0, role = "button", text = "Send", clickable = true,
            bounds = dev.axon.core.model.Bounds(0, 0, 100, 100),
        )
        val action = DeviceAction.Tap(Target(TargetBy.COORD, "50,50"), expect)

        val allowed = assertIs<GateResult.Allowed>(PreconditionGate.check(action, tree(node)))
        assertEquals(0, allowed.node?.index)
    }

    @Test
    fun `a malformed coordinate is rejected rather than throwing`() {
        // The value is model-authored and therefore untrusted. A parse failure
        // must become an ordinary rejection the planner can recover from, not an
        // exception that ends the run.
        val node = UiNode(
            index = 0, role = "button", clickable = true,
            bounds = dev.axon.core.model.Bounds(0, 0, 100, 100),
        )
        val action = DeviceAction.Tap(Target(TargetBy.COORD, "not,a,point"), expect)

        assertIs<GateResult.Rejected>(PreconditionGate.check(action, tree(node)))
    }
}
