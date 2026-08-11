package dev.axon.core.model

import kotlinx.serialization.Serializable

/**
 * The screen as the planner sees it (spec §10.2).
 *
 * A raw accessibility tree for a chat app runs to hundreds of nodes and many
 * thousands of tokens. On a 2B model with a small context that is fatal twice
 * over: it does not fit, and even where it fits, prefill dominates latency on a
 * phone. [CompactState] is the lossy projection that makes on-device planning
 * viable — pruned to interactable elements, capped in count, and rendered as
 * dense lines rather than JSON.
 *
 * The cap is a real trade-off and the thesis should own it: a screen with more
 * than [MAX_NODES] interactable elements is truncated, and if the needed element
 * falls outside the cap the planner cannot select it. [truncated] records when
 * that risk was taken so the benchmark can correlate failures with truncation
 * instead of blaming the model.
 */
@Serializable
data class CompactState(
    val foregroundPackage: String,
    val screenTitle: String?,
    val elements: List<CompactElement>,

    /** True when interactable nodes were dropped to satisfy [MAX_NODES]. */
    val truncated: Boolean = false,

    /** Count of nodes withheld under the §16 sensitive-field refusal. */
    val sensitiveWithheld: Int = 0,

    /** [UiTree.contentHash] of the snapshot this was projected from. */
    val sourceHash: Int = 0,
) {
    /**
     * Render for the prompt (§7.4).
     *
     * One line per element, `[index] role "label"` plus terse flags. Chosen over
     * JSON because JSON spends roughly a third of its tokens on punctuation and
     * repeated key names that carry no information the model needs — and the
     * planner's *output* is already JSON-constrained by the grammar, so the
     * input format is free to optimise purely for density.
     */
    fun render(): String = buildString {
        append("app: ").append(foregroundPackage).append('\n')
        screenTitle?.let { append("screen: ").append(it).append('\n') }
        if (sensitiveWithheld > 0) {
            // Told to the model on purpose: better it knows a field exists but is
            // off-limits than that it re-plans forever looking for a missing box.
            append("note: ").append(sensitiveWithheld)
                .append(" sensitive field(s) hidden by policy\n")
        }
        append("elements:\n")
        if (elements.isEmpty()) {
            append("  (none readable — screen may be canvas/DRM rendered)\n")
        } else {
            for (e in elements) append("  ").append(e.render()).append('\n')
        }
        if (truncated) append("  … list truncated at ").append(MAX_NODES).append('\n')
    }

    companion object {
        /**
         * Cap on interactable elements shown to the planner.
         *
         * 40 ≈ 600–900 tokens rendered, which leaves room for the goal, the
         * action menu and the failure context on a 4k window. Tune with evidence
         * from AXON-Bench (§14), not by feel.
         */
        const val MAX_NODES: Int = 40

        /**
         * Project a [UiTree] into the planner's view.
         *
         * Deterministic and pure — the same tree always yields the same state,
         * which is what lets perception be tested in CI with no phone attached.
         */
        fun from(tree: UiTree): CompactState {
            val interactable = tree.interactable()
            val kept = interactable.take(MAX_NODES)
            return CompactState(
                foregroundPackage = tree.foregroundPackage,
                screenTitle = tree.screenTitle,
                elements = kept.map(CompactElement::from),
                truncated = interactable.size > MAX_NODES,
                sensitiveWithheld = tree.dropped[DropReason.SENSITIVE] ?: 0,
                sourceHash = tree.contentHash,
            )
        }
    }
}

/** One line of the planner's screen view. */
@Serializable
data class CompactElement(
    val index: Int,
    val role: String,
    val label: String?,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
) {
    fun render(): String = buildString {
        append('[').append(index).append("] ").append(role)
        label?.let { append(" \"").append(it).append('"') }
        if (editable) append(" (editable)")
        if (scrollable) append(" (scrollable)")
        when (checked) {
            true -> append(" (on)")
            false -> append(" (off)")
            null -> Unit
        }
    }

    companion object {
        fun from(node: UiNode): CompactElement = CompactElement(
            index = node.index,
            role = node.role,
            label = node.label,
            editable = node.editable,
            scrollable = node.scrollable,
            checked = node.checked,
        )
    }
}
