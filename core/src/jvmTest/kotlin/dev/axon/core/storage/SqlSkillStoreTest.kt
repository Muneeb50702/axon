package dev.axon.core.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.axon.core.model.CompiledSkill
import dev.axon.core.model.CompiledStep
import dev.axon.core.model.Goal
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.SkillManifest
import dev.axon.core.model.SkillParameter
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.skills.InMemorySkillStore
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The claim this file exists to defend: learning survives the process.**
 *
 * Phase 5 measured an 8,407× speedup for replaying a compiled skill (E17), and
 * described AXON as a system that "gets faster the more it is used". With skills
 * in a `mutableMap` that description had an unstated qualifier — *within a single
 * run of the app* — and on this hardware that qualifier is severe: the OEM power
 * manager SIGKILLs sustained foreground compute after ~7 minutes (E6), so the
 * process dies routinely without the user ever closing anything.
 *
 * Every test here runs against a **real SQLite file**, and the ones that matter
 * close the driver entirely and open a new one before asserting. An in-memory
 * database would pass while testing nothing: the property under test is
 * precisely that the data outlives the connection that wrote it.
 *
 * These run on the JVM. C4's second dividend — a reviewer without the handset
 * can check the persistence claim, which is unusual in on-device agent work.
 */
class SqlSkillStoreTest {

    private val dir = Files.createTempDirectory("axon-skill-store")
    private val dbPath = dir.resolve("axon.db").absolutePathString()
    private val drivers = mutableListOf<JdbcSqliteDriver>()

    @AfterTest
    fun cleanup() {
        drivers.forEach { runCatching { it.close() } }
        dir.toFile().deleteRecursively()
    }

    /**
     * Open a store against the shared file, creating the schema on first use.
     *
     * Calling this twice is what "the app was killed and restarted" looks like
     * from the outside, which is why every driver is tracked and closed rather
     * than left to a finaliser.
     */
    private fun openStore(): SqlSkillStore {
        val fresh = !java.io.File(dbPath).exists()
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        drivers += driver
        if (fresh) AxonStorage.createSchema(driver)
        return SqlSkillStore(AxonStorage.database(driver), nowMs = { 1_700_000_000_000L })
    }

    /** Simulate process death: drop every connection to the file. */
    private fun restart(): SqlSkillStore {
        drivers.forEach { it.close() }
        drivers.clear()
        return openStore()
    }

    private fun skill(
        id: String = "whatsapp_send",
        pattern: String = "send {message} to {contact} on whatsapp",
        params: List<String> = listOf("message", "contact"),
    ) = CompiledSkill(
        manifest = SkillManifest(
            id = id,
            name = id,
            goalPattern = pattern,
            parameters = params.map { SkillParameter(it, "string", required = true) },
            deviceFamily = "tecno-camon-20",
        ),
        steps = listOf(
            CompiledStep(
                step = 1,
                selector = Target(TargetBy.CONTENT_DESC, "Send"),
                action = "tap",
                expect = PostCondition(PostConditionType.NODE_PRESENT, "Sent"),
                bindings = mapOf("text" to "message"),
            ),
        ),
        sourceTraces = listOf("trace-1", "trace-2"),
    )

    // ---------------------------------------------------------------------

    @Test
    fun `a compiled skill survives a restart and still matches`() = runTest {
        openStore().save(skill())

        // Everything before this line is a different process, as far as the
        // store below is concerned.
        val reopened = restart()

        val match = reopened.match(Goal("send on my way to ammi on whatsapp"))
        assertNotNull(match, "a persisted skill must still be matchable after a restart")
        assertEquals("whatsapp_send", match.skill.manifest.id)
        assertEquals(mapOf("message" to "on my way", "contact" to "ammi"), match.params)
    }

    @Test
    fun `the skill body round-trips byte-for-byte`() = runTest {
        val original = skill()
        openStore().save(original)

        val loaded = restart().all().single()

        // Not merely "a skill with the same id": selectors, assertions, slot
        // bindings and provenance must all survive, because replay is only
        // deterministic if what is replayed is what was compiled.
        assertEquals(original, loaded)
    }

    @Test
    fun `replay counters persist and accumulate across restarts`() = runTest {
        val first = openStore()
        first.save(skill())
        first.recordReplay("whatsapp_send", neededRepair = false)
        first.recordReplay("whatsapp_send", neededRepair = true)

        val second = restart()
        second.recordReplay("whatsapp_send", neededRepair = false)

        val loaded = restart().all().single()
        assertEquals(3, loaded.replayCount)
        assertEquals(1, loaded.repairCount)

        // The C2 drift signal — meaningless if it resets whenever the app does.
        assertEquals(2.0 / 3.0, loaded.cleanReplayRate, 1e-9)
    }

    @Test
    fun `recompiling after UI drift replaces the body but keeps the earned history`() = runTest {
        val store = openStore()
        store.save(skill())
        repeat(40) { store.recordReplay("whatsapp_send", neededRepair = false) }
        store.recordReplay("whatsapp_send", neededRepair = true)

        // The app updated, the skill broke, and it was compiled again.
        val repaired = skill().copy(
            steps = listOf(
                CompiledStep(
                    step = 1,
                    selector = Target(TargetBy.CONTENT_DESC, "Send message"),
                    action = "tap",
                    expect = PostCondition(PostConditionType.NODE_PRESENT, "Sent"),
                ),
            ),
        )
        store.save(repaired)

        val loaded = restart().all().single()
        assertEquals(
            "Send message", loaded.steps.single().selector?.value,
            "the recompiled body must win",
        )
        assertEquals(
            41, loaded.replayCount,
            "a skill that has replayed 41 times has earned that history; resetting it on " +
                "every recompile would make cleanReplayRate measure only the time since the " +
                "last drift",
        )
        assertEquals(1, loaded.repairCount)
    }

    @Test
    fun `forget removes a skill permanently`() = runTest {
        val store = openStore()
        store.save(skill())
        store.forget("whatsapp_send")

        val reopened = restart()
        assertTrue(reopened.all().isEmpty())
        assertNull(reopened.match(Goal("send on my way to ammi on whatsapp")))
        assertEquals(0L, reopened.persistedCount())
    }

    @Test
    fun `matching agrees with the in-memory store on every case`() = runTest {
        // The SQL store's whole value is durability; it must not also change
        // behaviour. Both delegate to GoalMatcher, and this is what keeps that
        // true rather than merely intended.
        val corpus = listOf(
            skill(),
            skill("call_contact", "call {contact}", listOf("contact")),
            skill("open_app", "open {app}", listOf("app")),
        )

        val memory = InMemorySkillStore().also { m -> corpus.forEach { m.save(it) } }
        val sql = openStore().also { s -> corpus.forEach { s.save(it) } }

        // No blank utterance here: `Goal` rejects one at construction, so the
        // stores are never asked. Testing it would assert a property of `Goal`
        // in the wrong file.
        val utterances = listOf(
            "send on my way to ammi on whatsapp",
            "call ammi",
            "open whatsapp",
            "please do something entirely unrelated",
            "send",
            "send  to  on whatsapp",
        )

        for (utterance in utterances) {
            val a = memory.match(Goal(utterance))
            val b = sql.match(Goal(utterance))
            assertEquals(a?.skill?.manifest?.id, b?.skill?.manifest?.id, "mismatch on: '$utterance'")
            assertEquals(a?.params, b?.params, "params differ on: '$utterance'")
        }
    }

    @Test
    fun `an unparseable skill body is skipped rather than failing the whole load`() = runTest {
        val store = openStore()
        store.save(skill())
        store.save(skill("call_contact", "call {contact}", listOf("contact")))

        // Corrupt exactly one row, the way a partial write or an incompatible
        // schema change would.
        val driver = drivers.last()
        driver.execute(null, "UPDATE skill SET json = '{not valid json' WHERE id = 'call_contact'", 0)

        val reopened = restart()
        val loaded = reopened.all()

        assertEquals(
            1, loaded.size,
            "one bad row must not take the others down with it — the symptom would be " +
                "'AXON stopped remembering anything'",
        )
        assertEquals("whatsapp_send", loaded.single().manifest.id)
        assertEquals(1, reopened.unreadableOnLoad, "corruption must be surfaced, not swallowed")
    }
}
