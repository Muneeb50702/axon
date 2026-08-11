package dev.axon.core.model

import kotlinx.serialization.Serializable

/**
 * A snapshot of the screen as a structured tree (spec §9.1 `observe()`).
 *
 * This is AXON's perception substrate and the reason the verifier can be
 * deterministic. A screenshot-based agent (the AutoGLM/Roubao paradigm, §3.1)
 * has only pixels, so "did that work?" is itself a model judgement. A structured
 * tree makes it a query.
 *
 * The tree is immutable and hashable: [contentHash] lets the executor detect
 * "nothing changed at all" — the signature of a tap that landed on nothing — and
 * lets the skill compiler recognise that two runs traversed the same screens.
 */
@Serializable
data class UiTree(
    /** Package name of the foreground app, e.g. `com.whatsapp`. */
    val foregroundPackage: String,

    /** Screen title / header if one could be identified. */
    val screenTitle: String? = null,

    /** Flattened nodes in stable pre-order. See [UiNode.index]. */
    val nodes: List<UiNode>,

    /** Milliseconds since epoch at capture. Used for trace timing (§10.5). */
    val capturedAtMs: Long,

    /**
     * How many nodes the driver saw but did not report, by reason.
     *
     * Kept because the two reasons matter differently and a thesis needs to
     * tell them apart: [DropReason.SENSITIVE] is a §16 safety refusal and its
     * count is evidence the guardrail fired; [DropReason.PRUNED] is a token-budget
     * decision (§10.2) and a high count means perception may be hiding the very
     * element the planner needs. Conflating them would make a safety event look
     * like a tuning artefact.
     */
    val dropped: Map<DropReason, Int> = emptyMap(),
) {
    /**
     * Order-sensitive fingerprint of the *semantic* content of the screen.
     *
     * Deliberately excludes [capturedAtMs] and node bounds: capturing the same
     * unchanged screen twice must produce the same hash, and a 1-pixel scroll
     * jitter or an animation settling must not read as "the screen changed".
     * Without that stability the verifier would report false progress and the
     * skill compiler would treat identical screens as distinct states.
     */
    val contentHash: Int by lazy {
        var h = foregroundPackage.hashCode()
        h = 31 * h + (screenTitle?.hashCode() ?: 0)
        for (n in nodes) {
            h = 31 * h + n.role.hashCode()
            h = 31 * h + (n.text?.hashCode() ?: 0)
            h = 31 * h + (n.contentDescription?.hashCode() ?: 0)
            h = 31 * h + (n.viewId?.hashCode() ?: 0)
            h = 31 * h + n.enabled.hashCode()
        }
        h
    }

    /** Nodes the executor is allowed to aim an action at. */
    fun interactable(): List<UiNode> = nodes.filter { it.isInteractable }

    /** Node by its stable [UiNode.index], or `null` if the index is stale. */
    fun byIndex(index: Int): UiNode? = nodes.firstOrNull { it.index == index }

    companion object {
        /** A screen with nothing readable — a canvas, game or DRM surface (§17). */
        fun empty(foregroundPackage: String, capturedAtMs: Long): UiTree =
            UiTree(foregroundPackage, null, emptyList(), capturedAtMs)
    }
}

/** Why a node the driver observed was left out of the reported tree. */
@Serializable
enum class DropReason {
    /**
     * Refused under §16: a password, OTP or authenticator field. AXON never
     * captures these, regardless of whether the app set
     * `isAccessibilityDataSensitive` (Android 14+, inconsistently applied — so
     * AXON classifies these itself rather than trusting the app's flag).
     */
    SENSITIVE,

    /** Dropped for token budget: decoration, invisible, or over the node cap (§10.2). */
    PRUNED,
}

/**
 * One element in the tree.
 *
 * Field set is chosen for what a *planner* and a *verifier* need, not for
 * fidelity to `AccessibilityNodeInfo`. Everything here is either a way to
 * address the node, a way to describe it to the model, or a way to assert on it.
 */
@Serializable
data class UiNode(
    /**
     * Stable position in pre-order traversal of this snapshot (§10.2).
     *
     * Stable *within* a snapshot only. The planner references elements by index
     * to keep prompts short, and the executor re-resolves the index against the
     * tree it was generated from — never against a newer one. A stale index is
     * a precondition failure (§7.5), not a silently mis-aimed tap.
     */
    val index: Int,

    /** Coarse semantic role: `button`, `edit_text`, `text`, `image`, `list`, … */
    val role: String,

    /** Visible text, if any. Never populated for [DropReason.SENSITIVE] nodes. */
    val text: String? = null,

    /** Accessibility content description — often the most stable selector. */
    val contentDescription: String? = null,

    /** Android view id resource name, e.g. `com.whatsapp:id/send`. */
    val viewId: String? = null,

    /** Screen bounds in pixels. */
    val bounds: Bounds? = null,

    val enabled: Boolean = true,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,

    /** True when the node is checked/selected — needed to verify toggle tasks. */
    val checked: Boolean? = null,
) {
    /** Worth showing the planner and worth aiming an action at. */
    val isInteractable: Boolean
        get() = enabled && (clickable || editable || scrollable)

    /** The best human-readable label, preferring the more stable source. */
    val label: String?
        get() = contentDescription?.takeIf { it.isNotBlank() }
            ?: text?.takeIf { it.isNotBlank() }

    /** Does this node satisfy [target]? Pure, so it is unit-testable without a device. */
    fun matches(target: Target): Boolean = when (target.by) {
        TargetBy.TEXT -> text.equalsFold(target.value)
        TargetBy.CONTENT_DESC -> contentDescription.equalsFold(target.value)
        TargetBy.ID -> viewId == target.value
        TargetBy.CLASS -> role.equalsFold(target.value)
        TargetBy.COORD -> bounds?.contains(target.value) == true
    }
}

private fun String?.equalsFold(other: String): Boolean =
    this != null && this.equals(other, ignoreCase = true)

/** Screen rectangle in pixels, left/top inclusive, right/bottom exclusive. */
@Serializable
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** True if this rectangle has no area — an off-screen or collapsed node. */
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    /** Does the point encoded as `"x,y"` fall inside? Malformed input is `false`. */
    fun contains(coord: String): Boolean {
        val parts = coord.split(',')
        if (parts.size != 2) return false
        val x = parts[0].trim().toIntOrNull() ?: return false
        val y = parts[1].trim().toIntOrNull() ?: return false
        return x in left until right && y in top until bottom
    }
}
