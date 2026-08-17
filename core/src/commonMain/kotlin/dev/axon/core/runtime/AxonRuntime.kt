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
import dev.axon.core.skills.ComposedPlan
import dev.axon.core.skills.CompileResult
import dev.axon.core.skills.SkillComposer
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
        val replayer = SkillReplayer(
            driver = driver,
            executor = executor,
            nowMs = nowMs,
            // The planner is available for per-step repair, but only for steps
            // whose llm_fallback allows it. A clean replay never touches it.
            planner = planner,
        )

        // ---- COMPOSE, if every part of a compound goal is already known ------
        //
        // Tried before the single-skill match because a compound goal will not
        // match one skill anyway, and after it would mean planning the whole
        // thing first. E24: nine steps of cold planning is ~9 minutes on this
        // hardware — past the ~7-minute ceiling the OEM power manager allows
        // (E6) — so a compound task is not slow here, it is impossible. Composed
        // from known skills it costs milliseconds.
        SkillComposer(skills).plan(goal)?.let { composed ->
            val outcome = runComposed(composed, replayer)
            if (outcome != null) return outcome
            // A composed run that failed part-way falls through to the planner
            // with the *original* goal, for the same reason a failed single
            // replay does: the skills were compiled against a UI that has since
            // changed, and the planner can still do the task, slowly.
        }

        // ---- REPLAY, if a skill matches --------------------------------------
        skills.match(goal)?.let { match ->
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
     * Replay a composed plan, or `null` if any part of it failed (E24).
     *
     * Steps run in the order the user said them and **stop at the first
     * failure**. A compound request is a sequence, not a set: "turn on wifi then
     * message Ammi" with the Wi-Fi step failed means the message would be sent
     * over a connection the user asked to have working. Carrying on would
     * substitute AXON's judgement about which parts matter for the user's, which
     * is the whole thing this project declines to do.
     *
     * The composed result is reported as one task, with the steps of every part
     * concatenated. That keeps §14.2's metrics meaningful — a composed run of
     * two three-step skills is a six-step task, not two tasks — and it keeps the
     * §16 audit log reading the way the user experienced it: one request, one
     * record.
     */
    private suspend fun runComposed(
        plan: ComposedPlan,
        replayer: SkillReplayer,
    ): RunOutcome? {
        val steps = mutableListOf<dev.axon.core.model.StepOutcome>()
        var totalMs = 0L
        var repaired = 0
        var llmCalls = 0

        for (part in plan.steps) {
            val replay = replayer.replay(part.match.skill, part.goal, part.match.params)
            skills.recordReplay(part.match.skill.manifest.id, replay.repairedSteps > 0)

            steps += replay.result.steps
            totalMs += replay.result.totalMs
            repaired += replay.repairedSteps
            // Summed from each part's own result rather than from its steps:
            // StepOutcome does not carry a call count, and inferring one from
            // step kinds would be a second, guessable definition of the number
            // C1′ rests on.
            llmCalls += replay.result.llmCalls

            // Stop at the first failure. The caller falls back to planning the
            // *original* goal, which re-does the parts that already succeeded —
            // wasteful, and correct: a partially-applied compound task is a
            // state the planner should be allowed to observe and finish from,
            // not one this method should try to repair by guessing.
            if (replay.result.outcome != TaskOutcome.SUCCESS) return null
        }

        return RunOutcome(
            result = dev.axon.core.model.TaskResult(
                goal = plan.goal,
                steps = steps,
                outcome = TaskOutcome.SUCCESS,
                // The C1′ number, and it must stay honest: a composed run costs
                // whatever its parts cost, which is zero unless a step needed
                // repair.
                llmCalls = llmCalls,
                totalMs = totalMs,
                servedBySkill = plan.steps.joinToString("+") { it.match.skill.manifest.id },
            ),
            path = if (repaired > 0) ExecutionPath.REPLAY_WITH_REPAIR else ExecutionPath.REPLAY,
            compiled = null,
        )
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
