package dev.axon.core.executor

import dev.axon.core.model.Capability
import dev.axon.core.model.DeviceAction

/**
 * Which capability an action needs (§7.10, E29).
 *
 * ## The claim this exists to make true
 *
 * The README has said, since Phase 0:
 *
 * > **Per-skill revocable capabilities.** Nothing runs a capability it did not
 * > declare and receive.
 *
 * That was enforced **at skill-install time only**. `SkillStore.install()`
 * compared a manifest's declared capabilities against the granted set and
 * returned `NeedsCapabilities` — and then nothing checked again. Three holes
 * followed from that:
 *
 * 1. The **PLAN path involves no skill at all**, so a freshly planned action had
 *    no manifest to check and was never gated.
 * 2. `PreconditionFailure.CapabilityDenied` existed in the type system and was
 *    **never constructed anywhere** — the failure was expressible and
 *    unreachable.
 * 3. The app built its store with the default `granted = emptySet()`, so on the
 *    only configuration that ships, nothing had been granted and nothing noticed.
 *
 * A safety property enforced at install and not at use is not enforced. This
 * maps every action to the capability it actually needs, so the check happens
 * where the action happens.
 *
 * ## Two different questions, deliberately kept apart
 *
 * - **Can the device do this?** — [dev.axon.core.driver.DeviceDriver.capabilities].
 *   A property of the platform and the granted OS permissions.
 * - **May AXON do this?** — the grant set. A property of what the *user* allowed.
 *
 * Both must hold. Collapsing them would mean a capability the OS happens to
 * expose is one AXON may use, which is precisely the reasoning that makes an
 * accessibility-API agent indistinguishable from the stalkerware built on the
 * same substrate (§6.3).
 */
public object CapabilityPolicy {

    /**
     * The capability [action] requires.
     *
     * Exhaustive on purpose: a new [DeviceAction] variant will not compile until
     * it declares what it needs. The alternative — a `when` with an `else` —
     * would silently grant every future action the weakest requirement, and the
     * failure would be a new capability shipping ungated.
     */
    public fun required(action: DeviceAction): Capability = when (action) {
        is DeviceAction.Tap,
        is DeviceAction.LongPress,
        is DeviceAction.InputText,
        is DeviceAction.Swipe,
        is DeviceAction.Scroll,
        is DeviceAction.PressKey,
        -> Capability.UI_GESTURE

        is DeviceAction.LaunchApp -> Capability.APP_LAUNCH

        // Waiting touches nothing. Requiring a grant to *not act* would mean a
        // task could be blocked from pausing for a screen to load, which is the
        // opposite of a safety property.
        is DeviceAction.Wait -> Capability.UI_OBSERVE
    }

    /**
     * May this action be dispatched?
     *
     * @param granted what the user has allowed.
     * @param supported what the device can actually do.
     */
    public fun check(
        action: DeviceAction,
        granted: Set<Capability>,
        supported: Set<Capability>,
    ): CapabilityVerdict {
        val needed = required(action)
        return when {
            needed !in supported -> CapabilityVerdict.Unsupported(needed)
            needed !in granted -> CapabilityVerdict.NotGranted(needed)
            else -> CapabilityVerdict.Allowed
        }
    }

    /**
     * Everything AXON needs to operate a screen at all.
     *
     * Offered as a named default so that a caller wiring up the agent grants a
     * set it can read, rather than passing `Capability.entries` and calling it
     * configuration. Notably it does **not** include the domains §6.3 excludes.
     */
    public val UI_ONLY: Set<Capability> = setOf(
        Capability.UI_OBSERVE,
        Capability.UI_GESTURE,
        Capability.APP_LAUNCH,
    )
}

public sealed interface CapabilityVerdict {
    public data object Allowed : CapabilityVerdict

    /** The user has not allowed this. Recoverable — they can grant it. */
    public data class NotGranted(val capability: Capability) : CapabilityVerdict

    /**
     * The device cannot do this at all.
     *
     * Distinguished from [NotGranted] because the remedies differ entirely:
     * one is a permission screen, the other is a driver that does not implement
     * the operation and never will on this platform. Reporting them as the same
     * failure sends the user to a settings page that cannot help.
     */
    public data class Unsupported(val capability: Capability) : CapabilityVerdict
}
