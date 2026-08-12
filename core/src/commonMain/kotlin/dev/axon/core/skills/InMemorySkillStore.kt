package dev.axon.core.skills

import dev.axon.core.model.Capability
import dev.axon.core.model.CompiledSkill
import dev.axon.core.model.Goal

/**
 * Matches goals to compiled skills (spec §7.7, §9.2).
 *
 * The first decision the control loop makes (§7.2): is there a skill for this?
 * A hit takes the REPLAY path with no model in the loop; a miss takes PLAN.
 * Everything C1′ claims about repeated tasks getting faster flows through this
 * one lookup.
 *
 * ## Matching is lexical here, and that is a stated limitation
 *
 * §7.8 specifies *semantic* matching over an on-device vector index, so that
 * "text Ammi that I'm coming" hits a skill whose pattern is "send {message} to
 * {contact} on whatsapp" despite sharing almost no words. That needs an
 * embedding model and an ANN index, which is Phase 7 work and a second model
 * resident in memory on a device that already struggles with one.
 *
 * This implementation does slot-aware lexical matching instead: it aligns the
 * utterance against the pattern's literal segments and extracts the spans
 * between them. That handles paraphrase-free repetition — which is the case C1′
 * actually needs, since the headline claim is about *repeated* tasks — and fails
 * cleanly on paraphrase rather than guessing.
 *
 * ## A false match is worse than a miss
 *
 * A miss costs a slower cold run. A false positive replays the *wrong skill
 * against a live device* — potentially sending a message to the wrong person
 * before anything can intervene. The threshold is therefore high and the
 * matching conservative: borderline cases fall through to the planner, which is
 * slow but safe.
 */
public class InMemorySkillStore(
    private val granted: Set<Capability> = emptySet(),
) : SkillStore {

    private val skills = mutableMapOf<String, CompiledSkill>()

    override suspend fun match(goal: Goal): SkillMatch? {
        val utterance = goal.utterance.lowercase().trim()

        var best: SkillMatch? = null
        for (skill in skills.values) {
            val extracted = extract(utterance, skill.manifest.goalPattern.lowercase())
                ?: continue

            // Every declared parameter must have been filled. A partial match
            // would replay a skill with a missing slot, and the replayer would
            // then refuse — after the store had already committed to the replay
            // path and skipped planning.
            val required = skill.manifest.parameters.filter { it.required }.map { it.name }
            if (!extracted.keys.containsAll(required)) continue

            val confidence = confidenceOf(utterance, skill.manifest.goalPattern.lowercase(), extracted)
            if (confidence < MIN_LITERAL_COVERAGE) continue

            if (best == null || confidence > best.confidence) {
                best = SkillMatch(skill, extracted, confidence)
            }
        }
        return best
    }

    override suspend fun install(folder: SkillFolder): InstallResult {
        val problems = folder.manifest.validate()
        if (problems.isNotEmpty()) {
            return InstallResult.Rejected(folder.id, problems)
        }

        folder.compiledSteps.forEach { skills[it.manifest.id] = it }

        // §7.10: a skill missing capabilities is still installed, and visible in
        // the permissions screen. Failing the install would hide it from the very
        // screen where the user would grant what it asked for.
        val missing = folder.manifest.capabilities().filterNot { it in granted }
        return if (missing.isEmpty()) {
            InstallResult.Installed(folder.id)
        } else {
            InstallResult.NeedsCapabilities(folder.id, missing.map { it.id })
        }
    }

    override suspend fun save(skill: CompiledSkill) {
        skills[skill.manifest.id] = skill
    }

    override suspend fun all(): List<CompiledSkill> = skills.values.toList()

    override suspend fun recordReplay(skillId: String, neededRepair: Boolean) {
        val skill = skills[skillId] ?: return
        skills[skillId] = skill.copy(
            replayCount = skill.replayCount + 1,
            repairCount = skill.repairCount + if (neededRepair) 1 else 0,
        )
    }

    // -----------------------------------------------------------------

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
    private fun extract(utterance: String, pattern: String): Map<String, String>? {
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
    private fun confidenceOf(
        utterance: String,
        pattern: String,
        extracted: Map<String, String>,
    ): Double {
        val slotChars = extracted.values.sumOf { it.length }
        val literalChars = (utterance.length - slotChars).coerceAtLeast(0)
        if (utterance.isEmpty()) return 0.0
        return literalChars.toDouble() / utterance.length
    }

    private companion object {
        val SLOT = Regex("""\{([a-z_][a-z0-9_]*)\}""")

        /**
         * Minimum share of the utterance covered by the pattern's literal words.
         *
         * Deliberately **not** [SkillMatch.MIN_CONFIDENCE], and the distinction
         * matters. That constant is calibrated for the *semantic* matcher §7.8
         * specifies — an embedding cosine, where 0.82 is a meaningful similarity.
         * This is *literal coverage*, a different quantity on a different scale:
         * a perfectly good match like "send on my way to ammi on whatsapp"
         * against "send {message} to {contact} on whatsapp" covers only ~0.61,
         * because the parameters are most of the sentence.
         *
         * Using one threshold for both silently rejected every correct match —
         * caught by the tests, and worth noting as the kind of error that would
         * otherwise present as "skill matching just never fires".
         *
         * 0.45 separates the cases that matter: the example above scores 0.61,
         * while a degenerate "{a} {b}" pattern against "delete everything" scores
         * 0.06 and is refused. Calibrate against AXON-Bench (§14) rather than by
         * feel — specifically the false-match rate on goals that *resemble* an
         * installed skill but differ where it counts.
         */
        const val MIN_LITERAL_COVERAGE = 0.45
    }
}
