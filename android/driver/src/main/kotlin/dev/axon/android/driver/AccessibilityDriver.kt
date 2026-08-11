package dev.axon.android.driver

import dev.axon.core.driver.DeviceDriver
import dev.axon.core.model.ActResult
import dev.axon.core.model.Capability
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.PostCondition
import dev.axon.core.model.UiTree

/**
 * The Android reference driver (spec §9.1, §12) — the concrete half of C4.
 *
 * Phase 2 implements this against `AccessibilityService`: tree capture through
 * `AccessibilityNodeInfo`, gestures through `dispatchGesture`, and Shizuku for
 * the privileged operations that the accessibility API cannot reach.
 *
 * Declared in Phase 0 with the contract satisfied and the bodies unimplemented,
 * which is the point of the phase: the seam exists and compiles, so nothing above
 * it has to be written against a guess about what Android will provide.
 *
 * Three things about the eventual implementation are already fixed by the spec
 * and are recorded here so Phase 2 does not have to re-derive them:
 *
 *  1. **`observe()` refuses sensitive nodes at capture**, via
 *     [dev.axon.core.perception.SensitivePolicy] — before reading node text, not
 *     after (§16 guardrail 3).
 *  2. **`act()` never judges success.** It reports dispatch only; the verifier
 *     decides outcomes from a fresh observation (§7.6).
 *  3. **`assert()` runs no model.** Deterministic tree queries, which is what
 *     makes C2 a contribution rather than a wrapper around self-assessment.
 */
public class AccessibilityDriver : DeviceDriver {

    override suspend fun observe(): UiTree =
        TODO("Phase 2: capture AccessibilityNodeInfo tree, apply SensitivePolicy at read time")

    override suspend fun act(action: DeviceAction): ActResult =
        TODO("Phase 2: dispatchGesture / performAction; report dispatch only, never success")

    override suspend fun assert(condition: PostCondition): Boolean =
        TODO("Phase 4: deterministic tree query — no LLM")

    override fun capabilities(): Set<Capability> = setOf(
        Capability.UI_OBSERVE,
        Capability.UI_GESTURE,
        Capability.APP_LAUNCH,
    )

    override val deviceFamily: String
        get() = TODO("Phase 2: derive from Build.MODEL and Build.VERSION.SDK_INT")
}
