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
     * Bring an existing database up to the current schema (E26).
     *
     * ## The install that would have failed
     *
     * `trace_step` gained three columns for E26's resolved selector handles. The
     * build stayed green and every test passed, because `Schema.create()` runs
     * only against a *fresh* database and every test opens a temp file. The
     * phone — holding a database with real learned skills in it — would have got
     * the old table, and the first task after upgrading would have failed on an
     * `insertStep` naming columns that did not exist.
     *
     * Same class as D11's SQLite-dialect trap and E21c's package visibility:
     * **the developer's environment is not the deployment environment.** Here
     * the difference is a table that already exists.
     *
     * ## Why this is hand-rolled rather than a `.sqm`
     *
     * SQLDelight's migration support wants versioned `.sqm` files validated
     * against `.db` schema snapshots. That is the right machinery for a schema
     * with a history, and adopting it *after* the `.sq` had already been changed
     * would mean reconstructing a snapshot of a version no longer in the tree.
     *
     * So this applies additive columns idempotently instead, by asking SQLite
     * what the table currently has. It is honest about its limits: it handles
     * **added nullable columns only**. A change that renames, drops or retypes a
     * column needs real migrations, and that is the point to adopt `.sqm` files
     * properly rather than extending this.
     *
     * Idempotent by construction — reading `PRAGMA table_info` rather than
     * tracking a version number means a half-applied upgrade, or one applied by
     * an older build, converges rather than failing.
     */
    public fun migrate(driver: SqlDriver) {
        val existing = columnsOf(driver, "trace_step")

        // An empty result means the table is absent — a fresh database, where
        // `createSchema` is the caller's job and there is nothing to migrate.
        if (existing.isEmpty()) return

        for ((column, type) in ADDED_IN_E26) {
            if (column in existing) continue
            driver.execute(null, "ALTER TABLE trace_step ADD COLUMN $column $type", 0)
        }
    }

    /** Column names of [table], or empty when it does not exist. */
    private fun columnsOf(driver: SqlDriver, table: String): Set<String> =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info($table)",
            parameters = 0,
            mapper = { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.next().value) {
                    // PRAGMA table_info columns: cid, name, type, notnull, ...
                    cursor.getString(1)?.let { names += it }
                }
                app.cash.sqldelight.db.QueryResult.Value(names.toSet())
            },
        ).value

    /**
     * Columns added by E26, as `name to type`.
     *
     * Nullable on purpose: adding a nullable column rewrites no rows, which
     * matters on a Helio G85 with a trace history — a table rebuild would be a
     * visible freeze at launch.
     */
    private val ADDED_IN_E26 = listOf(
        "resolved_view_id" to "TEXT",
        "resolved_content_desc" to "TEXT",
        "resolved_text" to "TEXT",
    )

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
