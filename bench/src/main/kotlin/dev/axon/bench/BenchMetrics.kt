package dev.axon.bench

import dev.axon.core.model.TaskOutcome
import dev.axon.core.model.VerifiedTrace
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The §14.2 metrics, computed from traces — contribution **C5**.
 *
 * Every figure in the results table folds over `VerifiedTrace`, which is the
 * record the runtime already writes for its own reasons (§7.8, §16). Nothing
 * here instruments the system specially, and that is the point: an evaluation
 * that needed its own measurement path could measure something the deployed
 * system does not do. A benchmark run *is* a set of traces, and the results
 * table is a fold over them.
 *
 * ## Two decisions worth arguing about
 *
 * **Success is the oracle's verdict, not the runtime's opinion.** A run whose
 * `outcome` is SUCCESS has only satisfied the loop's own termination test; the
 * benchmark asks whether the *task's* post-conditions hold, which is a different
 * question and the only one worth reporting. [TaskScore.oraclePassed] is
 * therefore supplied by whoever ran the task, and TSR counts that — never
 * `outcome`. Conflating them would let a system that declares victory early
 * score well, and self-declared success is exactly what C2 exists to replace.
 *
 * **Skipped tasks are excluded, not failed.** A task whose required app is not
 * installed says nothing about the agent. Counting it as a failure makes TSR a
 * function of which apps happen to be on the handset, and the comparison between
 * configs — which is the whole of §14.3 — stops being about the configs.
 */
@Serializable
data class BenchMetrics(
    @SerialName("config") val config: String,

    /** Tasks attempted — excludes skips. */
    val attempted: Int,
    val skipped: Int,

    /** §14.2 Task Success Rate: share of attempts whose oracle passed. */
    @SerialName("task_success_rate") val taskSuccessRate: Double,

    /**
     * §14.2 Step Efficiency: actual ÷ optimal, averaged over *successful* tasks.
     *
     * Failures are excluded because a task that gave up after one step would
     * otherwise score a flattering 0.33, and a config that fails more would look
     * more efficient. Efficiency is only meaningful for work that finished.
     *
     * 1.0 is optimal; above 1.0 means wasted steps. Below 1.0 should be
     * impossible and indicates a mis-counted `optimalSteps` in the task file.
     */
    @SerialName("step_efficiency") val stepEfficiency: Double?,

    /** §14.2 mean model invocations per task. The C1′ headline — ~0 on replay. */
    @SerialName("llm_calls_per_task") val llmCallsPerTask: Double,

    /**
     * §14.2 Valid-Action Rate: share of emitted actions that were well-formed.
     *
     * The C3 number. An action that the grammar could not have produced is one
     * the model produced *despite* it — so under config B this should be 100%
     * by construction, and a value below it means the grammar was not actually
     * installed (D9's failure mode: silently absent constraints).
     */
    @SerialName("valid_action_rate") val validActionRate: Double,

    /**
     * §14.2 Recovery Rate: share of failed steps that were healed — the C2 number.
     *
     * `null` when no step failed, which is not the same as 0%. A config that
     * never had to recover has no recovery rate, and reporting zero would make
     * a flawless run look like a broken one.
     */
    @SerialName("recovery_rate") val recoveryRate: Double?,

    /** §14.2 latency, milliseconds. */
    @SerialName("median_latency_ms") val medianLatencyMs: Long,
    @SerialName("p90_latency_ms") val p90LatencyMs: Long,

    /** Mean steps per successful task, for context on step efficiency. */
    @SerialName("mean_steps") val meanSteps: Double,
) {
    /** One line for the §14.3 results table. */
    public fun render(): String = buildString {
        append(config.padEnd(6))
        append("TSR ").append(pct(taskSuccessRate)).append("  ")
        append("valid ").append(pct(validActionRate)).append("  ")
        append("LLM/task ").append(fmt(llmCallsPerTask)).append("  ")
        append("steps ").append(stepEfficiency?.let { fmt(it) + "x" } ?: "—").append("  ")
        append("recovery ").append(recoveryRate?.let { pct(it) } ?: "n/a").append("  ")
        append("median ").append(medianLatencyMs / 1000).append("s")
        if (skipped > 0) append("  (").append(skipped).append(" skipped)")
    }

    private fun pct(v: Double) = "${(v * 1000).toInt() / 10.0}%".padEnd(6)
    private fun fmt(v: Double) = "${(v * 100).toInt() / 100.0}".padEnd(5)

    public companion object {

        /**
         * Fold a config's scored tasks into one row of the §14.3 table.
         *
         * Deliberately total: an empty run yields zeroes rather than throwing,
         * because an arm that was killed before its first case (E6 — the OEM
         * power manager terminates sustained compute) should appear in the table
         * as an arm with no data, not crash the aggregation of the arms that did
         * finish.
         */
        public fun of(config: String, scores: List<TaskScore>): BenchMetrics {
            val attempted = scores.filterNot { it.skipped }
            val succeeded = attempted.filter { it.oraclePassed }
            val latencies = attempted.map { it.trace.totalMs }.sorted()

            val actions = attempted.sumOf { it.actionsEmitted }
            val valid = attempted.sumOf { it.validActions }

            val failedSteps = attempted.sumOf { it.failedSteps }
            val healed = attempted.sumOf { it.trace.healedSteps }

            return BenchMetrics(
                config = config,
                attempted = attempted.size,
                skipped = scores.count { it.skipped },
                taskSuccessRate = ratio(succeeded.size, attempted.size),
                // Averaged over successes only — see the property's docs.
                stepEfficiency = succeeded
                    .filter { it.task.optimalSteps > 0 }
                    .map { it.trace.steps.size.toDouble() / it.task.optimalSteps }
                    .takeIf { it.isNotEmpty() }
                    ?.average(),
                llmCallsPerTask = attempted.map { it.trace.llmCalls.toDouble() }
                    .takeIf { it.isNotEmpty() }?.average() ?: 0.0,
                validActionRate = ratio(valid, actions),
                // null, not 0.0, when nothing failed — see the property's docs.
                recoveryRate = if (failedSteps == 0) null else ratio(healed, failedSteps),
                medianLatencyMs = latencies.quantile(0.50),
                p90LatencyMs = latencies.quantile(0.90),
                meanSteps = succeeded.map { it.trace.steps.size.toDouble() }
                    .takeIf { it.isNotEmpty() }?.average() ?: 0.0,
            )
        }

        private fun ratio(n: Int, d: Int) = if (d == 0) 0.0 else n.toDouble() / d

        /**
         * Nearest-rank quantile on a sorted list.
         *
         * Nearest-rank rather than interpolated, because these samples number in
         * the tens and an interpolated p90 of eleven values reports a latency no
         * run actually had. Every figure in the results table should be a thing
         * that happened.
         */
        private fun List<Long>.quantile(q: Double): Long {
            if (isEmpty()) return 0
            val rank = kotlin.math.ceil(q * size).toInt().coerceIn(1, size)
            return this[rank - 1]
        }
    }
}

/**
 * One task's outcome, as the harness observed it.
 *
 * Separate from [VerifiedTrace] because two of these fields are things the
 * *benchmark* knows and the runtime does not: whether the task's own oracle
 * passed, and whether the task was applicable to this device at all.
 */
public data class TaskScore(
    val task: BenchTask,
    val trace: VerifiedTrace,

    /**
     * Did the task's success oracle hold at the end?
     *
     * The benchmark's verdict, evaluated against the final UI — **not**
     * `trace.outcome`, which is the loop's opinion of itself. A run can
     * terminate believing it succeeded and fail the oracle; that gap is a
     * finding, and collapsing the two would hide it.
     */
    val oraclePassed: Boolean,

    /** Required app absent. Excluded from every rate, never counted as failure. */
    val skipped: Boolean = false,

    /** Actions the model emitted, including malformed ones. */
    val actionsEmitted: Int = 0,

    /** Of those, how many were schema-valid — the C3 numerator. */
    val validActions: Int = 0,
) {
    /** Steps that failed their gate or assertion — the recovery-rate denominator. */
    val failedSteps: Int
        get() = trace.steps.count { !it.preOk || !it.postOk }

    /**
     * Did the loop's own verdict agree with the oracle?
     *
     * Worth surfacing rather than deriving at analysis time. A run that reports
     * SUCCESS while the oracle fails is a **false positive**: the agent believed
     * it was done and was not. That is the single most important error class for
     * an agent that acts on a real device, and the count of them belongs in the
     * results table beside TSR rather than being reconstructible from it.
     */
    val selfAssessmentWrong: Boolean
        get() = (trace.outcome == TaskOutcome.SUCCESS) != oraclePassed
}
