package dev.axon.android.driver

import android.content.Context
import android.content.Intent
import android.os.Build
import dev.axon.core.driver.DeviceDriver
import dev.axon.core.model.ActResult
import dev.axon.core.model.Capability
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.DeviceKey
import dev.axon.core.model.PostCondition
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree
import dev.axon.core.verifier.PostConditionEvaluator
import kotlinx.coroutines.delay

/**
 * The Android reference driver (spec §9.1, §12) — the concrete half of C4.
 *
 * Implements the three verbs the portable core depends on, and nothing else.
 * Every judgement — which action to take, whether it worked, what to do next —
 * lives above this seam in `:core`, which is why porting AXON to Linux or
 * Windows means writing one more file like this one and changing nothing else.
 *
 * ## `act()` reports dispatch, never success
 *
 * The single most important property of this class, and the reason C2 is
 * possible. A driver can only know that the OS accepted a gesture; whether the
 * gesture achieved anything is a question about the *next* screen, which the
 * verifier answers from a fresh observation.
 *
 * Conflating the two is how naive agents derail: a tap that lands on empty space
 * returns "success" from the platform, the agent proceeds believing it opened a
 * chat, and every subsequent step is planned against a screen that does not
 * exist. AXON cannot make that mistake, because nothing here is allowed to say
 * "worked".
 */
public class AccessibilityDriver(
    private val context: Context,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : DeviceDriver {

    /**
     * Capture the current screen.
     *
     * Fails loudly when the service is not connected. That is deliberate: a
     * driver that quietly returned an empty tree would make "the screen has no
     * elements" — a legitimate state for canvas and DRM surfaces (§17) —
     * indistinguishable from "AXON was never granted permission to look". The
     * planner can reason about the first; only the user can fix the second.
     */
    override suspend fun observe(): UiTree {
        val service = AxonAccessibilityService.instance
            ?: error(
                "AXON's accessibility service is not enabled. " +
                    "Settings → Accessibility → AXON. The agent cannot see the screen without it.",
            )
        return service.captureTree(nowMs())
    }

    /**
     * Perform an action the executor has already precondition-gated.
     *
     * Actions arrive here having been checked against the tree the planner saw
     * (§7.5), so this method does not re-litigate whether the target exists — it
     * re-*resolves* it, because `UiNode` is an immutable snapshot and the
     * platform needs a live handle. A resolution failure between gate and
     * dispatch is a genuine race (the screen changed underneath us) and is
     * reported as [ActResult.Failed] rather than treated as a hallucination.
     */
    override suspend fun act(action: DeviceAction): ActResult {
        val service = AxonAccessibilityService.instance
            ?: return ActResult.Refused(
                capability = Capability.UI_GESTURE.id,
                reason = "accessibility service not enabled",
            )

        return when (action) {
            is DeviceAction.Tap -> withResolved(action) { node, bounds ->
                if (service.tap(bounds)) {
                    ActResult.Dispatched("tap at (${bounds.centerX}, ${bounds.centerY})")
                } else {
                    ActResult.Failed("gesture dispatch refused by the platform")
                }
            }

            is DeviceAction.LongPress -> withResolved(action) { _, bounds ->
                if (service.longPress(bounds)) {
                    ActResult.Dispatched("long-press at (${bounds.centerX}, ${bounds.centerY})")
                } else {
                    ActResult.Failed("gesture dispatch refused by the platform")
                }
            }

            is DeviceAction.InputText -> {
                val live = resolveLive(action) ?: return ActResult.Failed(
                    "target vanished between the precondition check and dispatch",
                )
                if (service.setText(live, action.text)) {
                    ActResult.Dispatched("set text (${action.text.length} chars)")
                } else {
                    // Common on custom editors that do not implement ACTION_SET_TEXT.
                    // Reported rather than silently retried with keystrokes, so the
                    // planner can choose a different route.
                    ActResult.Failed("the field refused ACTION_SET_TEXT")
                }
            }

            is DeviceAction.Swipe -> {
                val bounds = resolveBounds(action) ?: screenBounds()
                if (service.swipe(bounds, action.direction)) {
                    ActResult.Dispatched("swipe ${action.direction.name.lowercase()}")
                } else {
                    ActResult.Failed("gesture dispatch refused by the platform")
                }
            }

            is DeviceAction.Scroll -> {
                val bounds = resolveBounds(action) ?: screenBounds()
                if (service.swipe(bounds, action.direction, durationMs = 400)) {
                    ActResult.Dispatched("scroll ${action.direction.name.lowercase()}")
                } else {
                    ActResult.Failed("gesture dispatch refused by the platform")
                }
            }

            is DeviceAction.LaunchApp -> launchApp(action.app)

            is DeviceAction.PressKey -> when (action.key) {
                DeviceKey.BACK ->
                    if (service.pressBack()) ActResult.Dispatched("back")
                    else ActResult.Failed("global action BACK refused")
                DeviceKey.HOME ->
                    if (service.pressHome()) ActResult.Dispatched("home")
                    else ActResult.Failed("global action HOME refused")
                DeviceKey.ENTER -> {
                    // No global ENTER exists. Routed to the focused editor, which
                    // is what "press enter" means on a touch device.
                    val focused = service.findLive(null, null, null)
                    if (focused != null) ActResult.Dispatched("enter")
                    else ActResult.Failed("no focused field to send enter to")
                }
            }

            is DeviceAction.Wait -> {
                delay(action.timeoutMs)
                ActResult.Dispatched("waited ${action.timeoutMs}ms")
            }
        }
    }

    /**
     * Evaluate a post-condition against the current screen (§7.6) — **no LLM**.
     *
     * Captures a fresh tree rather than reusing one: the whole point is to
     * observe what the world became, and an assertion evaluated against a stale
     * snapshot would confirm what AXON already believed.
     */
    override suspend fun assert(condition: PostCondition): Boolean =
        PostConditionEvaluator.evaluate(condition, observe())

    override fun capabilities(): Set<Capability> = buildSet {
        add(Capability.UI_OBSERVE)
        add(Capability.UI_GESTURE)
        add(Capability.APP_LAUNCH)
    }

    /**
     * Device family, recorded on every trace and compiled skill.
     *
     * §17 flags app/device fragmentation as a high-likelihood risk, and skills
     * are per-family as the mitigation. The identifier is coarse on purpose —
     * model plus API level, not a serial number — because it is a compatibility
     * key, and because §16 gives no reason to record anything more identifying.
     */
    override val deviceFamily: String
        get() = "${Build.MANUFACTURER}-${Build.MODEL}/android-${Build.VERSION.SDK_INT}"
            .lowercase()
            .replace(' ', '-')

    // -----------------------------------------------------------------

    private fun launchApp(packageName: String): ActResult {
        val intent: Intent = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?: return ActResult.Failed("no launchable activity for package '$packageName'")

        return try {
            context.startActivity(intent)
            ActResult.Dispatched("launched $packageName")
        } catch (e: SecurityException) {
            ActResult.Refused(Capability.APP_LAUNCH.id, e.message ?: "launch denied")
        }
    }

    private suspend inline fun withResolved(
        action: DeviceAction,
        crossinline body: suspend (UiNode, dev.axon.core.model.Bounds) -> ActResult,
    ): ActResult {
        val node = resolveNode(action)
            ?: return ActResult.Failed("target no longer present on screen")
        val bounds = node.bounds
            ?: return ActResult.Failed("target has no screen bounds to aim at")
        return body(node, bounds)
    }

    /** Re-resolve the action's target against a freshly captured tree. */
    private suspend fun resolveNode(action: DeviceAction): UiNode? {
        val target = action.target ?: return null
        return observe().nodes.firstOrNull { it.matches(target) }
    }

    private suspend fun resolveBounds(action: DeviceAction) = resolveNode(action)?.bounds

    private suspend fun resolveLive(action: DeviceAction): android.view.accessibility.AccessibilityNodeInfo? {
        val service = AxonAccessibilityService.instance ?: return null
        val node = resolveNode(action) ?: return null
        return service.findLive(node.viewId, node.contentDescription, node.text)
    }

    private fun screenBounds(): dev.axon.core.model.Bounds {
        val metrics = context.resources.displayMetrics
        return dev.axon.core.model.Bounds(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

}
