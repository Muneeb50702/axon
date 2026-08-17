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
    // The harness is suspend-based end to end, because the runtime it drives is.
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Emit the §10.6 action grammar so llama.cpp's own parser can validate the exact
 * text the app ships. See ExportGrammar for why the grammar is generated rather
 * than checked in.
 */
val exportGrammar by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Write the action grammar to build/axon-action.gbnf"
    mainClass.set("dev.axon.bench.ExportGrammar")
    classpath = sourceSets["main"].runtimeClasspath
    args(layout.buildDirectory.file("axon-action.gbnf").get().asFile.path)
    outputs.file(layout.buildDirectory.file("axon-action.gbnf"))
}

/**
 * E26b — selector promotion under UI drift.
 *
 * A `JavaExec` rather than a test that prints, because the table is a research
 * artefact someone reproduces on demand; the *assertions* about it live in
 * SkillDriftStudyTest and run on every build.
 */
val driftStudy by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Print E26b: does selector promotion survive UI drift?"
    mainClass.set("dev.axon.bench.RunSkillDriftStudy")
    classpath = sourceSets["main"].runtimeClasspath
}

/**
 * E9 — the C2 recovery-rate measurement, structural half.
 *
 * Reports a ceiling and a floor rather than one number, because the device rate
 * is a product of the loop and the model and only the loop runs here.
 */
val recoveryStudy by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Print E9: what recovery rate does the control loop permit?"
    mainClass.set("dev.axon.bench.RunRecoveryStudy")
    classpath = sourceSets["main"].runtimeClasspath
}

/** E27b — is the skill-retirement threshold calibrated? Exact, not simulated. */
val retirementStudy by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Print E27b: what does the retirement rule cost a healthy skill?"
    mainClass.set("dev.axon.bench.RunRetirementStudy")
    classpath = sourceSets["main"].runtimeClasspath
}
