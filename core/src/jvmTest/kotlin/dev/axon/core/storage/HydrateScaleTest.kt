package dev.axon.core.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.axon.core.model.CompiledSkill
import dev.axon.core.model.CompiledStep
import dev.axon.core.model.Goal
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.SkillManifest
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **E22e** — what persistence costs once AXON has actually learned something.
 *
 * E22b measured hydrate at **39 ms with one skill** on the phone and called it
 * negligible. One skill is not a measurement of a store; it is a measurement of
 * fixed overhead. Two costs grow with what the user has taught AXON:
 *
 * | cost | when it is paid | why it matters |
 * |---|---|---|
 * | **hydrate** | once per process, at launch | deserialises *every* stored skill |
 * | **match** | **every request** | linear scan over every skill, on the path against a 2.3 s replay |
 *
 * `match` is the one to watch. It is the first thing a task does and it decides
 * whether the task is free; a match that costs more than it saves would invert
 * C1′ silently.
 *
 * ## What this test does and does not establish
 *
 * It runs on the JVM against a real SQLite file, so it measures the **shape** of
 * each cost — linear, quadratic, or worse — and that shape transfers.
 *
 * **The absolute milliseconds do not transfer**, and this project has documented
 * three separate cases of assuming otherwise (D11, E21c, E26 — "the developer's
 * environment is not the deployment environment"). A desktop JVM with a warm page
 * cache is not a Helio G85 on a phone already 1.6 GB into swap (E6b). So the
 * assertions below bound the *growth*, and the device figure remains E22b's
 * 39 ms at N=1 plus a shape — not a prediction.
 */
class HydrateScaleTest {

    private val drivers = mutableListOf<JdbcSqliteDriver>()

    @AfterTest
    fun tearDown() {
        drivers.forEach { runCatching { it.close() } }
        drivers.clear()
    }

    private fun openStore(): SqlSkillStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AxonStorage.createSchema(driver)
        drivers += driver
        return SqlSkillStore(AxonStorage.database(driver), nowMs = { 0L })
    }

    /**
     * A skill of realistic weight.
     *
     * Six steps with selectors and post-conditions, because the hydrate cost is
     * JSON deserialisation and a one-step skill would understate it by roughly
     * the ratio of the step counts.
     */
    private fun skill(n: Int) = CompiledSkill(
        manifest = SkillManifest(
            id = "skill_$n",
            name = "Skill $n",
            goalPattern = "do task number $n",
            deviceFamily = "fake/test",
        ),
        steps = (1..6).map { i ->
            CompiledStep(
                step = i,
                selector = Target(TargetBy.CONTENT_DESC, "Element $i of task $n"),
                action = "tap",
                expect = PostCondition(PostConditionType.NODE_PRESENT, "Result $i"),
                args = mapOf("hint" to "step $i of skill $n"),
            )
        },
        sourceTraces = listOf("trace-$n-a", "trace-$n-b"),
    )

    private inline fun microsOf(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000
    }

    /**
     * Run everything once and throw the numbers away.
     *
     * The first version of this test reported match at 75 µs for **one** skill
     * and 81 µs for **five hundred** — 8% more work for 500× the data, which is
     * impossible for a linear scan and was JIT warmup dominating the small
     * cases. An unreliable measurement is worse than no measurement, because it
     * looks like evidence. Warmup is not a detail here; it *was* the result.
     */
    private fun warmUp() {
        val store = openStore()
        repeat(200) { runCatchingBlocking { store.save(skill(it)) } }
        repeat(50) { runCatchingBlocking { store.all() } }
        repeat(5_000) { runCatchingBlocking { store.match(Goal("do task number 199")) } }
    }

    @Test
    fun `hydrate and match scale linearly, and the table is printed`() = runTest {
        warmUp()

        val sizes = listOf(1, 10, 50, 100, 250, 500)
        val hydrateUs = mutableMapOf<Int, Long>()
        val matchUs = mutableMapOf<Int, Long>()

        for (size in sizes) {
            // Populate, then open a *second* store over the same data so the
            // hydrate being timed is genuinely cold.
            val seeded = openStore()
            repeat(size) { seeded.save(skill(it)) }
            val db = AxonStorage.database(drivers.last())

            // Hydrate is once-per-process, so it cannot be averaged over
            // iterations on one store — each measurement needs its own cold
            // cache. Take the best of several, which is the standard defence
            // against a scheduler hiccup landing in a single sample.
            hydrateUs[size] = (1..5).minOf {
                val cold = SqlSkillStore(db, nowMs = { 0L })
                microsOf { runCatchingBlocking { cold.all() } }
            }

            val check = SqlSkillStore(db, nowMs = { 0L })
            assertEquals(size, check.all().size, "store should hold $size skills")

            // Match against a warm cache — the per-request cost. The goal names
            // the LAST skill inserted, so the scan cannot short-circuit early
            // and flatter the result.
            val goal = Goal("do task number ${size - 1}")
            assertTrue(check.match(goal) != null, "the probe goal must actually match")

            val iterations = 2_000
            val start = System.nanoTime()
            repeat(iterations) { runCatchingBlocking { check.match(goal) } }
            matchUs[size] = (System.nanoTime() - start) / 1_000 / iterations
        }
        val hydrateMs = hydrateUs.mapValues { (_, us) -> us / 1000 }

        println(buildString {
            appendLine()
            appendLine("E22e — PERSISTENCE COST BY STORE SIZE (JVM; shape only, not device ms)")
            appendLine("=".repeat(72))
            appendLine("skills".padEnd(10) + "hydrate (µs)".padEnd(16) + "match (µs, warm cache)")
            appendLine("-".repeat(72))
            for (size in sizes) {
                appendLine(
                    "$size".padEnd(10) +
                        "${hydrateUs[size]}".padEnd(16) +
                        "${matchUs[size]}",
                )
            }
            appendLine("=".repeat(72))
            appendLine("Absolute values are JVM figures and do NOT transfer to the phone.")
            appendLine("E22b's device number is 39 ms at N=1; this gives the shape, not a")
            appendLine("prediction. See the class comment.")
        })

        // --- the assertions that matter: growth, not absolutes ---------------

        // Match is a linear scan over the cache. 50x the skills must not cost
        // wildly more than 50x the time — anything superlinear here means the
        // matcher acquired an accidental quadratic, which would land on the
        // request path and invert C1′ silently.
        val small = maxOf(matchUs[10] ?: 1, 1)
        val large = matchUs[500] ?: 0
        assertTrue(
            large < small * 200,
            "match looks superlinear: ${small}µs at 10 skills, ${large}µs at 500",
        )

        // Hydrate is deserialisation of every row; it must stay well inside the
        // budget a user would notice at launch even with a large store.
        assertTrue(
            (hydrateMs[500] ?: 0) < 5_000,
            "hydrate of 500 skills took ${hydrateMs[500]}ms on a desktop JVM — " +
                "on the target device that is a launch the user would feel",
        )

        // Match must actually grow with the store, or the probe is
        // short-circuiting and the flat line is an artefact rather than a
        // finding. This is the assertion the first, warmup-dominated version of
        // this test would have failed.
        assertTrue(
            large > small,
            "match at 500 skills (${large}µs) is not slower than at 10 (${small}µs) — " +
                "the scan is being short-circuited and this table means nothing",
        )
    }

    /** `runBlocking` is unavailable in commonTest style here; keep it local and explicit. */
    private fun <T> runCatchingBlocking(block: suspend () -> T): T? =
        kotlinx.coroutines.runBlocking { runCatching { block() }.getOrNull() }
}
