package dev.axon.core.runtime

import dev.axon.core.driver.DeviceDriver
import dev.axon.core.executor.DefaultExecutor
import dev.axon.core.memory.CompilationPolicy
import dev.axon.core.memory.TraceRecorder
import dev.axon.core.memory.TraceStore
import dev.axon.core.model.Goal
import dev.axon.core.model.TaskOutcome
import dev.axon.core.model.TaskResult
import dev.axon.core.planner.Planner
import dev.axon.core.skills.CompileResult
import dev.axon.core.skills.SkillCompiler
import dev.axon.core.skills.SkillReplayer
import dev.axon.core.skills.SkillStore

/**
 * The complete §7.2 control loop, including the REPLAY/PLAN branch.
 *
 * ```
 * receive goal
 *   ├─ skill store hit?  ──YES──▶ REPLAY   (deterministic, 0 LLM calls)
 *   │                     ──NO───▶ PLAN    (DefaultAgentRuntime)
 *   └─ on a clean success: record trace → maybe compile to a skill (C1′)
 * ```
 *
 * [DefaultAgentRuntime] implements the PLAN path. This wraps it with the two
 * things that make the system *learn*: the lookup that lets a repeated task skip
 * the model entirely, and the recording that lets a successful run become the
 * skill a future request will hit.
 *
 * ## The loop closes here
 *
 * Everything before this was one half or the other. A planner that never records
 * cannot improve; a replayer with an empty store never fires. Together they give
 * the property §7.7 claims: *the same task, asked twice, costs ~60 s the first
 * time and milliseconds the second.* On hardware where a planning step costs
 * ~83 J (E15), that is also the difference between ~138 tasks per charge and
 * effectively unbounded.
 *
 * ## Compilation waits for repetition, deliberately
 *
 * §7.7 permits auto-compiling every verified trace. [CompilationPolicy] requires
 * two clean runs instead, for two reasons: compiling one-offs fills the store
 * with single-use skills that dilute matching and make a false match more likely,
 * and waiting for a second run gives the compiler two traces to diff — which is
 * how slots are inferred from evidence rather than heuristics.
 */
public class AxonRuntime(
    private val driver: DeviceDriver,

    /**
     * The planner, or `null` when no model is loaded (E22d).
     *
     * Nullable because the replay path does not need one, and on this hardware
     * that distinction is worth ~40 seconds. The gateway used to load the
     * 806 MB GGUF before every task, including tasks a compiled skill could
     * serve without consulting it — so a run that cost **zero model calls**
     * still cost a full model load, and C1′'s benefit was thrown away at the
     * app layer while the measurement inside the loop looked perfect.
     *
     * A `null` planner is therefore a legitimate configuration meaning "replay
     * only". [execute] refuses to fall through to PLAN with one, rather than
     * failing later and less clearly.
     */
    private val planner: Planner?,
    private val executor: DefaultExecutor,
    private val skills: SkillStore,
    private val traces: TraceStore,
    private val compiler: SkillCompiler,
    private val recorder: TraceRecorder,
    private val nowMs: () -> Long,
    private val compilationPolicy: CompilationPolicy = CompilationPolicy(),
    private val goalReached: suspend (dev.axon.core.model.CompactState) -> Boolean = { false },
) {

    /**
     * Run [goal], taking whichever path is available.
     *
     * @return the result plus which path served it, so §14.2 can report LLM
     *   calls by path — the number that makes C1′ a measurement.
     */
    public suspend fun execute(goal: Goal): RunOutcome {
        // ---- REPLAY, if a skill matches -------------------------------------
        skills.match(goal)?.let { match ->
            val replayer = SkillReplayer(
                driver = driver,
                executor = executor,
                nowMs = nowMs,
                // The planner is available for per-step repair, but only for
                // steps whose llm_fallback allows it. A clean replay never
                // touches it.
                planner = planner,
            )

            val replay = replayer.replay(match.skill, goal, match.params)
            skills.recordReplay(match.skill.manifest.id, replay.repairedSteps > 0)

            if (replay.result.outcome == TaskOutcome.SUCCESS) {
                return RunOutcome(replay.result, replay.path, compiled = null)
            }

            // A failed replay falls through to a cold plan rather than giving up.
            // The skill was compiled against a UI that has since changed, and the
            // planner can still do the task — slowly. §17 frames drift as
            // something to recover from, not something that breaks the feature.
        }

        // ---- PLAN -----------------------------------------------------------
        //
        // Reached either because no skill matched, or because a matched skill's
        // replay failed. With no planner there is nothing further to try, and
        // saying so plainly beats a null-pointer three frames down.
        val planner = planner ?: error(
            "no skill matched '${goal.utterance}' and no planner is available — " +
                "the model must be loaded to plan",
        )

        val runtime = DefaultAgentRuntime(
            driver = driver,
            planner = planner,
            executor = executor,
            nowMs = nowMs,
            goalReached = goalReached,
        )
        val result = runtime.execute(goal)

        // ---- record, and maybe learn ----------------------------------------
        val trace = recorder.record(result)
        traces.append(trace)

        var compiled: String? = null
        if (compilationPolicy.isCleanRun(result) && compilationPolicy.shouldCompile(goal, traces)) {
            compiled = compile(goal)
        }

        return RunOutcome(result, ExecutionPath.PLAN, compiled)
    }

    /**
     * Compile — or refine — a skill from the clean traces for this goal.
     *
     * Refinement is preferred when a skill already exists, because a second
     * trace of the same goal is *evidence* about which positions were variable,
     * where single-trace inference is a heuristic (§7.7).
     */
    private suspend fun compile(goal: Goal): String? {
        val clean = traces.forGoal(goal.utterance).filter { it.isCompilable }
        if (clean.isEmpty()) return null

        val newest = clean.first()
        val existing = skills.all().firstOrNull { it.manifest.goalPattern.isMatchFor(goal.utterance) }

        val result = if (existing != null) {
            compiler.refine(existing, newest)
        } else {
            compiler.compile(newest)
        }

        return when (result) {
            is CompileResult.Compiled -> {
                skills.save(result.skill)
                result.skill.manifest.id
            }
            // A refusal is not an error. Most traces are not compilable, and the
            // run that produced this one already succeeded.
            is CompileResult.Rejected -> null
        }
    }

    private fun String.isMatchFor(utterance: String): Boolean {
        val literal = replace(Regex("""\{[a-z_][a-z0-9_]*\}"""), "").trim()
        return literal.isNotEmpty() && utterance.lowercase().contains(literal.split(" ").first())
    }
}

/** A completed run, with the path that served it. */
public data class RunOutcome(
    val result: TaskResult,
    val path: ExecutionPath,

    /** Skill id compiled or refined by this run, if any. */
    val compiled: String?,
) {
    /** Did this run cost no model calls at all? The C1′ headline, per run. */
    public val wasFree: Boolean get() = result.llmCalls == 0
}
