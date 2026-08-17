package dev.axon.bench

import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree

/**
 * How app UIs change between versions — the model **E26b** is measured against.
 *
 * ## Why this file is the experiment
 *
 * A study of "does promoting a selector to a sturdier handle reduce replay
 * breakage" is decided entirely by which changes it calls realistic. Mutate only
 * visible text and promotion to a view id wins every case by construction; the
 * result would be an artefact of the fixture and would say nothing about real
 * apps. So the drift classes are enumerated here, each tied to a **cause** rather
 * than to a convenient mutation, and results are reported per class.
 *
 * The classes below are deliberately chosen so that **no single selector kind
 * survives all of them**. If one did, this study would have been built to reach a
 * conclusion rather than to test one.
 *
 * ## What this is not
 *
 * It is not a measured distribution of real app updates. Nobody here has mined a
 * corpus of Android releases to find how often copy changes relative to resource
 * ids. That means per-class survival is a fact about the mechanism, and **any
 * aggregate over classes is only as good as the weights assumed** — so the study
 * reports the classes and declines to average them into a headline. Stated in
 * `docs/EXPERIMENTS.md` as the study's main limitation rather than buried here.
 */
enum class DriftClass(
    /** What changes on screen. */
    val change: String,
    /** Why a real app would do this. */
    val cause: String,
) {
    /**
     * Copy is rewritten and the accessibility label follows it.
     *
     * The common case when `contentDescription` was auto-derived from the label
     * or written to mirror it, which is typical of app-internal buttons.
     */
    COPY_AND_DESC("visible text and content-description both change", "localisation, rewording"),

    /** Copy is rewritten but a hand-written accessibility label is kept. */
    COPY_ONLY("visible text changes, content-description kept", "reworded label, a11y label maintained separately"),

    /** Resource ids renamed by a refactor; labels untouched. */
    ID_RENAMED("view id changes", "resource rename, package refactor"),

    /**
     * View ids disappear entirely.
     *
     * The Jetpack Compose migration case, and the reason this class must be
     * here: Compose nodes frequently carry no resource id at all, so a skill
     * that promoted to `id` loses its handle across a framework migration that
     * changes nothing a user would notice.
     */
    ID_REMOVED("view id disappears", "XML → Compose migration"),

    /** An accessibility label is removed. */
    DESC_REMOVED("content-description removed", "a11y regression in a redesign"),

    /** An accessibility label is added where there was none. */
    DESC_ADDED("content-description added", "a11y improvement"),

    /** The element moves; every handle is intact. */
    REORDERED("element order changes", "layout restructure, A/B variant"),

    /** The label becomes an icon, keeping only its accessibility description. */
    ICONIFIED("visible text removed, content-description kept", "icon-only redesign"),
    ;

    /** Apply this drift to [tree], mutating the element at [targetIndex]. */
    fun applyTo(tree: UiTree, targetIndex: Int = 0): UiTree {
        if (this == REORDERED) {
            // Nothing about the element changes — only where it sits. Handles
            // are attribute-based, so every selector should survive; the class
            // exists precisely to confirm that and to catch a future selector
            // kind that secretly depends on position.
            return tree.copy(nodes = tree.nodes.reversed())
        }

        val nodes = tree.nodes.map { node ->
            if (node.index != targetIndex) return@map node
            when (this) {
                COPY_AND_DESC -> node.copy(
                    text = node.text?.let { "$it (updated)" },
                    contentDescription = node.contentDescription?.let { "$it (updated)" },
                )
                COPY_ONLY -> node.copy(text = node.text?.let { "$it (updated)" })
                ID_RENAMED -> node.copy(viewId = node.viewId?.replace(":id/", ":id/new_"))
                ID_REMOVED -> node.copy(viewId = null)
                DESC_REMOVED -> node.copy(contentDescription = null)
                DESC_ADDED -> node.copy(contentDescription = node.contentDescription ?: "Added label")
                ICONIFIED -> node.copy(text = null)
                REORDERED -> node
            }
        }
        return tree.copy(nodes = nodes)
    }

    companion object {
        val ALL: List<DriftClass> = entries.toList()
    }
}
