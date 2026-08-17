package dev.axon.core.skills

import dev.axon.core.model.CompiledSkill
import dev.axon.core.planner.AppIntent

/**
 * Decides whether an utterance is served by a compiled skill, and with what
 * slots (spec §7.7, §9.2).
 *
 * Extracted from [InMemorySkillStore] when the SQLite-backed store arrived, and
 * the extraction is not cosmetic. This is the predicate that decides PLAN versus
 * REPLAY on every single task, and a false positive replays the wrong skill
 * against a live device. Two implementations of it — one per store — would be
 * two chances to get that wrong, and the second one would be tested less. There
 * is one, and both stores call it.
 *
 * ## Matching is lexical here, and that is a stated limitation
 *
 * §7.8 specifies *semantic* matching over an on-device vector index, so that
 * "text Ammi that I'm coming" hits a skill whose pattern is "send {message} to
 * {contact} on whatsapp" despite sharing almost no words. That needs an
 * embedding model and an ANN index — a second model resident on a device that
 * already struggles with one.
 *
 * This does slot-aware lexical matching instead: it aligns the utterance against
 * the pattern's literal segments and extracts the spans between them. That
 * handles paraphrase-free repetition — the case C1′ actually needs, since the
 * headline claim is about *repeated* tasks — and fails cleanly on paraphrase
 * rather than guessing.
 *
 * One class of paraphrase **is** handled, exactly rather than approximately:
 * app-launch verbs (E23). "launch whatsapp", "kholo whatsapp" and "open
 * whatsapp" are one request, and [AppIntent] already knew that, so both sides of
 * the comparison are also tried in canonical form. It is a lookup against a verb
 * list, not a similarity score — which is why it is safe on the replay path,
 * where a false match acts on a live device before anything can intervene.
 *
 * ## A false match is worse than a miss
 *
 * A miss costs a slower cold run. A false positive acts on the device before
 * anything can intervene — potentially messaging the wrong person. Every
 * threshold here is therefore set to fail towards the planner.
 */
public object GoalMatcher {

    /**
     * Best match for [utterance] among [candidates], or `null` for the PLAN path.
     *
     * Ties are broken by confidence alone. When two skills score identically the
     * result is whichever the caller listed first, which is stable per store but
     * not meaningful — a corpus where that happens has redundant skills, and the
     * fix is to not compile them, not to invent a tiebreak here.
     *
     * ## Each side is tried in two forms (E23)
     *
     * Literal alignment means a skill learned from "open whatsapp" was missed by
     * "launch whatsapp" — same request, same package, different verb — and the
     * user paid a full cold plan for a task the system had already learned. The
     * *system* was correct and the *person* could not tell why it was slow.
     *
     * Both the utterance and the stored pattern are therefore also tried in
     * canonical form (see [AppIntent.canonical]), which collapses every launch
     * verb AXON recognises — including the Roman-Urdu ones — onto one phrasing.
     * Normalising both sides rather than only the incoming request means skills
     * compiled before this existed match too, so nothing has to be recompiled.
     *
     * This does **not** make matching semantic. It handles verb paraphrase for
     * app-launch goals, which is a narrow class chosen because the normalisation
     * already existed and is exact — `AppIntent` reads the verb list, not a
     * similarity score. "Text ammi that I'm coming" still misses a skill patterned
     * "send {message} to {contact} on whatsapp"; that needs the embedding index
     * §7.8 specifies, and remains a stated limitation.
     */
    public fun match(utterance: String, candidates: Iterable<CompiledSkill>): SkillMatch? {
        val normalised = utterance.lowercase().trim()
        val canonical = AppIntent.canonical(normalised)

        var best: SkillMatch? = null
        for (skill in candidates) {
            val pattern = skill.manifest.goalPattern.lowercase()

            // Literal first, so an exact match never pays for normalisation and
            // a non-launch goal behaves exactly as it did before.
            var form = normalised
            var extracted = extract(normalised, pattern)

            if (extracted == null && canonical != null) {
                // The stored pattern is canonicalised too: a skill compiled from
                // "launch whatsapp" must be reachable by "open whatsapp", not
                // only the other way round.
                val canonicalPattern = AppIntent.canonical(pattern) ?: pattern
                extracted = extract(canonical, canonicalPattern)
                if (extracted != null) form = canonical
            }
            if (extracted == null) continue

            // Every declared parameter must have been filled. A partial match
            // would replay a skill with a missing slot, and the replayer would
            // then refuse — after the store had already committed to the replay
            // path and skipped planning.
            val required = skill.manifest.parameters.filter { it.required }.map { it.name }
            if (!extracted.keys.containsAll(required)) continue

            val confidence = confidenceOf(form, extracted)
            if (confidence < MIN_LITERAL_COVERAGE) continue

            if (best == null || confidence > best.confidence) {
                best = SkillMatch(skill, extracted, confidence)
            }
        }
        return best
    }

    /**
     * Align [utterance] against [pattern] and pull out the slot values.
     *
     * The pattern's literal segments are anchors; whatever sits between two
     * consecutive anchors is the slot value. `"send {message} to {contact} on
     * whatsapp"` against `"send on my way to ammi on whatsapp"` anchors on
     * `"send "`, `" to "` and `" on whatsapp"`, yielding `message = "on my way"`
     * and `contact = "ammi"`.
     *
     * Returns `null` when any anchor is absent or out of order, which is the
     * conservative outcome: no match rather than a partial one.
     */
    public fun extract(utterance: String, pattern: String): Map<String, String>? {
        val slots = SLOT.findAll(pattern).map { it.groupValues[1] }.toList()
        if (slots.isEmpty()) {
            return if (utterance == pattern) emptyMap() else null
        }

        val anchors = pattern.split(SLOT).map { it.trim() }
        val values = mutableMapOf<String, String>()
        var cursor = 0

        for ((i, slot) in slots.withIndex()) {
            val before = anchors.getOrNull(i).orEmpty()
            val after = anchors.getOrNull(i + 1).orEmpty()

            if (before.isNotEmpty()) {
                val at = utterance.indexOf(before, cursor)
                if (at < 0) return null
                cursor = at + before.length
            }

            val end = if (after.isEmpty()) {
                utterance.length
            } else {
                val at = utterance.indexOf(after, cursor)
                if (at < 0) return null
                at
            }

            val value = utterance.substring(cursor, end).trim()
            if (value.isEmpty()) return null
            values[slot] = value
            cursor = end
        }
        return values
    }

    /**
     * How much of the utterance the pattern's literal text accounts for.
     *
     * A pattern that is mostly slots matches almost anything — `"{a} {b}"` would
     * "match" every two-word request — so confidence is the share of the
     * utterance covered by *fixed* words. That makes a highly-parameterised
     * pattern score low and fall through to the planner, which is the right
     * failure direction given how much worse a false match is than a miss.
     */
    public fun confidenceOf(utterance: String, extracted: Map<String, String>): Double {
        if (utterance.isEmpty()) return 0.0
        val slotChars = extracted.values.sumOf { it.length }
        val literalChars = (utterance.length - slotChars).coerceAtLeast(0)
        return literalChars.toDouble() / utterance.length
    }

    private val SLOT = Regex("""\{([a-z_][a-z0-9_]*)\}""")

    /**
     * Minimum share of the utterance covered by the pattern's literal words.
     *
     * Deliberately **not** [SkillMatch.MIN_CONFIDENCE], and the distinction
     * matters. That constant is calibrated for the *semantic* matcher §7.8
     * specifies — an embedding cosine, where 0.82 is a meaningful similarity.
     * This is *literal coverage*, a different quantity on a different scale: a
     * perfectly good match like "send on my way to ammi on whatsapp" against
     * "send {message} to {contact} on whatsapp" covers only ~0.61, because the
     * parameters are most of the sentence.
     *
     * Using one threshold for both silently rejected every correct match —
     * caught by the tests, and worth noting as the kind of error that would
     * otherwise present as "skill matching just never fires".
     *
     * 0.45 separates the cases that matter: the example above scores 0.61, while
     * a degenerate "{a} {b}" pattern against "delete everything" scores 0.06 and
     * is refused. Calibrate against AXON-Bench (§14) rather than by feel —
     * specifically the false-match rate on goals that *resemble* an installed
     * skill but differ where it counts.
     */
    public const val MIN_LITERAL_COVERAGE: Double = 0.45
}
