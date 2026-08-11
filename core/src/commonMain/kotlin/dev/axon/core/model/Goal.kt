package dev.axon.core.model

import kotlinx.serialization.Serializable

/**
 * What the user asked for (spec §9.2).
 *
 * [utterance] is kept verbatim for the whole run — it is what the planner reads,
 * what the trace records, and what the skill store matches new requests against.
 * [params] holds slots already extracted by a router or supplied by a caller
 * invoking a skill directly; on the cold PLAN path it is usually empty and the
 * planner works from the utterance alone.
 */
@Serializable
data class Goal(
    val utterance: String,

    /** Typed slots, e.g. `{"contact": "Ammi", "message": "on my way"}` (§10.5). */
    val params: Map<String, String> = emptyMap(),

    /**
     * Hard ceiling on actions for this task (§7.5 action budget).
     *
     * Non-negotiable runaway protection: without it, a small model that loops
     * between two screens will do so until the battery dies. Default 15 per §7.5.
     */
    val stepBudget: Int = DEFAULT_STEP_BUDGET,

    /**
     * Heal attempts allowed per failed step before escalating to the user
     * (§7.6). Default 2.
     */
    val healBudget: Int = DEFAULT_HEAL_BUDGET,
) {
    init {
        require(utterance.isNotBlank()) { "goal utterance must not be blank" }
        require(stepBudget > 0) { "stepBudget must be positive, was $stepBudget" }
        require(healBudget >= 0) { "healBudget must not be negative, was $healBudget" }
    }

    companion object {
        const val DEFAULT_STEP_BUDGET: Int = 15
        const val DEFAULT_HEAL_BUDGET: Int = 2
    }
}

/**
 * The actions available on the current screen, handed to the planner (§9.2).
 *
 * Note the division of labour that makes C3 work. The *grammar* (§10.6)
 * guarantees the emitted action is structurally well-formed — that is a decoding
 * property and needs no prompt. The *menu* tells the model, in natural language,
 * which of those well-formed actions make sense here. §7.4 is explicit that the
 * grammar is not injected into the prompt; the menu is what the prompt carries
 * instead.
 *
 * The menu is advisory. An action that ignores it is still structurally valid and
 * still gets caught — by the executor's precondition gate (§7.5), which checks
 * against the live tree rather than against the prompt.
 */
@Serializable
data class ActionMenu(
    val available: List<String>,
    val notes: List<String> = emptyList(),
) {
    fun render(): String = buildString {
        append("available actions: ").append(available.joinToString(", ")).append('\n')
        for (n in notes) append("- ").append(n).append('\n')
    }

    companion object {
        /**
         * The menu for a normal screen: every action type is offered, with the
         * addressing rules stated. The planner is told to prefer `content_desc`
         * because it is the selector most likely to survive an app update — the
         * same preference the skill compiler applies when freezing a trace (§7.7).
         */
        fun forScreen(state: CompactState): ActionMenu {
            val notes = buildList {
                add("select elements by the label shown in brackets, using target.by = \"content_desc\" or \"text\"")
                add("prefer content_desc over text; never use coord unless nothing else identifies the element")
                add("every action must state an \"expect\" — what will be true on screen afterwards")
                if (state.elements.none { it.editable }) {
                    add("there is no text field on this screen, so input_text will be rejected")
                }
                if (state.elements.none { it.scrollable }) {
                    add("nothing on this screen scrolls")
                }
                if (state.truncated) {
                    add("the element list is truncated; scroll to reveal more elements")
                }
            }
            return ActionMenu(DeviceAction.ACTION_TYPES, notes)
        }
    }
}
