// :bench — AXON-Bench harness (spec §14, contribution C5).
//
// Pure JVM, and that is a design decision rather than a convenience.
//
// §14 asks for a *reproducible* results table across five ablation configs. If
// the harness could only run on a phone, "reproducible" would mean "if you have
// the same handset, the same app versions and the same battery temperature" —
// which is not reproducible, and is how most student evaluations quietly fail to
// be checkable. Because `:core` has no Android dependency, the whole reliability
// core — planner logic, precondition gate, verifier, skill compiler, replay —
// runs headless against recorded UI trees on any machine, including CI.
//
// The split that follows:
//   • deterministic components → JVM, in CI, on every commit, no device;
//   • device-dependent numbers (latency, thermal ceiling) → the phone, reported
//     separately and labelled as device-bound.
//
// Which also means an examiner can reproduce the §14.3 ablation table without
// owning the hardware.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
