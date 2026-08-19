package dev.axon.bench

import dev.axon.core.model.DeviceAction
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.TaskOutcome
import dev.axon.core.model.TraceStep
import dev.axon.core.model.VerifiedTrace
import java.io.File

/**
 * Load a device corpus run (`tools/run-corpus.sh`) into §14.2's metrics — **E8**.
 *
 * ## Why the CSV is turned into `TaskScore` rather than summed directly
 *
 * Task success rate, LLM-calls-per-task and step efficiency are already defined
 * once, in [BenchMetrics.of]. Computing them again here from the CSV would give
 * the project two definitions of its headline number, and the one printed in the
 * paper would be whichever file the reader happened not to check. So the runner's
 * output is mapped into the existing type and the existing arithmetic is reused.
 *
 * ## The synthesised trace, and what is honest about it
 *
 * [TaskScore] wants a [VerifiedTrace]. The device runner does not export one —
 * it records the *outcome*, the wall clock, the model calls and the step count
 * from the gateway's `TASK_END` marker, because a clean replay writes no trace at
 * all (E24b) and the database therefore cannot be the source.
 *
 * The trace built here carries those real numbers and **placeholder steps**: the
 * count is real, the contents are not. That is safe only because the metrics
 * computed from it use `steps.size` and never a step's contents — stated
 * explicitly because a synthesised trace that leaked into anything reading
 * `action` or `preOk` would be fabricated evidence rather than a shim.
 *
 * `oraclePassed` is the one field that decides success, and it comes from the
 * runner's **external** check (`dumpsys` + `uiautomator dump`), never from the
 * agent's self-report.
 */
object CorpusResults {

    /** One row of the runner's CSV. */
    data class Row(
        val task: String,
        val config: String,
        val outcome: String,
        val oraclePass: Boolean?,
        val wallMs: Long,
        /** The agent's own measure of the task, or null if it died before reporting. */
        val agentMs: Long?,
        /**
         * Nullable on purpose: a process killed before emitting `TASK_END`
         * reported nothing, and 0 would read as "made no model calls" — exactly
         * what a free replay looks like.
         */
        val llmCalls: Int?,
        val steps: Int?,
        val killed: Boolean,
        val detail: String,
    ) {
        /** Confirmation-gated tasks were never attempted; §16 forbids it unattended. */
        val gated: Boolean get() = outcome == "GATED_CONFIRMATION"
    }

    /**
     * Parse by **header name**, never by column position.
     *
     * The runner gained an `agent_ms` column and every positional index after it
     * shifted by one, so `llm_calls` silently read the agent's duration and the
     * report printed an LLM-calls-per-task of 263,987. Absurd enough to notice
     * here; a column added *after* `llm_calls` would have shifted only `killed`
     * and produced a plausible wrong number instead.
     */
    fun parse(csv: File): List<Row> {
        val lines = csv.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val header = splitCsv(lines.first()).map { it.trim() }
        fun idx(name: String) = header.indexOf(name)

        val iTask = idx("task"); val iConfig = idx("config"); val iOutcome = idx("outcome")
        val iOracle = idx("oracle_pass"); val iWall = idx("wall_ms"); val iAgent = idx("agent_ms")
        val iLlm = idx("llm_calls"); val iSteps = idx("steps"); val iKilled = idx("killed")
        val iDetail = idx("detail")

        return lines.drop(1).map { line ->
            val f = splitCsv(line)
            fun at(i: Int) = if (i >= 0) f.getOrElse(i) { "" } else ""
            Row(
                task = at(iTask),
                config = at(iConfig),
                outcome = at(iOutcome),
                oraclePass = at(iOracle).toIntOrNull()?.let { it == 1 },
                wallMs = at(iWall).toLongOrNull() ?: 0L,
                agentMs = at(iAgent).toLongOrNull(),
                llmCalls = at(iLlm).toIntOrNull(),
                steps = at(iSteps).toIntOrNull(),
                killed = at(iKilled) == "1",
                detail = at(iDetail),
            )
        }
    }

    /** Minimal CSV split honouring the one quoted field the runner emits. */
    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        for (c in line) {
            when {
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out
    }

    /**
     * The attempt that counts, per task: the last non-killed one if any exists.
     *
     * `PROCESS_KILLED` is a **lost sample, not a task failure**. E6b showed the
     * OS reaps the app in batches and that survival is sweep overlap rather than
     * anything the agent did, so scoring a kill as a failure attributes a
     * platform property to the model. A task retried until it completed is
     * scored on the completion; a task killed on every attempt has **no score**
     * and is `skipped`, which removes it from the denominator rather than
     * counting it wrong in either direction.
     *
     * Every attempt stays in the CSV. This chooses what to score; it does not
     * edit the record, and [killAttempts] reports what the coverage cost was.
     */
    fun scored(rows: List<Row>): List<Row> =
        rows.groupBy { it.task }.values.map { attempts ->
            attempts.lastOrNull { it.outcome != "PROCESS_KILLED" } ?: attempts.last()
        }

    /** How many attempts the OS killed, across every task. */
    fun killAttempts(rows: List<Row>): Int = rows.count { it.killed }

    fun scores(csv: File): List<TaskScore> {
        val byId = BenchCorpus.ALL.associateBy { it.id }
        return scored(parse(csv)).mapNotNull { row ->
            val task = byId[row.task] ?: return@mapNotNull null
            TaskScore(
                task = task,
                trace = VerifiedTrace(
                    traceId = "device-${row.task}-${row.config}",
                    goal = task.goal,
                    // Placeholder steps: the COUNT is real, the contents are not.
                    // Nothing downstream reads a step's contents — see the class
                    // comment.
                    steps = List(row.steps ?: 0) { placeholderStep() },
                    outcome = runCatching { TaskOutcome.valueOf(row.outcome) }
                        .getOrDefault(TaskOutcome.ERROR),
                    llmCalls = row.llmCalls ?: 0,
                    totalMs = row.wallMs,
                    device = "tecno-ck6n",
                    model = "gemma-3-1b-it-Q4_K_M",
                    config = row.config,
                ),
                // The external oracle, not the agent's opinion of itself.
                oraclePassed = row.oraclePass == true,
                // Gated tasks were never attempted (§16); tasks the OS killed on
                // every attempt have no measurement. Both leave the denominator
                // rather than being scored as failures.
                skipped = row.gated || row.outcome == "PROCESS_KILLED",
            )
        }
    }

    private fun placeholderStep() = TraceStep(
        action = DeviceAction.Wait(PostCondition(PostConditionType.NODE_PRESENT, "-")),
        preOk = true,
        postOk = true,
        latencyMs = 0,
    )
}

/** Render a device corpus run. `./gradlew :bench:report -Pcsv=<file>[,<file>…]` */
object RunCorpusReport {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("usage: RunCorpusReport <results.csv> [more.csv …]")
            return
        }

        println("AXON-Bench — DEVICE CORPUS RESULTS (§14.2)")
        println("=".repeat(96))

        for (path in args) {
            val file = File(path)
            if (!file.exists()) {
                println("missing: $path"); continue
            }
            val rows = CorpusResults.parse(file)
            val kills = CorpusResults.killAttempts(rows)
            val scores = CorpusResults.scores(file)
            val config = rows.firstOrNull()?.config ?: "?"
            val m = BenchMetrics.of(config, scores)

            println()
            println("── ${file.name}  (config $config) " + "─".repeat(40))
            println(m.render())
            println("  gated by §16 (never attempted) : ${rows.count { it.gated }}")
            println("  attempts killed by the OS      : $kills of ${rows.size}")
            println("  tasks with no surviving attempt: " +
                CorpusResults.scored(rows).count { it.outcome == "PROCESS_KILLED" })
            println()
            println("  " + "task".padEnd(24) + "oracle".padEnd(9) + "outcome".padEnd(18) +
                "wall".padEnd(10) + "llm")
            println("  " + "-".repeat(70))
            for (r in rows) {
                println(
                    "  " + r.task.padEnd(24) +
                        (if (r.gated) "—" else if (r.oraclePass == true) "PASS" else "fail").padEnd(9) +
                        r.outcome.padEnd(18) +
                        (if (r.gated) "—" else "${r.wallMs / 1000}s").padEnd(10) +
                        (if (r.gated) "—" else r.llmCalls?.toString() ?: "?"),
                )
            }
        }
        println()
        println("=".repeat(96))
        println("Oracles were evaluated externally (dumpsys + uiautomator dump), never from")
        println("the agent's own trace. Gated tasks are EXCLUDED from the denominator: §16")
        println("requires a human to approve an irreversible action, so they were not")
        println("attempted, and scoring them as failures would penalise the safety property.")
    }
}
