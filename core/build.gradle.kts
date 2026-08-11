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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
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
