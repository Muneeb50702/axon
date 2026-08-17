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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * §7.10's capability sandbox, enforced where the action happens — **E29**.
 *
 * The README claimed, from Phase 0, that *"nothing runs a capability it did not
 * declare and receive"*. That was true at skill-install time and nowhere else:
 *
 * - the **PLAN path has no skill**, so a freshly planned action had nothing to
 *   check against and was never gated;
 * - `PreconditionFailure.CapabilityDenied` was in the type system and **never
 *   constructed** — the failure was expressible and unreachable;
 * - the app built its store with the default `granted = emptySet()`, so on the
 *   only configuration that ships, nothing was granted and nothing noticed.
 *
 * A safety property checked at install and not at use is not enforced. These
 * tests are the claim's evidence.
 */
class CapabilityPolicyTest {

    private val expect = PostCondition(PostConditionType.NODE_PRESENT, "done")

    private fun button(label: String) = UiNode(
        index = 0, role = "button", text = label, contentDescription = label,
        bounds = Bounds(0, 0, 100, 40), clickable = true,
    )

    private fun screen() = UiTree("com.example", null, listOf(button("Go")), capturedAtMs = 0)

    private class Device(private val supports: Set<Capability>) : DeviceDriver {
        var dispatched = 0
        override suspend fun observe() = UiTree(
            "com.example", null,
            listOf(
                UiNode(0, "button", text = "Go", contentDescription = "Go",
                    bounds = Bounds(0, 0, 100, 40), clickable = true),
            ),
            capturedAtMs = 0,
        )
        override suspend fun act(action: DeviceAction): ActResult {
            dispatched++
            return ActResult.Dispatched()
        }
        override suspend fun assert(condition: PostCondition) =
            PostConditionEvaluator.evaluate(condition, observe())
        override fun capabilities() = supports
        override val deviceFamily = "fake/test"
    }

    private val tap = DeviceAction.Tap(Target(TargetBy.CONTENT_DESC, "Go"), expect)
    private val launch = DeviceAction.LaunchApp("com.whatsapp", expect)

    // -------------------------------------------------------- the mapping ----

    @Test
    fun `every action declares the capability it needs`() {
        // Exhaustive by construction — a new DeviceAction variant will not
        // compile until it appears in `required`. The alternative, a `when` with
        // an `else`, would silently grant every future action the weakest
        // requirement and ship a new capability ungated.
        assertEquals(Capability.UI_GESTURE, CapabilityPolicy.required(tap))
        assertEquals(Capability.APP_LAUNCH, CapabilityPolicy.required(launch))
        assertEquals(
            Capability.UI_GESTURE,
            CapabilityPolicy.required(DeviceAction.PressKey(DeviceKey.BACK, expect)),
        )
        // Waiting touches nothing; requiring a grant to *not act* would block a
        // task from pausing for a screen to load.
        assertEquals(
            Capability.UI_OBSERVE,
            CapabilityPolicy.required(DeviceAction.Wait(expect)),
        )
    }

    @Test
    fun `not-granted and unsupported are different verdicts`() {
        // The remedies are entirely different — a permission screen versus a
        // driver that will never implement the operation on this platform.
        // Reporting them alike sends the user somewhere that cannot help.
        assertIs<CapabilityVerdict.NotGranted>(
            CapabilityPolicy.check(launch, granted = emptySet(), supported = setOf(Capability.APP_LAUNCH)),
        )
        assertIs<CapabilityVerdict.Unsupported>(
            CapabilityPolicy.check(launch, granted = setOf(Capability.APP_LAUNCH), supported = emptySet()),
        )
        assertIs<CapabilityVerdict.Allowed>(
            CapabilityPolicy.check(
                launch,
                granted = setOf(Capability.APP_LAUNCH),
                supported = setOf(Capability.APP_LAUNCH),
            ),
        )
    }

    @Test
    fun `the default grant excludes the domains §6_3 rules out`() {
        // A default of "everything" would make the check ornamental. UI_ONLY is
        // enough to observe, tap and launch — and nothing from the
        // stalkerware-adjacent set.
        assertTrue(Capability.CONTACTS_READ !in CapabilityPolicy.UI_ONLY)
        assertTrue(Capability.SETTINGS_WRITE !in CapabilityPolicy.UI_ONLY)
    }

    // ------------------------------------------------------- enforcement ----

    @Test
    fun `an ungranted action is refused before the device is touched`() = runTest {
        val device = Device(supports = setOf(Capability.UI_OBSERVE, Capability.UI_GESTURE, Capability.APP_LAUNCH))
        val executor = DefaultExecutor(
            device, settleMs = 0, nowMs = { 0 },
            granted = setOf(Capability.UI_OBSERVE), // no gesture
            confirmation = ConfirmationGate.ALLOW_FOR_TESTING,
        )

        val outcome = executor.run(tap, screen())

        assertTrue(!outcome.preOk)
        assertEquals(0, device.dispatched, "nothing may reach the device")
        assertIs<ActResult.Failed>(outcome.actResult)
    }

    @Test
    fun `an action the driver cannot perform is refused`() = runTest {
        val device = Device(supports = setOf(Capability.UI_OBSERVE, Capability.UI_GESTURE))
        val executor = DefaultExecutor(
            device, settleMs = 0, nowMs = { 0 },
            granted = CapabilityPolicy.UI_ONLY,
            confirmation = ConfirmationGate.ALLOW_FOR_TESTING,
        )

        val outcome = executor.run(launch, screen())

        assertTrue(!outcome.preOk)
        assertEquals(0, device.dispatched)
    }

    @Test
    fun `a granted, supported action goes through`() = runTest {
        val device = Device(supports = CapabilityPolicy.UI_ONLY)
        val executor = DefaultExecutor(
            device, settleMs = 0, nowMs = { 0 },
            granted = CapabilityPolicy.UI_ONLY,
            confirmation = ConfirmationGate.ALLOW_FOR_TESTING,
        )

        executor.run(tap, screen())

        assertEquals(1, device.dispatched)
    }

    @Test
    fun `the capability check runs before the precondition gate`() = runTest {
        // "You may not do this at all" outranks "the thing you named is not on
        // screen". Checking the gate first would do work to produce a more
        // specific reason for refusing — and would report the wrong one, sending
        // a user to hunt for a missing button when the real answer is that AXON
        // was never allowed to tap.
        val device = Device(supports = CapabilityPolicy.UI_ONLY)
        val executor = DefaultExecutor(
            device, settleMs = 0, nowMs = { 0 },
            granted = emptySet(),
            confirmation = ConfirmationGate.ALLOW_FOR_TESTING,
        )

        val outcome = executor.run(
            // A target that is NOT on screen: the gate would also reject this.
            DeviceAction.Tap(Target(TargetBy.CONTENT_DESC, "Nonexistent"), expect),
            screen(),
        )

        val failed = assertIs<ActResult.Failed>(outcome.actResult)
        assertTrue(
            "capability" in failed.reason.lowercase(),
            "the capability refusal must win over the gate's; got: ${failed.reason}",
        )
    }
}
