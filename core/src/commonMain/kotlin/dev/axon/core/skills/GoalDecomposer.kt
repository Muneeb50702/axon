package dev.axon.core.skills

/**
 * Splits a compound goal into the parts a person actually asked for (E24).
 *
 * ## The fifth application of one idea
 *
 * AXON's thesis is that reliability comes from removing judgement from the model
 * wherever the answer is determinable without it. That has now been applied four
 * times, each time to a different decision:
 *
 * | decision | determined by | instead of |
 * |---|---|---|
 * | what shape may an action take | GBNF grammar (C3) | the model getting JSON right |
 * | which elements may be named | the live UI tree | the model remembering the screen |
 * | which app "open X" means | a package lookup (E21) | the model picking an icon |
 * | when a launch goal is finished | foreground package (E21b) | the model self-assessing |
 * | **where one task ends and the next begins** | **this** | **the model planning across both** |
 *
 * "Turn on wifi **then** message Ammi" is two tasks, and the word "then" says so.
 * That is syntax, not semantics — no model is required to see it, and asking one
 * costs ~60 s and may get it wrong.
 *
 * ## Why this matters more here than it would on a flagship
 *
 * A nine-step compound goal costs ~9 minutes of cold planning on a Helio G85 (E2)
 * — past the ~7-minute ceiling the OEM power manager allows (E6). Such a task is
 * **not slow on this hardware, it is impossible.** If both halves are already
 * compiled skills, composing them costs milliseconds.
 *
 * So decomposition is not a convenience. It is what makes multi-app tasks exist
 * at all in this regime, and it is the sharpest available statement of C1′ as a
 * viability mechanism rather than an optimisation.
 *
 * ## Splitting is aggressive; *composing* is what stays conservative
 *
 * This class over-splits on purpose. "Send a message to Ali and Ahmed" is one
 * task, and a naive split on "and" produces a fragment reading "ahmed".
 *
 * Rather than teach a heuristic to recognise that — which would be semantics
 * again, and would be wrong at 1B — the safety lives one level up in
 * [SkillComposer]: **a plan composes only if every fragment matches an existing
 * skill.** "ahmed" matches nothing, so composition declines and the whole goal
 * falls through to the planner exactly as it does today.
 *
 * A bad split therefore costs a missed opportunity, never a wrong action. That
 * is the same asymmetry that governs every threshold in [GoalMatcher]: a miss
 * costs a slow run, a false match acts on a live device.
 */
public object GoalDecomposer {

    /**
     * Split [goal] on sequencing markers, or return it whole.
     *
     * A single-element result means "not compound", which callers can treat as
     * "nothing to compose" without a special case.
     */
    public fun split(goal: String): List<String> {
        val parts = SEQUENCERS.split(goal.trim())
            .map { it.trim().trim(',', '.', ';').trim() }
            .filter { it.isNotEmpty() }

        // A fragment of one or two characters is punctuation debris, not a task.
        // Returning it would guarantee the no-skill-matched outcome anyway, but
        // it would also make `split` produce nonsense that a reader of a trace
        // would have to interpret.
        val meaningful = parts.filter { it.length > 2 }

        return if (meaningful.size < 2) listOf(goal.trim()) else meaningful
    }

    /** Is this goal worth attempting to compose? */
    public fun isCompound(goal: String): Boolean = split(goal).size > 1

    /**
     * Words that mark one request ending and another beginning.
     *
     * `then` and `phir` are unambiguous sequencers. `and` and `aur` are not —
     * they conjoin objects as often as actions — and are included anyway,
     * because [SkillComposer]'s all-fragments-must-match rule makes a wrong
     * split harmless while excluding them would miss the most common phrasing
     * people actually use ("turn on wifi and open whatsapp").
     *
     * Matched with surrounding whitespace so "Ahmed" is not split at "and", and
     * case-insensitively because a request is typed by a person.
     */
    private val SEQUENCERS = Regex(
        """\s+(?:then|and then|and|after that|phir|aur)\s+""",
        RegexOption.IGNORE_CASE,
    )
}
