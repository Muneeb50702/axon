package dev.axon.core.skills

import dev.axon.core.model.Capability
import dev.axon.core.model.CompiledSkill
import dev.axon.core.model.Goal

/**
 * Volatile [SkillStore] — matching per [GoalMatcher], storage in a map.
 *
 * The first decision the control loop makes (§7.2): is there a skill for this?
 * A hit takes the REPLAY path with no model in the loop; a miss takes PLAN.
 * Everything C1′ claims about repeated tasks getting faster flows through this
 * one lookup, which is why the lookup itself lives in [GoalMatcher] and is
 * shared with the SQLite-backed store rather than reimplemented there.
 *
 * ## What this is still for, now that skills persist
 *
 * [dev.axon.core.storage.SqlSkillStore] is what the app runs: learning that
 * does not survive a restart is not learning (§11). This implementation remains
 * because a great deal of the test suite and all of AXON-Bench want a store with
 * no file, no driver and no teardown, and because it is the control that makes
 * the persistence tests meaningful — the SQL store is asserted to agree with
 * this one, so "it round-trips" is checked against a known-good reference
 * rather than against itself.
 */
public class InMemorySkillStore(
    private val granted: Set<Capability> = emptySet(),
) : SkillStore {

    private val skills = mutableMapOf<String, CompiledSkill>()

    override suspend fun match(goal: Goal): SkillMatch? =
        GoalMatcher.match(goal.utterance, skills.values)

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

    /**
     * Save, applying the same health rule as the SQLite store (E27).
     *
     * The two stores disagreed before this: SQLite preserved the replay
     * counters across every save, and this one silently reset them by
     * overwriting. That is the class of divergence `GoalMatcher` was extracted
     * to prevent — the tests use this store and the phone uses the other, so a
     * behavioural difference here means the tests are not testing what ships.
     *
     * The rule, stated once: **same steps, keep the history; different steps, it
     * is a different script** and its predecessor's record says nothing about it.
     */
    override suspend fun save(skill: CompiledSkill) {
        val previous = skills[skill.manifest.id]
        val bodyChanged = previous != null && previous.steps != skill.steps

        skills[skill.manifest.id] = when {
            previous == null || bodyChanged -> skill.copy(replayCount = 0, repairCount = 0)
            else -> skill.copy(
                replayCount = previous.replayCount,
                repairCount = previous.repairCount,
            )
        }
    }

    override suspend fun all(): List<CompiledSkill> = skills.values.toList()

    override suspend fun recordReplay(skillId: String, neededRepair: Boolean) {
        val skill = skills[skillId] ?: return
        skills[skillId] = skill.copy(
            replayCount = skill.replayCount + 1,
            repairCount = skill.repairCount + if (neededRepair) 1 else 0,
        )
    }

}
