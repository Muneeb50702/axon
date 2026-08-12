package dev.axon.android.driver

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import dev.axon.core.model.Bounds
import dev.axon.core.model.DropReason
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree
import dev.axon.core.perception.SensitivePolicy

/**
 * Turns an `AccessibilityNodeInfo` tree into a [UiTree] (spec §7.1, §10.2).
 *
 * This is the perception half of the Android driver and the place where the §16
 * credential guardrail is actually enforced. Everything here is deliberately
 * mechanical: walk, classify, filter, flatten. No judgement, no model.
 *
 * ## The §16 refusal happens *before* the read
 *
 * §16, guardrail 3, requires AXON to skip password, OTP and authenticator nodes
 * rather than trusting the app's own `isAccessibilityDataSensitive` flag, which
 * apps apply inconsistently.
 *
 * The order of operations in [capture] matters more than the policy itself: a
 * node's metadata is classified **first**, and only if it passes is its text
 * read. A password therefore never enters AXON's process memory — not into a
 * variable, not into a trace, not into a log. Filtering after reading would
 * satisfy a code review and miss the point, because §16 is about not capturing,
 * not about not displaying.
 *
 * ## Pruning is lossy, and the loss is recorded
 *
 * A real chat screen produces hundreds of nodes. [CompactState] caps what reaches
 * the planner, but this layer already drops decoration, invisible nodes and empty
 * containers. Both kinds of loss are counted separately in [UiTree.dropped]: a
 * [DropReason.SENSITIVE] count is evidence a safety guardrail fired, while
 * [DropReason.PRUNED] is a token-budget decision. Conflating them would make a
 * safety event indistinguishable from a tuning artefact.
 */
internal object TreeCapture {

    /**
     * Maximum tree depth to walk.
     *
     * Deep hierarchies are common in Compose and React Native, where a visible
     * button can sit 30 layers down. The cap exists to bound worst-case capture
     * time on a slow device, not to express a view about UI design — and 60 is
     * generous enough that hitting it indicates something pathological.
     */
    private const val MAX_DEPTH = 60

    /** Hard ceiling on nodes visited, as runaway protection on malformed trees. */
    private const val MAX_NODES = 1_500

    fun capture(
        root: AccessibilityNodeInfo?,
        foregroundPackage: String,
        capturedAtMs: Long,
    ): UiTree {
        if (root == null) return UiTree.empty(foregroundPackage, capturedAtMs)

        val nodes = mutableListOf<UiNode>()
        val dropped = mutableMapOf<DropReason, Int>()
        var visited = 0
        var screenTitle: String? = null

        fun drop(reason: DropReason) {
            dropped[reason] = (dropped[reason] ?: 0) + 1
        }

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH || visited >= MAX_NODES) return
            visited++

            // ---- §16: classify from metadata, before reading any content ----
            val className = node.className?.toString()
            val viewId = node.viewIdResourceName
            val contentDesc = node.contentDescription?.toString()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()
            } else {
                null
            }
            val appFlagged = if (Build.VERSION.SDK_INT >= 34) {
                node.isAccessibilityDataSensitive
            } else {
                false
            }

            val sensitive = SensitivePolicy.isSensitive(
                className = className,
                viewId = viewId,
                contentDescription = contentDesc,
                hintText = hint,
                isPassword = node.isPassword,
                appFlaggedSensitive = appFlagged,
            )

            if (sensitive) {
                // Refused. `node.text` is never touched, and the subtree is not
                // descended into — a credential field's children (an inline
                // error, a strength meter echoing the value) are refused with it.
                drop(DropReason.SENSITIVE)
                return
            }

            // ---- safe to read content from here on ----
            val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            val visible = node.isVisibleToUser
            val bounds = Rect().also { node.getBoundsInScreen(it) }.toBounds()

            val interactive = node.isClickable || node.isEditable || node.isScrollable ||
                node.isLongClickable || node.isCheckable

            val worthKeeping = visible &&
                !bounds.isEmpty &&
                (interactive || text != null || contentDesc != null)

            if (!worthKeeping) {
                drop(DropReason.PRUNED)
            } else {
                // The first substantial text at shallow depth is usually the
                // screen title — an app bar heading. A heuristic, and labelled as
                // one: post-conditions of type SCREEN_TITLE depend on it, so when
                // it is wrong the verifier will say so rather than silently pass.
                if (screenTitle == null && depth <= 4 && text != null && !interactive) {
                    screenTitle = text
                }

                nodes += UiNode(
                    index = nodes.size,
                    role = roleOf(className, node),
                    text = text,
                    contentDescription = contentDesc?.takeIf { it.isNotBlank() },
                    viewId = viewId,
                    bounds = bounds,
                    enabled = node.isEnabled,
                    clickable = node.isClickable || node.isLongClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    checked = if (node.isCheckable) node.isChecked else null,
                )
            }

            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
            }
        }

        walk(root, 0)

        return UiTree(
            foregroundPackage = foregroundPackage,
            screenTitle = screenTitle,
            nodes = nodes,
            capturedAtMs = capturedAtMs,
            dropped = dropped,
        )
    }

    /**
     * Coarse semantic role from the platform class name.
     *
     * The planner sees this word, so it is chosen for what a model will
     * understand — `button`, `edit_text`, `list_item` — rather than for fidelity
     * to the Android class hierarchy. `android.widget.AppCompatImageButton`
     * carries no information a 1B model can use; `button` does.
     */
    private fun roleOf(className: String?, node: AccessibilityNodeInfo): String {
        val name = className?.substringAfterLast('.') ?: ""
        return when {
            node.isEditable || name.contains("EditText") -> "edit_text"
            name.contains("Button") -> "button"
            name.contains("Switch") || name.contains("Toggle") -> "switch"
            name.contains("CheckBox") -> "checkbox"
            name.contains("RadioButton") -> "radio"
            name.contains("ImageView") -> "image"
            name.contains("SeekBar") -> "slider"
            name.contains("Tab") -> "tab"
            node.isScrollable -> "list"
            // A clickable container is a row in practice — a chat entry, a
            // settings item — and calling it that reads better to the planner
            // than "ViewGroup".
            node.isClickable && node.childCount > 0 -> "list_item"
            node.isClickable -> "button"
            name.contains("TextView") -> "text"
            else -> "view"
        }
    }

    private fun Rect.toBounds() = Bounds(left, top, right, bottom)
}
