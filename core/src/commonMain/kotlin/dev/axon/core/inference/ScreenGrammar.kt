package dev.axon.core.inference

import dev.axon.core.model.CompactState

/**
 * Specialises the action grammar to the elements actually on screen.
 *
 * ## The idea
 *
 * [ActionGrammar] constrains the *shape* of an action: the sampler cannot emit
 * an invented action type or an illegal field combination. It leaves
 * `target.value` as a free string, so this is still reachable:
 *
 * ```json
 * {"action":"tap","target":{"by":"content_desc","value":"Whatsapp"}}
 * ```
 *
 * — perfectly well-formed, and naming an element that is not on the screen. The
 * precondition gate (§7.5) catches it before the device is touched, so nothing
 * unsafe happens, but the step is still spent and the model has still been
 * allowed to say something false.
 *
 * A screen grammar closes that. The element labels visible right now are baked
 * into the grammar as a closed alternation, so `target.value` can only be one of
 * them. Hallucinated targets stop being *rejected* and become **unreachable** —
 * there is no token path to a label that is not on screen.
 *
 * ## Why this matters more than it first appears
 *
 * Experiment E18 measured the failure this addresses. Asked to open an app, a 1B
 * planner proposed `tap content_desc="Whatsapp"` three times against a screen
 * with no such element, ignoring a failure context that explicitly named the
 * failed attempt and asked for something different. Three planning steps, 182
 * seconds, nothing accomplished.
 *
 * Under a screen grammar that run cannot happen: `"Whatsapp"` is not in the
 * alternation, so the sampler never produces it, and the model is forced to
 * choose among things that exist — including `launch_app`, which is what the
 * task actually needed.
 *
 * This is the project's thesis applied one level deeper. §2.3: *"make the
 * model's freedom smaller."* The grammar already removes malformed actions; this
 * removes actions about a world that is not there.
 *
 * ## What it costs, stated honestly
 *
 * - **The grammar is rebuilt every step**, since the screen changes. Parsing a
 *   ~40-element grammar is microseconds against a ~50 s planning step, so the
 *   cost is not latency — but it does mean the grammar is no longer a static
 *   artefact, and the thesis appendix should show both.
 * - **Truncation risk grows**: a screen with many long labels produces a large
 *   alternation. [MAX_LABELS] caps it, and the cap is a real loss — an element
 *   beyond the cap becomes unnameable, so the planner must scroll to reach it.
 * - **`launch_app` stays unconstrained by screen**, because package names are
 *   deliberately not on screen. Its own `package-name` production still applies.
 * - **Escape hatch**: when the screen has no usable labels (a canvas or DRM
 *   surface, §17), specialisation is skipped and the base grammar is used, or
 *   the agent would have no legal move at all.
 */
public object ScreenGrammar {

    /**
     * Maximum labels admitted to the alternation.
     *
     * Matches `CompactState.MAX_NODES` so the grammar can express exactly what
     * the planner was shown — a label visible in the prompt but absent from the
     * grammar would be maddening, since the model would be told about an element
     * it is structurally forbidden to name.
     */
    public const val MAX_LABELS: Int = 40

    /**
     * Build a grammar whose selectors are restricted to [state]'s elements.
     *
     * Returns the base [ActionGrammar] unchanged when the screen offers nothing
     * to select — the §17 empty-tree case, where device-directed actions are the
     * only way out and must stay available.
     */
    public fun forScreen(state: CompactState): Gbnf {
        val labels = state.elements
            .mapNotNull { it.label }
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_LABEL_LENGTH }
            .distinct()
            .take(MAX_LABELS)

        if (labels.isEmpty()) return ActionGrammar.GBNF

        val alternation = labels.joinToString(" | ") { "\"\\\"${escape(it)}\\\"\"" }

        // Only the `string` production used by target.value is replaced. The
        // `expect.value` and `input_text.text` strings stay free: a
        // post-condition may legitimately mention text that is not on screen yet
        // — that is the entire point of a post-condition — and typed text is
        // arbitrary by definition.
        val specialised = ActionGrammar.SOURCE
            .replace(
                "target   ::= (\n  \"{\" \"\\\"by\\\"\" \":\" by\n  \",\" \"\\\"value\\\"\" \":\" string \"}\"\n)",
                "target   ::= (\n  \"{\" \"\\\"by\\\"\" \":\" by\n  \",\" \"\\\"value\\\"\" \":\" screen-label \"}\"\n)",
            )
            .plus("\n\n# --- screen-grounded selectors (rebuilt every step) ---\n")
            .plus("# Only elements present on the current screen are nameable, so a\n")
            .plus("# hallucinated target has no token path (see ScreenGrammar).\n")
            .plus("screen-label ::= $alternation\n")

        return Gbnf(specialised)
    }

    /**
     * A grammar whose only legal action is launching [packageName] (E21).
     *
     * Used when the goal is recognised as an app-launch intent and the name
     * resolves to an installed package. At that point the correct action is
     * fully determined without consulting the screen, so the model is not asked
     * to choose — the sampler has exactly one path.
     *
     * This is the strongest form the constraint takes anywhere in AXON: not
     * "well-formed", not "refers to something present", but *this specific
     * action*. It is only ever safe because the determination is made outside
     * the model, by a package lookup that cannot be wrong about whether an app
     * exists.
     */
    public fun forAppLaunch(packageName: String, expectValue: String = packageName): Gbnf = Gbnf(
        buildString {
            append("# AXON — single-action grammar (E21).\n")
            append("# The goal is an app-launch intent and the package resolved, so the\n")
            append("# correct action is determined without the model's judgement. The\n")
            append("# sampler has exactly one legal path.\n\n")
            append("root ::= \"{\\\"action\\\":\\\"launch_app\\\",\\\"app\\\":\\\"")
            append(packageName)
            append("\\\",\\\"expect\\\":{\\\"type\\\":\\\"app_foreground\\\",\\\"value\\\":\\\"")
            append(expectValue)
            append("\\\"}}\"\n")
        },
    )

    /** Did specialisation actually apply, or did it fall back to the base grammar? */
    public fun isSpecialised(grammar: Gbnf): Boolean =
        "screen-label ::=" in grammar.source

    /**
     * Escape a label for use as a GBNF string literal.
     *
     * Labels come from real apps and contain quotes, backslashes and newlines.
     * An unescaped one produces a grammar that fails to parse — and a grammar
     * that fails to parse is not an error, it is a *silently unconstrained run*
     * (llama.cpp declines to install the sampler and generation proceeds). That
     * failure mode cost hours in Phase 1, so escaping here is load-bearing.
     */
    private fun escape(label: String): String = buildString {
        for (c in label) {
            when (c) {
                '"' -> append("\\\\\\\"")
                '\\' -> append("\\\\\\\\")
                '\n', '\r', '\t' -> append(' ')
                else -> if (c.code >= 0x20 && c.code != 0x7F) append(c)
            }
        }
    }

    /**
     * Labels longer than this are excluded from the alternation.
     *
     * A 200-character content description — common on image buttons carrying a
     * whole caption — would bloat the grammar and is not a plausible selector
     * anyway. Excluded elements remain visible in the prompt and remain
     * reachable through `by: "text"` on a shorter fragment.
     */
    private const val MAX_LABEL_LENGTH = 60
}
