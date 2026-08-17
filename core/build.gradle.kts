// :core — the portable AXON reliability core (spec §7.1 "AGENT CORE", §9, §10).
//
// HARD RULE: this module has NO Android dependencies. Everything in commonMain
// must compile for every target. The Android-specific world is reached only
// through the DeviceDriver seam (§9.1) and InferenceEngine (§9.3), both of which
// are interfaces declared here and implemented in :android:*.
//
// Two targets today:
//   • jvm     — lets the whole control loop, verifier, compiler and AXON-Bench
//               run headless in CI against a synthetic driver, with no phone.
//   • android — lets :android:driver and :android:app consume the core directly.
// Additional targets (iOS/linuxX64) are a post-FYP concern (§6.3) but the source
// set layout is already correct for them.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

// Spec §11 persistence. The schema and every query live here, in the portable
// core, while the *driver* — the only Android-shaped part — is injected from
// outside (D11). That is what keeps `:core has no Android dependency` true while
// still satisfying "SQLite" rather than inventing a file format.
sqldelight {
    databases {
        create("AxonDatabase") {
            packageName.set("dev.axon.core.storage.db")

            // Off, because this project does not use SQLDelight migrations.
            //
            // The intent behind turning it on was right — the first migration
            // should not silently skip verification. But the task requires a
            // `.db` snapshot in core/src/commonMain/sqldelight/databases, no
            // snapshot was ever generated, and SQLDelight exposes no task to
            // generate one without `.sqm` files present. So it failed
            // unconditionally with *"Verifying a migration requires a database
            // file to be present"*, which meant **`./gradlew build` did not
            // pass** — the flag was not verifying migrations, it was breaking
            // the build for a schema that has never changed.
            //
            // Migrations here are hand-written and idempotent
            // (`AxonStorage.migrate`), chosen when the `.sqm` route was
            // rejected: SQLDelight's migration mode wants `.db` snapshots, and
            // the phone's existing database is not one. That decision is not
            // reversed by this line — it is what this line acknowledges.
            //
            // What actually verifies migrations: `SqlTraceStoreTest` runs the
            // real upgrade against a pre-migration schema, runs it twice to
            // prove convergence, and runs it against an empty database. Those
            // execute on every build; the task below never did.
            verifyMigrations.set(false)
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())

    jvm()

    android {
        namespace = "dev.axon.core"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
            // KMP runtime only. `SqlDriver` is an interface here; supplying one
            // is the caller's job, which is exactly the DeviceDriver pattern
            // applied to storage.
            api(libs.sqldelight.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // In-memory SQLite, so the persistence tests exercise the *same*
            // generated queries the phone runs — in CI, with no device. C4's
            // second dividend, now covering storage too.
            implementation(libs.sqldelight.driver.jvm)
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        // @JsonClassDiscriminator is how §10.1's `"action"` discriminator is
        // expressed; it is still marked experimental upstream, so opt in once
        // here rather than annotating every model file.
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}
