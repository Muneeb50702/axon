package dev.axon.bench

import dev.axon.core.model.CompiledSkill

/**
 * **E27b** — is the skill-retirement threshold calibrated?
 *
 * ## What is being asked
 *
 * E27 retires a skill that has been *repaired more often than not*:
 *
 * ```kotlin
 * isHealthy = replayCount < MIN_REPLAYS_TO_JUDGE || cleanReplayRate >= MIN_CLEAN_REPLAY_RATE
 * //                        3                                          0.5
 * ```
 *
 * Both numbers are reasoned in their own comments and neither is calibrated.
 * "Whether retirement thresholds fire correctly" was logged as open, and the
 * shallow reading of that question — *does the boolean evaluate as written* — is
 * already covered by unit tests. The question that matters is **what the rule
 * costs**, and that is answerable exactly.
 *
 * ## Why an exact calculation and not a simulation
 *
 * The rule is a threshold on a binomial process, so the probability of retiring a
 * skill whose true per-replay clean rate is `p` can be computed rather than
 * sampled. No seeds, no variance, no "we ran it 10,000 times".
 *
 * ## The property that makes this matter: retirement is absorbing
 *
 * `GoalMatcher` skips an unhealthy skill (`if (!skill.isHealthy) continue`), so a
 * retired skill is **never replayed again** — its counters freeze and it can
 * never recover on its own. `resetHealth` fires only when the skill body changes,
 * i.e. after the goal has been cold-planned twice and recompiled.
 *
 * So a false retirement is not a missed replay. It costs the user a full
 * re-learn: on the target hardware, two planning runs at ~60 s per step (E2).
 * And because the rule is re-evaluated on every replay, a healthy skill gets
 * *many independent chances* to be unlucky.
 */
object RetirementStudy {

    /** Replays to consider; long enough for a well-used skill. */
    const val HORIZON: Int = 50

    /**
     * P(the skill is retired within [horizon] replays | true clean rate [p]).
     *
     * Exact, by dynamic programming over `(replays, cleanReplays)` with the
     * unhealthy condition as an absorbing state — which is what the runtime
     * actually does, since a retired skill is never replayed again.
     */
    fun probabilityRetired(
        p: Double,
        horizon: Int = HORIZON,
        minReplays: Int = CompiledSkill.MIN_REPLAYS_TO_JUDGE,
    ): Double {
        // alive[c] = P(after `t` replays, `c` of them clean, never yet retired)
        var alive = DoubleArray(1).also { it[0] = 1.0 }
        var retired = 0.0

        for (t in 1..horizon) {
            val next = DoubleArray(t + 1)
            for (c in 0 until t) {
                val mass = alive[c]
                if (mass == 0.0) continue
                next[c + 1] += mass * p          // clean replay
                next[c] += mass * (1.0 - p)      // needed repair
            }
            // Apply the rule to every state reachable at this count.
            for (c in 0..t) {
                if (next[c] == 0.0) continue
                if (isRetiredAt(replays = t, clean = c, minReplays = minReplays)) {
                    retired += next[c]
                    next[c] = 0.0
                }
            }
            alive = next
        }
        return retired
    }

    /** The runtime's own rule, restated over raw counts. */
    fun isRetiredAt(
        replays: Int,
        clean: Int,
        minReplays: Int = CompiledSkill.MIN_REPLAYS_TO_JUDGE,
    ): Boolean {
        if (replays < minReplays) return false
        return clean.toDouble() / replays < CompiledSkill.MIN_CLEAN_REPLAY_RATE
    }

    /** Expected replays before retirement, or `null` if it usually never happens. */
    fun medianReplaysToRetire(p: Double, horizon: Int = HORIZON): Int? {
        var previous = 0.0
        for (t in CompiledSkill.MIN_REPLAYS_TO_JUDGE..horizon) {
            val cumulative = probabilityRetired(p, t)
            if (previous < 0.5 && cumulative >= 0.5) return t
            previous = cumulative
        }
        return null
    }

    data class Row(
        val cleanRate: Double,
        val retiredBy10: Double,
        val retiredBy50: Double,
        val medianToRetire: Int?,
    )

    fun rows(): List<Row> =
        listOf(0.95, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1).map { p ->
            Row(
                cleanRate = p,
                retiredBy10 = probabilityRetired(p, 10),
                retiredBy50 = probabilityRetired(p, 50),
                medianToRetire = medianReplaysToRetire(p),
            )
        }

    fun render(): String = buildString {
        appendLine("E27b — SKILL RETIREMENT THRESHOLD, CALIBRATION")
        appendLine("=".repeat(80))
        appendLine(
            "rule: retire when replays >= ${CompiledSkill.MIN_REPLAYS_TO_JUDGE} " +
                "and clean rate < ${CompiledSkill.MIN_CLEAN_REPLAY_RATE}",
        )
        appendLine("retirement is ABSORBING — a retired skill is never replayed again")
        appendLine()
        appendLine("true clean rate".padEnd(18) + "P(retired ≤10)".padEnd(17) +
            "P(retired ≤50)".padEnd(17) + "median replays to retire")
        appendLine("-".repeat(80))
        for (r in rows()) {
            appendLine(
                "${(r.cleanRate * 100).toInt()}%".padEnd(18) +
                    pct(r.retiredBy10).padEnd(17) +
                    pct(r.retiredBy50).padEnd(17) +
                    (r.medianToRetire?.toString() ?: "—"),
            )
        }
        appendLine("=".repeat(80))
        appendLine()
        val healthy = rows().single { it.cleanRate == 0.9 }
        val rotten = rows().single { it.cleanRate == 0.3 }
        appendLine("A skill that replays cleanly 90% of the time is retired with probability")
        appendLine("${pct(healthy.retiredBy50)} within 50 replays. Retirement is permanent until the goal")
        appendLine("is cold-planned twice and recompiled, so that is the cost of the rule on")
        appendLine("a skill that was working.")
        appendLine()
        appendLine("A genuinely rotten skill (30% clean) is retired with probability")
        appendLine("${pct(rotten.retiredBy50)} within 50 replays, median ${rotten.medianToRetire} replays.")
        appendLine()
        appendLine("WHERE THE RISK LIVES. Almost all of it is at the FIRST judgement:")
        appendLine("at 90% clean, P(retired at replay 3) is ${pct(probabilityRetired(0.9, 3))} of the")
        appendLine("${pct(probabilityRetired(0.9, 50))} lifetime total. The minimum-sample constant is")
        appendLine("therefore the lever, not the rate threshold:")
        appendLine()
        appendLine("  min replays".padEnd(16) + "P(retire | 95%)".padEnd(18) +
            "P(retire | 90%)".padEnd(18) + "P(retire | 80%)".padEnd(18) + "P(retire | 30%)")
        appendLine("  " + "-".repeat(76))
        for (m in 3..7) {
            appendLine(
                "  " + "$m".padEnd(14) +
                    pct(probabilityRetired(0.95, HORIZON, m)).padEnd(18) +
                    pct(probabilityRetired(0.90, HORIZON, m)).padEnd(18) +
                    pct(probabilityRetired(0.80, HORIZON, m)).padEnd(18) +
                    pct(probabilityRetired(0.30, HORIZON, m)),
            )
        }
        appendLine()
        appendLine("WHY THE CONSTANT IS NOT CHANGED HERE. Raising it looks free above -- rotten")
        appendLine("skills are still caught 99.9% of the time -- but that column hides the")
        appendLine("cost. A higher minimum means a rotten skill is REPLAYED MORE TIMES before")
        appendLine("retirement, and for a rotten skill most replays need repair, and a repaired")
        appendLine("replay costs a planner call: ~60 s on this hardware (E2), not the ~2.3 s a")
        appendLine("clean replay costs (E22d).")
        appendLine()
        appendLine("  going 3 -> 6 saves  (3.1% - 0.3%) x ~120 s re-learn  =  ~3 s per healthy skill")
        appendLine("  and costs           ~3 extra replays x ~0.7 repair x ~60 s  =  ~126 s per rotten one")
        appendLine()
        appendLine("So the optimum depends entirely on the ratio of healthy to rotten skills in")
        appendLine("a real store -- which is the SAME missing number E26c needs. The lever is")
        appendLine("identified and priced; choosing its value is not possible from this study,")
        appendLine("and picking one anyway would be exactly the unearned calibration E27")
        appendLine("shipped with.")
    }

    private fun pct(v: Double) = "${(v * 1000).toInt() / 10.0}%"
}

/** Print E27b's table. `./gradlew :bench:retirementStudy` */
object RunRetirementStudy {
    @JvmStatic
    fun main(args: Array<String>) = println(RetirementStudy.render())
}
