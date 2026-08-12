package dev.axon.core.memory

import dev.axon.core.model.Goal
import dev.axon.core.model.StepOutcome
import dev.axon.core.model.TaskOutcome
import dev.axon.core.model.TaskResult
import dev.axon.core.model.TraceStep
import dev.axon.core.model.VerifiedTrace

/**
 * Turns a completed run into an episodic record (spec §7.8, §10.5).
 *
 * A trace is doing three jobs at once, which is why this exists as its own step
 * rather than being a field on [TaskResult]:
 *
 * 1. **Compiler input (C1′)** — a clean successful trace is what
 *    [dev.axon.core.skills.SkillCompiler] freezes into a skill.
 * 2. **Evaluation record (C5)** — every §14.2 metric is computed from traces,
 *    so a benchmark run *is* a set of these and the results table is a fold.
 * 3. **Audit log (§16)** — the user must be able to see everything the agent
 *    did. Same data, so it is stored once rather than duplicated into a logging
 *    path that could drift from what actually happened.
 *
 * The third is why nothing is filtered here. A rejected action, a failed
 * assertion and a heal all belong in the record even though none of them will
 * ever be compiled: an audit log that only contains successes is not an audit
 * log.
 */
public class TraceRecorder(
    private val device: String,
    private val model: String,
    private val newId: () -> String,
    private val nowMs: () -> Long,
) {

    /**
     * Record a finished run.
     *
     * @param config which §14.3 ablation arm produced this, so one store can
     *   hold every arm's runs and the results table can group by it. Defaults to
     *   `D` — the full system — which is what an interactive run is.
     */
    public fun record(result: TaskResult, config: String = "D"): VerifiedTrace = VerifiedTrace(
        traceId = newId(),
        goal = result.goal.utterance,
        params = result.goal.params,
        steps = result.steps.map(::toTraceStep),
        outcome = result.outcome,
        llmCalls = result.llmCalls,
        totalMs = result.totalMs,
        device = device,
        model = model,
        config = config,
        startedAtMs = nowMs() - result.totalMs,
    )

    private fun toTraceStep(outcome: StepOutcome) = TraceStep(
        action = outcome.action,
        preOk = outcome.preOk,
        // `postOk == null` means the post-condition was never evaluated — the
        // action was gated or refused before it ran. Recorded as `false` on the
        // trace because a trace step is a claim about what happened, and nothing
        // happened; `isCompilable` then correctly excludes the whole trace.
        postOk = outcome.postOk == true,
        latencyMs = outcome.latencyMs,
        healed = false,
        llmCalls = 0,
        stateHashBefore = outcome.stateHashBefore,
        stateHashAfter = outcome.stateHashAfter,
        failureReason = failureOf(outcome),
    )

    private fun failureOf(outcome: StepOutcome): String? = when {
        outcome.committed -> null
        !outcome.preOk -> (outcome.actResult as? dev.axon.core.model.ActResult.Failed)?.reason
            ?: "rejected before dispatch"
        else -> (outcome.verifyResult as? dev.axon.core.model.VerifyResult.Mismatch)?.observed
    }
}

/**
 * Append-only store of execution traces (§7.8 episodic memory).
 *
 * In-memory here; SQLite in Phase 6 per §11. The interface is what matters at
 * this stage, because both the compiler and the benchmark harness read through
 * it and neither should care where the rows live.
 */
public interface TraceStore {
    public suspend fun append(trace: VerifiedTrace)

    /** Traces for a goal, newest first — the compiler's input for refinement. */
    public suspend fun forGoal(goal: String): List<VerifiedTrace>

    public suspend fun all(): List<VerifiedTrace>

    /**
     * Clean successful traces, which are the only ones worth compiling.
     *
     * Filtering here rather than in the compiler means the caller cannot
     * accidentally hand it a run that limped to the goal through failed steps —
     * freezing one of those compiles the mistakes in and replays them forever.
     */
    public suspend fun compilable(): List<VerifiedTrace> =
        all().filter { it.isCompilable }
}

public class InMemoryTraceStore : TraceStore {
    private val traces = mutableListOf<VerifiedTrace>()

    override suspend fun append(trace: VerifiedTrace) {
        traces += trace
    }

    override suspend fun forGoal(goal: String): List<VerifiedTrace> =
        traces.filter { it.goal.equals(goal, ignoreCase = true) }.reversed()

    override suspend fun all(): List<VerifiedTrace> = traces.toList()
}

/**
 * Decides whether a finished run is worth compiling into a skill.
 *
 * §7.7 says a verified trace is "offered/auto compiled" to a skill. Auto is
 * right for repeated tasks and wrong for one-offs: compiling every successful
 * run would fill the store with single-use skills that dilute matching and make
 * a false match more likely — and a false match replays the wrong thing against
 * a live device.
 *
 * So compilation waits for evidence of repetition. The second clean run of the
 * same goal is what triggers it, which also means the compiler gets **two**
 * traces and can infer slots by diffing them (§7.7) rather than guessing from
 * one.
 */
public class CompilationPolicy(
    private val minCleanRuns: Int = 2,
) {
    public suspend fun shouldCompile(goal: Goal, store: TraceStore): Boolean {
        val clean = store.forGoal(goal.utterance).count { it.isCompilable }
        return clean >= minCleanRuns
    }

    /** Was this run itself clean enough to count toward the threshold? */
    public fun isCleanRun(result: TaskResult): Boolean =
        result.outcome == TaskOutcome.SUCCESS &&
            result.steps.isNotEmpty() &&
            result.steps.all { it.committed }
}
