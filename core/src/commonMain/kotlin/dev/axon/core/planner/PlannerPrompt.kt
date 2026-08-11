package dev.axon.core.planner

import dev.axon.core.model.ActionMenu
import dev.axon.core.model.CompactState
import dev.axon.core.model.Goal

/**
 * Builds the planner prompt (spec §7.4).
 *
 * ## Why the prompt has to do this much work
 *
 * §7.4 is explicit: *"The grammar is not injected into the prompt — the prompt
 * must describe the available actions and current elements in natural language,
 * while the grammar enforces shape."*
 *
 * That division is easy to state and easy to get wrong. The grammar guarantees
 * the model emits *a* well-formed action; it has nothing to say about whether
 * that action is the right one. Every bit of judgement — which element, which
 * verb, what should be true afterwards — has to come from the text below. So the
 * prompt is not a formality wrapped around the real mechanism; it is the half of
 * C3 that the sampler cannot do.
 *
 * ## Structured as prefix + suffix, deliberately
 *
 * [systemPrompt] is identical for every step of every task. [userPrompt] changes
 * each step. On the target device (2× Cortex-A75) prefill dominates wall-clock,
 * and re-processing an unchanged system prompt on every step is pure waste —
 * decision D8 identifies KV-cache prefix reuse as the main mitigation. Splitting
 * the prompt this way now means that optimisation is a change to the engine, not
 * a rewrite of the planner.
 */
public object PlannerPrompt {

    /**
     * The stable half: role, rules, output format, and how to choose a
     * post-condition.
     *
     * Written for a ~1B model, which shapes it more than politeness would
     * suggest. Small models follow *concrete, positive* instructions far better
     * than abstract or negative ones, so this says "use the label in brackets"
     * rather than "avoid inventing selectors", and gives worked examples rather
     * than describing a schema abstractly.
     *
     * ## Why the output format is described here, despite §7.4
     *
     * An earlier version omitted it entirely, reasoning that shape is the
     * grammar's job (§7.4: "the grammar is not injected into the prompt"). That
     * was a misreading, and experiment E4 exposed it: the prompt ended with
     * "answer with the action only", and the unconstrained arm dutifully replied
     * with the single word `tap`. The constrained arm looked perfect only
     * because the grammar was supplying structure the prompt had failed to ask
     * for — so the ablation measured *grammar versus a forgetful prompt*, which
     * is not a result anyone should publish.
     *
     * Describing the format in prose is **not** injecting the grammar. §7.4
     * forbids pasting the GBNF, because a grammar is a decoding constraint and
     * belongs at the sampler; telling the model what an action looks like is
     * ordinary prompting, and both ablation arms receive it identically. The
     * grammar's contribution is then measured honestly: what it adds *over a
     * well-specified prompt*, which is the only version of the C3 claim worth
     * defending.
     *
     * It costs roughly 80 prompt tokens, and prefill is already ~63% of a
     * planning step (E2) — a real trade, and one the evaluation should report
     * rather than absorb silently.
     */
    public const val SYSTEM: String = """You operate an Android phone for the user, one action at a time.

You will be shown the current screen as a list of elements. Each line looks like:
  [3] button "Send"
The number in brackets is the element's index. The quoted text is its label.

Choose exactly ONE action to move closer to the goal. You do not finish the task
in one step — you take the single best next step, and you will be shown the new
screen afterwards.

RULES
1. Only act on elements that appear in the list. If what you need is not there,
   scroll to look for it, or press back to leave this screen.
2. Address an element by its exact label, using target.by = "content_desc" or
   "text". Prefer "content_desc" when a label is shown.
3. Every action must include "expect": what will be TRUE ON SCREEN after the
   action works. This is checked automatically, so it must be observable.
      good: {"type":"node_present","value":"Search"}
      good: {"type":"app_foreground","value":"com.android.settings"}
      bad:  {"type":"node_present","value":"the message was sent successfully"}
4. To type, the element must be marked (editable).
5. To open an app, use launch_app with its package name, e.g. "com.whatsapp".
6. If the screen is still loading, use wait.

OUTPUT FORMAT
Reply with one JSON object and nothing else. No explanation, no code fence.

  {"action":"tap","target":{"by":"content_desc","value":"Send"},
   "expect":{"type":"node_present","value":"Message sent"}}

  {"action":"input_text","target":{"by":"content_desc","value":"Message"},
   "text":"on my way",
   "expect":{"type":"node_present","value":"on my way"}}

  {"action":"launch_app","app":"com.whatsapp",
   "expect":{"type":"app_foreground","value":"com.whatsapp"}}

action  : tap | long_press | input_text | swipe | scroll | launch_app | press_key | wait
by      : content_desc | text | id | class | coord
expect  : node_present | node_absent | text_matches | screen_title | app_foreground
extra   : "text" only with input_text; "direction" (up|down|left|right) only with
          swipe/scroll; "app" only with launch_app; "key" (home|back|enter) only
          with press_key."""

    /**
     * The volatile half: goal, screen, available actions, and any failure.
     *
     * @param failure present when re-planning after a verifier mismatch (§7.6).
     *   Placed *last*, immediately before the model generates, because
     *   position matters for a small model: a failure buried above the screen
     *   listing gets ignored, and the retry proposes the same action that just
     *   failed — which is the difference between self-healing and looping.
     */
    public fun user(
        state: CompactState,
        goal: Goal,
        menu: ActionMenu = ActionMenu.forScreen(state),
        failure: FailureContext? = null,
    ): String = buildString {
        append("GOAL: ").append(goal.utterance).append('\n')
        if (goal.params.isNotEmpty()) {
            append("DETAILS:\n")
            for ((k, v) in goal.params) {
                append("  ").append(k).append(": ").append(v).append('\n')
            }
        }
        append('\n')

        append("CURRENT SCREEN\n")
        append(state.render())
        append('\n')

        append(menu.render())

        if (failure != null) {
            append('\n')
            append(failure.render())
        }
    }

    /**
     * Convenience for engines that take one flat string.
     *
     * Loses the prefix/suffix boundary and therefore the ability to cache the
     * prefill, so the two-part form is preferred wherever the engine supports a
     * chat template.
     */
    public fun flat(
        state: CompactState,
        goal: Goal,
        menu: ActionMenu = ActionMenu.forScreen(state),
        failure: FailureContext? = null,
    ): String = SYSTEM + "\n\n" + user(state, goal, menu, failure)
}
