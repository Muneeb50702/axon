package dev.axon.core.storage

import dev.axon.core.model.AxonJson
import dev.axon.core.model.Capability
import dev.axon.core.model.CompiledSkill
import dev.axon.core.model.Goal
import dev.axon.core.skills.GoalMatcher
import dev.axon.core.skills.InstallResult
import dev.axon.core.skills.SkillFolder
import dev.axon.core.skills.SkillMatch
import dev.axon.core.skills.SkillStore
import dev.axon.core.storage.db.AxonDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/**
 * A [SkillStore] that survives the process (spec §11, §7.7) — the last piece
 * C1′ was missing.
 *
 * Everything the learning loop does was already correct before this existed; it
 * just did not last. Phase 5 shipped with skills in a `mutableMap`, so the claim
 * "repeated tasks cost zero model calls" was true within one run of the app and
 * false the moment it was killed — which on this hardware happens routinely, and
 * not only when the user asks (E6: the OEM power manager SIGKILLs sustained
 * foreground compute). A user who taught AXON a task on Monday found it had
 * forgotten by Tuesday, and the 8,407× replay speedup (E17) was only ever
 * reachable on the second attempt of a single sitting.
 *
 * ## The cache is not premature optimisation
 *
 * Skills are held in memory and written through to SQLite, rather than read from
 * SQLite per lookup. That is a deliberate response to a measured constraint.
 *
 * [match] runs on the critical path of *every* task, and it needs whole
 * `CompiledSkill` objects — [GoalMatcher] inspects declared parameters, not just
 * the goal pattern — so a read-through store would deserialise every skill on
 * every request. Replay costs **17 ms end-to-end** (E17). Parsing even a few
 * dozen skill bodies on a Helio G85 is tens of milliseconds, so a read-through
 * store would spend more time deciding to replay than replaying, and would erode
 * the headline number of the whole contribution.
 *
 * The cost of the cache is the usual one: two places holding the same state.
 * It is contained by making every mutation go to SQLite *first* and update the
 * map only after the write returns, so a failed write can never leave a skill
 * that this process believes in and the database has never heard of. The
 * reverse — a durable skill missing from the map — resolves itself on next
 * launch, and is the harmless direction.
 */
public class SqlSkillStore(
    private val db: AxonDatabase,
    private val granted: Set<Capability> = emptySet(),
    private val nowMs: () -> Long,
) : SkillStore {

    private val lock = Mutex()
    private var cache: MutableMap<String, CompiledSkill>? = null

    /**
     * Load every persisted skill.
     *
     * Lazy rather than in the constructor, because construction is not a
     * suspending context and reading the database on the main thread at app
     * start is how a launch becomes janky on exactly the hardware this project
     * is about.
     */
    private suspend fun skills(): MutableMap<String, CompiledSkill> = lock.withLock {
        cache ?: hydrate().also { cache = it }
    }

    private fun hydrate(): MutableMap<String, CompiledSkill> {
        val loaded = LinkedHashMap<String, CompiledSkill>()
        var unreadable = 0

        for (row in db.skillQueries.selectAll().executeAsList()) {
            // An unparseable body skips that skill rather than failing the load.
            //
            // The alternative is that one bad row — a partial write, or a body
            // from a build whose schema changed incompatibly — bricks the whole
            // learning system, and the symptom the user sees is "AXON stopped
            // remembering anything". Losing one skill degrades to a cold PLAN
            // for that one task, which is slow but correct. `persistence`-style
            // forward compatibility (`ignoreUnknownKeys`) already absorbs the
            // common case of an added field, so reaching here means real damage.
            val skill = runCatching {
                AxonJson.compact.decodeFromString<CompiledSkill>(row.json)
            }.getOrNull()

            if (skill == null) {
                unreadable++
                continue
            }

            // The COLUMNS are authoritative for the counters, per Skill.sq: they
            // are bumped by SQL `UPDATE`s that never rewrite the blob, so the
            // JSON's copy is whatever it happened to be at compile time.
            loaded[row.id] = skill.copy(
                replayCount = row.replay_count,
                repairCount = row.repair_count,
            )
        }

        // Surfaced rather than swallowed: silent partial loading is how a store
        // ends up "working" while quietly holding half the user's skills.
        unreadableOnLoad = unreadable
        return loaded
    }

    /**
     * Skills that failed to deserialise at load, if any.
     *
     * Non-zero means real corruption and belongs in front of the user, not in a
     * log line nobody reads.
     */
    public var unreadableOnLoad: Int = 0
        private set

    override suspend fun match(goal: Goal): SkillMatch? =
        GoalMatcher.match(goal.utterance, skills().values)

    override suspend fun install(folder: SkillFolder): InstallResult {
        val problems = folder.manifest.validate()
        if (problems.isNotEmpty()) {
            return InstallResult.Rejected(folder.id, problems)
        }

        folder.compiledSteps.forEach { save(it) }

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
        val cached = skills()

        // E27: did the *script* change, or only its slots?
        //
        // The SQL deliberately preserves the replay counters, which is right for
        // a refinement and wrong for a re-compile after drift: a skill retired
        // for a poor clean-replay rate would inherit that rate onto its
        // replacement and stay retired forever, having just been repaired.
        val previous = cached[skill.manifest.id]
        val bodyChanged = previous != null && previous.steps != skill.steps

        db.skillQueries.save(
            id = skill.manifest.id,
            goalPattern = skill.manifest.goalPattern,
            deviceFamily = skill.manifest.deviceFamily,
            compiledAtMs = nowMs(),
            json = AxonJson.compact.encodeToString(skill),
        )
        if (bodyChanged) db.skillQueries.resetHealth(skill.manifest.id)
        // Re-read rather than trusting the argument's counters. `save` is also
        // the re-compile path after UI drift, and the SQL deliberately preserves
        // the existing replay/repair counts (Skill.sq) — so the object handed in
        // may carry stale zeroes that must not overwrite an earned history.
        val persisted = db.skillQueries.selectById(skill.manifest.id).executeAsOneOrNull()
        lock.withLock {
            cached[skill.manifest.id] = skill.copy(
                replayCount = persisted?.replay_count ?: skill.replayCount,
                repairCount = persisted?.repair_count ?: skill.repairCount,
            )
        }
    }

    override suspend fun all(): List<CompiledSkill> = skills().values.toList()

    override suspend fun recordReplay(skillId: String, neededRepair: Boolean) {
        val cached = skills()
        if (skillId !in cached) return

        db.skillQueries.recordReplay(
            repaired = if (neededRepair) 1 else 0,
            id = skillId,
        )
        lock.withLock {
            cached[skillId]?.let { current ->
                cached[skillId] = current.copy(
                    replayCount = current.replayCount + 1,
                    repairCount = current.repairCount + if (neededRepair) 1 else 0,
                )
            }
        }
    }

    /**
     * Forget a skill entirely — §16's revocability, applied to learned behaviour.
     *
     * Not on the [SkillStore] interface because nothing in the control loop
     * deletes skills; this exists for the user, via the settings screen. A user
     * who can see what the agent has learned must be able to make it unlearn,
     * and "stop replaying this" is a different request from "clear my history".
     */
    public suspend fun forget(skillId: String) {
        val cached = skills()
        db.skillQueries.delete(skillId)
        lock.withLock { cached.remove(skillId) }
    }

    /** Rows in the table, ignoring the cache — used by the persistence tests. */
    public fun persistedCount(): Long = db.skillQueries.countAll().executeAsOne()
}
