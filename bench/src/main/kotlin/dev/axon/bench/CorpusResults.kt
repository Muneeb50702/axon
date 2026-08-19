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
        val llmCalls: Int,
        val steps: Int,
        val killed: Boolean,
        val detail: String,
    ) {
        /** Confirmation-gated tasks were never attempted; §16 forbids it unattended. */
        val gated: Boolean get() = outcome == "GATED_CONFIRMATION"
    }

    fun parse(csv: File): List<Row> =
        csv.readLines()
            .drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val f = splitCsv(line)
                Row(
                    task = f.getOrElse(0) { "" },
                    config = f.getOrElse(1) { "" },
                    outcome = f.getOrElse(3) { "" },
                    oraclePass = f.getOrElse(4) { "" }.toIntOrNull()?.let { it == 1 },
                    wallMs = f.getOrElse(5) { "" }.toLongOrNull() ?: 0L,
                    llmCalls = f.getOrElse(6) { "" }.toIntOrNull() ?: 0,
                    steps = f.getOrElse(7) { "" }.toIntOrNull() ?: 0,
                    killed = f.getOrElse(8) { "" } == "1",
                    detail = f.getOrElse(9) { "" },
                )
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

    fun scores(csv: File): List<TaskScore> {
        val byId = BenchCorpus.ALL.associateBy { it.id }
        return parse(csv).mapNotNull { row ->
            val task = byId[row.task] ?: return@mapNotNull null
            TaskScore(
                task = task,
                trace = VerifiedTrace(
                    traceId = "device-${row.task}-${row.config}",
                    goal = task.goal,
                    // Placeholder steps: the COUNT is real, the contents are not.
                    // Nothing downstream reads a step's contents — see the class
                    // comment.
                    steps = List(row.steps) { placeholderStep() },
                    outcome = runCatching { TaskOutcome.valueOf(row.outcome) }
                        .getOrDefault(TaskOutcome.ERROR),
                    llmCalls = row.llmCalls,
                    totalMs = row.wallMs,
                    device = "tecno-ck6n",
                    model = "gemma-3-1b-it-Q4_K_M",
                    config = row.config,
                ),
                // The external oracle, not the agent's opinion of itself.
                oraclePassed = row.oraclePass == true,
                skipped = row.gated,
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
            val scores = CorpusResults.scores(file)
            val config = rows.firstOrNull()?.config ?: "?"
            val m = BenchMetrics.of(config, scores)

            println()
            println("── ${file.name}  (config $config) " + "─".repeat(40))
            println(m.render())
            println("  gated by §16 (never attempted) : ${rows.count { it.gated }}")
            println("  killed mid-task by the OS      : ${rows.count { it.killed }}")
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
                        (if (r.gated) "—" else "${r.llmCalls}"),
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
