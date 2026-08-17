package dev.axon.android.app

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.axon.core.storage.AxonStorage
import dev.axon.core.storage.db.AxonDatabase

/**
 * Supplies the platform half of §11's persistence — the SQLite driver.
 *
 * `:core` owns the schema, every query and both stores, and has no Android
 * dependency; this is the twenty lines that cannot be portable, because opening
 * a database on Android needs a `Context`. The split is the same one the project
 * already makes for the device itself (`DeviceDriver`), and it is what lets the
 * persistence tests run on the JVM against the same generated code.
 *
 * The database lives in the app's private data directory, so it inherits the
 * platform's per-app isolation: no other app can read what AXON has observed,
 * without the project having to implement anything to make that true. Combined
 * with holding no `INTERNET` permission (D7), the traces are reachable only by
 * this app on this device.
 */
object AndroidAxonStorage {

    @Volatile
    private var database: AxonDatabase? = null

    /**
     * Open the shared database, once per process.
     *
     * A single instance on purpose. `AndroidSqliteDriver` holds a connection
     * pool, and opening a second driver over the same file gives two pools that
     * do not share SQLite's in-process locking — which surfaces as intermittent
     * `SQLITE_BUSY` under exactly the pattern AXON has: the gateway service and
     * the UI both touching the store while a task runs.
     */
    fun database(context: Context): AxonDatabase = database ?: synchronized(this) {
        database ?: build(context.applicationContext).also { database = it }
    }

    private fun build(context: Context): AxonDatabase {
        val driver = AndroidSqliteDriver(
            schema = AxonDatabase.Schema,
            context = context,
            name = AxonStorage.DATABASE_NAME,
            callback = object : AndroidSqliteDriver.Callback(AxonDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // SQLite disables foreign keys per connection by default, so
                    // `ON DELETE CASCADE` on trace_step is inert unless this runs.
                    // Without it, "delete my history" (§16) removes the tasks and
                    // leaves every recorded action behind — the precise opposite
                    // of what the user asked for, and silent.
                    db.setForeignKeyConstraintsEnabled(true)
                }
            },
        )
        return AxonStorage.database(driver)
    }
}
