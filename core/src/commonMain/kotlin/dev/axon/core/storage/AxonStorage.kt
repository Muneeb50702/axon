package dev.axon.core.storage

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import dev.axon.core.storage.db.AxonDatabase
import dev.axon.core.storage.db.Skill
import dev.axon.core.storage.db.Trace
import dev.axon.core.storage.db.Trace_step

/**
 * Opens the AXON database (spec §11: "Persistence: SQLite — traces, skills,
 * permissions").
 *
 * ## Why the driver is a parameter and not built here
 *
 * Because `:core` has no Android dependency, and that is compiler-enforced (C4).
 * SQLDelight splits neatly along the same seam AXON already uses for the device:
 * the *runtime* — schema, generated queries, `SqlDriver` — is pure Kotlin
 * Multiplatform, while the drivers that actually talk to SQLite are per-platform
 * (`AndroidSqliteDriver` needs a `Context`; `JdbcSqliteDriver` needs a JDBC URL).
 *
 * So the schema and every query live in the portable core, and the driver is
 * injected from outside — the same shape as [dev.axon.core.driver.DeviceDriver].
 * Which buys the thing that matters for the evaluation: the persistence tests
 * run against a JVM driver in CI, exercising the *same generated code* the phone
 * runs, with no device attached.
 *
 * Storing this data at all is what makes C1′ a property of the system rather
 * than of a session. Until it existed, "repeated tasks cost zero model calls"
 * held only until the process died — which on Android, with an OEM power manager
 * that SIGKILLs sustained foreground compute (E6), is not long.
 */
public object AxonStorage {

    /** Filename the Android app should open. Here so both sides agree on it. */
    public const val DATABASE_NAME: String = "axon.db"

    /**
     * Wire adapters onto [driver] and return the database.
     *
     * Does **not** create the schema: `AndroidSqliteDriver` does that itself
     * from `AxonDatabase.Schema`, and calling it twice fails. JVM callers want
     * [openInMemory] or an explicit [createSchema].
     */
    public fun database(driver: SqlDriver): AxonDatabase = AxonDatabase(
        driver = driver,
        skillAdapter = Skill.Adapter(
            replay_countAdapter = IntAdapter,
            repair_countAdapter = IntAdapter,
        ),
        traceAdapter = Trace.Adapter(
            llm_callsAdapter = IntAdapter,
        ),
        // No adapters for the boolean columns: SQLDelight maps `kotlin.Boolean`
        // to INTEGER natively. It only does so for the *qualified* name, though
        // — a bare `AS Boolean` generates `import Boolean`, which does not
        // resolve, and the same trap applies to `AS Int`. Hence `kotlin.` on
        // every annotated column in the .sq files.
        trace_stepAdapter = Trace_step.Adapter(
            stepAdapter = IntAdapter,
            llm_callsAdapter = IntAdapter,
            state_hash_beforeAdapter = IntAdapter,
            state_hash_afterAdapter = IntAdapter,
        ),
    )

    /** Create the tables on a driver that has none. */
    public fun createSchema(driver: SqlDriver) {
        AxonDatabase.Schema.create(driver)
    }

    /**
     * SQLite stores every INTEGER as a 64-bit value, so the narrower Kotlin
     * types declared in the `.sq` files need converting on the way through.
     *
     * The cast is unchecked on purpose: every `AS kotlin.Int` column in this
     * schema is a counter, a step index or a screen hash, none of which can
     * exceed `Int` without something else having gone wrong far earlier. A
     * silent truncation would still be worse than a crash, so this is worth
     * revisiting if a wider column is ever added.
     */
    private object IntAdapter : ColumnAdapter<Int, Long> {
        override fun decode(databaseValue: Long): Int = databaseValue.toInt()
        override fun encode(value: Int): Long = value.toLong()
    }
}
