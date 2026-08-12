package dev.axon.android.driver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.axon.core.model.Bounds
import dev.axon.core.model.Direction
import dev.axon.core.model.UiTree
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * AXON's `AccessibilityService` — the substrate for perception and action
 * (spec §7.1, §9.1).
 *
 * ## The uncomfortable part, stated up front
 *
 * §16 opens by noting this is the same API behind a documented ~$145M
 * stalkerware industry — *"a single API granting god-mode over an Android
 * device."* Everything below is written on the assumption that a reviewer knows
 * that and will look for the difference. The difference is not intent, it is
 * what the code is unable to do:
 *
 * | guardrail | how it is enforced |
 * |---|---|
 * | screen contents cannot be exfiltrated | the app holds **no `INTERNET` permission** — the OS enforces it, not us |
 * | credential fields are never read | [TreeCapture] classifies before reading; a password never enters process memory |
 * | operation is visible | capture happens only while a task is running, under a foreground service with a persistent notification |
 * | no covert restart | `START_NOT_STICKY` on the gateway; a killed agent stays dead until the user asks again |
 *
 * ## Why it holds a static instance
 *
 * Android constructs accessibility services itself, so there is no way to inject
 * one into [AccessibilityDriver]. The service therefore publishes itself on
 * connect and clears on disconnect. [instance] being null is the normal state —
 * the user has not enabled the service yet — and every caller must treat it as
 * an expected condition, not an error.
 */
class AxonAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AXON accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.i(TAG, "AXON accessibility service disconnected")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Deliberately empty.
     *
     * AXON is **pull-based**: it reads the screen when it is executing a step,
     * and at no other time. A conventional accessibility service reacts to the
     * event stream, which would mean observing every screen the user visits
     * whether or not a task is running. That is precisely the always-watching
     * behaviour §16 forbids, so the event callback does nothing and the
     * `AccessibilityServiceInfo` requests no event types.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // -----------------------------------------------------------------
    // Perception
    // -----------------------------------------------------------------

    /** Capture the current screen, with §16 refusal applied at read time. */
    fun captureTree(nowMs: Long): UiTree {
        val root: AccessibilityNodeInfo? = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: "unknown"
        return TreeCapture.capture(root, pkg, nowMs)
    }

    // -----------------------------------------------------------------
    // Action
    // -----------------------------------------------------------------

    /**
     * Tap the centre of [bounds].
     *
     * Dispatched as a gesture rather than `performAction(ACTION_CLICK)`, and the
     * choice is not arbitrary. `ACTION_CLICK` invokes the node's *declared*
     * handler, which many custom views and Compose surfaces never register — the
     * call returns `true` and nothing happens, which is the worst possible
     * outcome for a system whose verifier trusts what it observes rather than
     * what it is told. A synthetic touch goes through the same input path as a
     * finger, so if the UI would respond to a user it responds to AXON.
     */
    suspend fun tap(bounds: Bounds, durationMs: Long = 60): Boolean =
        dispatch(
            Path().apply { moveTo(bounds.centerX.toFloat(), bounds.centerY.toFloat()) },
            startMs = 0,
            durationMs = durationMs,
        )

    suspend fun longPress(bounds: Bounds): Boolean =
        tap(bounds, durationMs = 600)

    /**
     * Swipe across [bounds], or across the screen when no element is given.
     *
     * The gesture runs *against* the requested direction of travel, as a finger
     * does: swiping "down" to scroll content down means dragging upward. Getting
     * this backwards produces an agent that scrolls away from what it is looking
     * for, and the verifier would report only that the expected node never
     * appeared.
     */
    suspend fun swipe(bounds: Bounds, direction: Direction, durationMs: Long = 300): Boolean {
        val cx = bounds.centerX.toFloat()
        val cy = bounds.centerY.toFloat()
        val dx = bounds.width * 0.4f
        val dy = bounds.height * 0.4f

        val (endX, endY) = when (direction) {
            Direction.UP -> cx to (cy + dy)
            Direction.DOWN -> cx to (cy - dy)
            Direction.LEFT -> (cx + dx) to cy
            Direction.RIGHT -> (cx - dx) to cy
        }

        val path = Path().apply {
            moveTo(cx, cy)
            lineTo(endX, endY)
        }
        return dispatch(path, startMs = 0, durationMs = durationMs)
    }

    /**
     * Set the text of an editable node.
     *
     * `ACTION_SET_TEXT` replaces the field's contents outright rather than
     * appending, which is what the planner means by `input_text` — a step that
     * silently appended to an existing draft would make replay
     * non-idempotent, and a compiled skill re-run twice would produce
     * "on my wayon my way".
     */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /**
     * Find a live node matching a captured [dev.axon.core.model.UiNode].
     *
     * Re-resolution is needed because `UiNode` is an immutable snapshot while
     * `performAction` requires a live handle. Matching goes by view id first,
     * then content description, then text — the same stability ordering the
     * skill compiler uses when freezing selectors, so replay and live execution
     * agree about which identifier is most trustworthy.
     */
    fun findLive(viewId: String?, contentDesc: String?, text: String?): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null

        viewId?.let { id ->
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { return it }
        }
        contentDesc?.let { desc ->
            findByPredicate(root) { it.contentDescription?.toString() == desc }?.let { return it }
        }
        text?.let { t ->
            root.findAccessibilityNodeInfosByText(t)
                .firstOrNull { it.text?.toString().equals(t, ignoreCase = true) }
                ?.let { return it }
        }
        return null
    }

    private fun findByPredicate(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 60) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            findByPredicate(node.getChild(i), depth + 1, predicate)?.let { return it }
        }
        return null
    }

    private fun findByPredicate(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? = findByPredicate(node, 0, predicate)

    /**
     * Dispatch a gesture and suspend until the platform reports its outcome.
     *
     * `dispatchGesture` is callback-based and returns immediately; awaiting the
     * callback is what lets the executor know the gesture was actually delivered
     * before the verifier captures the resulting screen. Without it, verification
     * would race the gesture and report failures that are really timing.
     */
    private suspend fun dispatch(path: Path, startMs: Long, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val stroke = GestureDescription.StrokeDescription(path, startMs, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val ok = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(description: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )

            // dispatchGesture returns false when the service cannot dispatch at
            // all — typically because it lacks canPerformGestures. The callback
            // will never fire, so the coroutine must be resumed here or the
            // executor hangs forever.
            if (!ok && cont.isActive) cont.resume(false)
        }

    companion object {
        private const val TAG = "AxonA11y"

        /**
         * The connected service, or `null` when the user has not enabled it.
         *
         * Null is the normal starting state, not an error — AXON cannot enable
         * its own accessibility service, and by §16 it should not want to. The
         * UI's job is to explain and link to the settings screen.
         */
        @Volatile
        var instance: AxonAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }
}
