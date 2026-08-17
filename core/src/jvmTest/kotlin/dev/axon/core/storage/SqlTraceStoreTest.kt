package dev.axon.core.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.TaskOutcome
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.model.TraceStep
import dev.axon.core.model.VerifiedTrace
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Durable episodic memory and the §16 audit log.
 *
 * Two properties are under test, and they pull in opposite directions:
 *
 *  - **Lossless round-trip.** A trace reloaded after a restart must still be
 *    compilable, which means the fields the compiler reads — screen hashes
 *    especially — have to survive. Shredding steps into columns is where that
 *    would quietly break, and where an in-memory store never could.
 *  - **Nothing filtered on the way in.** Refusals, gate failures and heals all
 *    belong in the audit log even though none will ever be compiled. An audit
 *    log containing only successes is not an audit log.
 */
class SqlTraceStoreTest {

    private val dir = Files.createTempDirectory("axon-trace-store")
    private val dbPath = dir.resolve("axon.db").absolutePathString()
    private val drivers = mutableListOf<JdbcSqliteDriver>()

    @AfterTest
    fun cleanup() {
        drivers.forEach { runCatching { it.close() } }
        dir.toFile().deleteRecursively()
    }

    private fun openStore(): SqlTraceStore {
        val fresh = !java.io.File(dbPath).exists()
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        drivers += driver
        if (fresh) AxonStorage.createSchema(driver)
        // SQLite defaults foreign keys OFF per connection, so ON DELETE CASCADE
        // is inert unless every connection turns them on. Asserted below rather
        // than assumed, because the failure mode is silent orphaned rows.
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        return SqlTraceStore(AxonStorage.database(driver))
    }

    private fun restart(): SqlTraceStore {
        drivers.forEach { it.close() }
        drivers.clear()
        return openStore()
    }

    private fun step(
        label: String,
        preOk: Boolean = true,
        postOk: Boolean = true,
        healed: Boolean = false,
        reason: String? = null,
    ) = TraceStep(
        action = DeviceAction.Tap(
            target = Target(TargetBy.CONTENT_DESC, label),
            expect = PostCondition(PostConditionType.NODE_PRESENT, "Sent"),
        ),
        preOk = preOk,
        postOk = postOk,
        latencyMs = 120,
        healed = healed,
        llmCalls = 1,
        stateHashBefore = 111,
        stateHashAfter = 222,
        failureReason = reason,
    )

    private fun trace(
        id: String = "trace-1",
        goal: String = "send on my way to ammi on whatsapp",
        outcome: TaskOutcome = TaskOutcome.SUCCESS,
        steps: List<TraceStep> = listOf(step("Ammi"), step("Send")),
        startedAtMs: Long = 1_700_000_000_000L,
    ) = VerifiedTrace(
        traceId = id,
        goal = goal,
        params = mapOf("contact" to "ammi", "message" to "on my way"),
        steps = steps,
        outcome = outcome,
        llmCalls = 2,
        totalMs = 142_917,
        device = "tecno-camon-20",
        model = "gemma-3-1b-it-q4_k_m",
        config = "D",
        startedAtMs = startedAtMs,
    )

    // ---------------------------------------------------------------------

    @Test
    fun `a trace round-trips losslessly across a restart`() = runTest {
        val original = trace()
        openStore().append(original)

        val loaded = restart().all().single()

        assertEquals(original, loaded)
    }

    @Test
    fun `a reloaded trace is still compilable`() = runTest {
        // The real risk of shredding steps into columns: drop `stateHashBefore`
        // and the trace still looks fine, still round-trips "close enough", and
        // silently stops being usable as compiler input. That would present as
        // "AXON never learns anything after a restart" — which is exactly the
        // bug persistence was added to fix.
        openStore().append(trace())

        val loaded = restart().compilable()
        assertEquals(1, loaded.size)
        assertTrue(loaded.single().isCompilable)
        assertEquals(111, loaded.single().steps.first().stateHashBefore)
        assertEquals(222, loaded.single().steps.first().stateHashAfter)
    }

    @Test
    fun `refusals and heals are recorded, not filtered`() = runTest {
        val messy = trace(
            id = "trace-messy",
            outcome = TaskOutcome.ESCALATED,
            steps = listOf(
                step("Ammi", preOk = false, postOk = false, reason = "no node matched 'Ammi'"),
                step("Send", healed = true),
            ),
        )
        openStore().append(messy)

        val loaded = restart()
        assertEquals(1, loaded.all().size, "a failed run is still history")
        assertTrue(loaded.compilable().isEmpty(), "but it must never become a skill")

        val audit = loaded.auditLog()
        assertEquals(2, audit.size)
        assertFalse(audit.first { it.step == 0 }.wasPerformed)
        assertEquals("no node matched 'Ammi'", audit.first { it.step == 0 }.failureReason)
        assertTrue(audit.first { it.step == 1 }.healed)
    }

    @Test
    fun `the audit log carries the goal each action served`() = runTest {
        val store = openStore()
        store.append(trace(id = "t1", goal = "call ammi", startedAtMs = 1000))
        store.append(trace(id = "t2", goal = "open whatsapp", startedAtMs = 2000))

        // Newest first — "what did AXON just do" is the common question.
        val audit = restart().auditLog()
        assertEquals(4, audit.size)
        assertEquals("open whatsapp", audit.first().goal)
        assertEquals("tap", audit.first().actionType)
    }

    @Test
    fun `the clean-run gate is counted in SQL and matches isCompilable`() = runTest {
        val store = openStore()
        val goal = "send on my way to ammi on whatsapp"

        store.append(trace(id = "clean-1", goal = goal, startedAtMs = 1000))
        assertEquals(1L, store.cleanRunCount(goal), "one clean run is not yet two (§7.7)")

        // A run that limped there through a heal must not count towards
        // compilation, or the compiler freezes the mistakes in.
        store.append(
            trace(
                id = "healed", goal = goal, startedAtMs = 2000,
                steps = listOf(step("Ammi"), step("Send", healed = true)),
            ),
        )
        assertEquals(1L, store.cleanRunCount(goal))

        store.append(trace(id = "clean-2", goal = goal, startedAtMs = 3000))
        assertEquals(2L, store.cleanRunCount(goal), "now the §7.7 threshold is met")

        // The SQL predicate and the Kotlin one must agree — two definitions of
        // "clean" is how a store starts compiling things it should not.
        val reopened = restart()
        assertEquals(
            reopened.forGoal(goal).count { it.isCompilable }.toLong(),
            reopened.cleanRunCount(goal),
        )
    }

    @Test
    fun `erasing history cascades to steps and leaves no orphans`() = runTest {
        val store = openStore()
        store.append(trace(id = "t1"))
        store.append(trace(id = "t2"))

        store.eraseHistory()

        val reopened = restart()
        assertEquals(0L, reopened.count())
        assertTrue(
            reopened.auditLog().isEmpty(),
            "orphaned steps would mean 'delete my history' left the actions behind — " +
                "the exact opposite of what §16 promises",
        )
    }

    @Test
    fun `erasing one trace leaves the others`() = runTest {
        val store = openStore()
        store.append(trace(id = "t1", goal = "call ammi", startedAtMs = 1000))
        store.append(trace(id = "t2", goal = "open whatsapp", startedAtMs = 2000))

        store.erase("t1")

        val reopened = restart()
        assertEquals(1L, reopened.count())
        assertEquals("open whatsapp", reopened.all().single().goal)
        assertEquals(2, reopened.auditLog().size)
    }

    @Test
    fun `an outcome this build does not recognise degrades to ERROR`() = runTest {
        val store = openStore()
        store.append(trace())
        drivers.last().execute(
            null, "UPDATE trace SET outcome = 'SOMETHING_FROM_A_LATER_BUILD'", 0,
        )

        val loaded = restart().all().single()
        assertEquals(TaskOutcome.ERROR, loaded.outcome)
        assertFalse(
            loaded.isCompilable,
            "a value this build cannot interpret must never become a replayable skill",
        )
    }
}
