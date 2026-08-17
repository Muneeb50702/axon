package dev.axon.bench

import dev.axon.core.driver.DeviceDriver
import dev.axon.core.model.Goal
import dev.axon.core.model.PostCondition
import dev.axon.core.model.VerifiedTrace
import dev.axon.core.verifier.PostConditionEvaluator

/**
 * Runs the AXON-Bench corpus under one configuration — contribution **C5**.
 *
 * ## Why the runner takes a lambda instead of building an agent
 *
 * A benchmark harness that constructed its own runtime would be a *second*
 * assembly of the system, and the §14.3 ablation would then compare two
 * programs rather than two configurations. Worse, the thing measured would be
 * the harness's agent, not the one that ships.
 *
 * So [execute] is supplied by the caller: on device it is the real
 * `AxonRuntime` wired exactly as `AxonAgent` wires it, and in CI it is a
 * scripted stand-in. Same seam as `DeviceDriver`, same reason.
 *
 * ## The oracle is evaluated here, by the same evaluator the verifier uses
 *
 * A task's verdict is **not** `trace.outcome` — that is the loop's opinion of
 * itself, and measuring it would measure the self-assessment C2 exists to
 * replace. After the run, the harness observes the device and checks the task's
 * own post-conditions with [PostConditionEvaluator], the identical code path the
 * runtime uses for its own assertions. Sharing it means the benchmark cannot
 * quietly hold the system to a different standard than it holds itself.
 */
public class BenchRunner(
    private val driver: DeviceDriver,

    /**
     * Run one goal and return its trace.
     *
     * Throwing is allowed and expected — a crashed task scores as a failure
     * rather than taking the run down, because an arm that dies on task three of
     * twenty should still report the two it managed.
     */
    private val execute: suspend (Goal) -> VerifiedTrace,

    /**
     * Which apps are installed, for the skip rule.
     *
     * A task whose app is absent says nothing about the agent, and counting it
     * as a failure would make TSR a function of the handset's app list rather
     * than of the configuration under test.
     */
    private val installedApps: Set<String> = emptySet(),

    /** Called after each task, so a long run reports progress rather than hanging. */
    private val onProgress: (TaskScore) -> Unit = {},
) {

    /**
     * Score one task.
     *
     * Never throws: a task that blows up is a failed task, with the exception
     * recorded as the trace's own failure. On this hardware the OEM power
     * manager terminates sustained compute (E6), so partial runs are the normal
     * case and every case that *did* finish has to survive the one that did not.
     */
    public suspend fun runTask(task: BenchTask): TaskScore {
        if (task.requiredApps.any { it !in installedApps } && installedApps.isNotEmpty()) {
            return TaskScore(
                task = task,
                trace = emptyTrace(task, "skipped: required app not installed"),
                oraclePassed = false,
                skipped = true,
            )
        }

        val trace = runCatching { execute(Goal(task.goal, params = task.params)) }
            .getOrElse { return TaskScore(task, emptyTrace(task, "threw: ${it.message}"), false) }

        // The benchmark's verdict, taken from the device rather than the trace.
        val oracleHeld = runCatching { checkOracle(task.successOracle) }.getOrDefault(false)

        val score = TaskScore(
            task = task,
            trace = trace,
            oraclePassed = oracleHeld,
            // Deliberately left at zero: **the runner cannot compute the
            // valid-action rate and must not appear to.**
            //
            // §14.2 defines it as the share of *generations* that were
            // syntactically and enumeratively valid — the C3 number. A trace
            // cannot supply it, because a malformed generation never becomes a
            // step: it fails to parse, the planner discards it, and nothing
            // reaches the executor. So `steps.size` counts only the generations
            // that already succeeded, and a rate computed from it would be 100%
            // for every arm including the unconstrained one whose entire purpose
            // is to emit malformed output.
            //
            // An earlier version of this line used `steps.count { it.preOk }`,
            // which is the *precondition gate* pass rate — a different quantity
            // measuring whether the named element existed, not whether the JSON
            // was well-formed. It would have reported a plausible number for the
            // wrong thing, which is worse than reporting none.
            //
            // The figure has to come from the planner, which sees the raw
            // generations. E4b measures it that way in instrumentation and it is
            // the caller's job to pass it in; [BenchMetrics] treats a zero
            // denominator as "not measured" rather than as 0%.
            actionsEmitted = 0,
            validActions = 0,
        )
        onProgress(score)
        return score
    }

    /** Score a whole tier, or the whole corpus. */
    public suspend fun run(tasks: List<BenchTask>): List<TaskScore> = tasks.map { runTask(it) }

    /**
     * Does the task's success oracle hold on the device *now*?
     *
     * Every condition must hold, not any: a task with two post-conditions has
     * two claims about what "done" means, and satisfying one of them is not
     * finishing the task.
     */
    private suspend fun checkOracle(oracle: List<PostCondition>): Boolean {
        if (oracle.isEmpty()) return false
        val tree = driver.observe()
        return oracle.all { PostConditionEvaluator.evaluate(it, tree) }
    }

    private fun emptyTrace(task: BenchTask, why: String) = VerifiedTrace(
        traceId = "bench-${task.id}",
        goal = task.goal,
        params = task.params,
        steps = emptyList(),
        outcome = dev.axon.core.model.TaskOutcome.ERROR,
        llmCalls = 0,
        totalMs = 0,
        device = driver.deviceFamily,
        model = "n/a",
        config = why,
    )
}
