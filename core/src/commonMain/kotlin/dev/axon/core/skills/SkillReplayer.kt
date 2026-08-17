package dev.axon.core.skills

import dev.axon.core.driver.DeviceDriver
import dev.axon.core.executor.DefaultExecutor
import dev.axon.core.model.CompiledSkill
import dev.axon.core.model.CompiledStep
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.DeviceKey
import dev.axon.core.model.Direction
import dev.axon.core.model.Goal
import dev.axon.core.model.PostCondition
import dev.axon.core.model.StepOutcome
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.model.TaskOutcome
import dev.axon.core.model.TaskResult
import dev.axon.core.planner.Planner
import dev.axon.core.runtime.ExecutionPath

/**
 * Replays a compiled skill with the model out of the loop (spec §7.7) — **C1′**.
 *
 * This is the path the whole contribution exists to make possible. A cold PLAN
 * run costs ~60 s and ~83 J *per step* on the target device (E2, E15); a replay
 * costs a gesture and an assertion. §7.7's claim — *"repeated tasks drop from N
 * LLM calls to 0"* — is not a figure of speech here, and [TaskResult.llmCalls]
 * is where it is measured.
 *
 * ## Every step still asserts
 *
 * Replay is not blind playback, and the difference is the point. Each frozen step
 * carries the post-condition that held when it was recorded, and that assertion
 * is evaluated on replay exactly as it was during the original run. A skill that
 * has stopped working *says so at the step that broke*, rather than driving
 * blindly through a changed UI.
 *
 * That is what makes a compiled skill safe to run unattended, and it is the
 * property a macro recorder does not have.
 *
 * ## The fallback boundary
 *
 * When an assertion fails and the step allows it ([CompiledStep.llmFallback]),
 * the planner is consulted **for that step only** — the rest of the skill still
 * replays deterministically. §7.7 frames UI drift as something to repair rather
 * than something that invalidates the skill, and §17 lists device/app
 * fragmentation as a high-likelihood risk whose mitigation is exactly this.
 *
 * Steps marked `llmFallback = false` are structurally fixed (launching a package,
 * pressing back). A failure there means something the model cannot fix by
 * guessing, and calling it would spend ~60 s re-deriving an action that was never
 * in doubt.
 */
public class SkillReplayer(
    private val driver: DeviceDriver,
    private val executor: DefaultExecutor,
    private val nowMs: () -> Long,

    /**
     * Consulted only when a step's assertion fails and that step permits it.
     *
     * Nullable on purpose: a replayer with no planner is a *fully deterministic*
     * executor, which is the configuration the §14.3 ablation needs in order to
     * measure replay in isolation, and the configuration a device with no model
     * loaded can still run.
     */
    private val planner: Planner? = null,
) {

    /**
     * Replay [skill] with [params] substituted into its parameterised steps.
     */
    public suspend fun replay(
        skill: CompiledSkill,
        goal: Goal,
        params: Map<String, String>,
    ): ReplayResult {
        val started = nowMs()
        val outcomes = mutableListOf<StepOutcome>()
        var llmCalls = 0
        var repairs = 0

        executor.reset()

        for (step in skill.steps) {
            val action = materialise(step, params)
                ?: return ReplayResult(
                    result = TaskResult(
                        goal = goal,
                        outcome = TaskOutcome.ERROR,
                        steps = outcomes,
                        llmCalls = llmCalls,
                        totalMs = nowMs() - started,
                        servedBySkill = skill.manifest.id,
                    ),
                    path = ExecutionPath.REPLAY,
                    repairedSteps = repairs,
                    failedAtStep = step.step,
                )

            val tree = driver.observe()
            var outcome = executor.run(action, tree)

            if (!outcome.committed && step.llmFallback && planner != null) {
                // UI drift. Repair this step, keep replaying the rest.
                repairs++
                val repaired = repairStep(step, goal, params)
                if (repaired != null) {
                    llmCalls += repaired.llmCalls
                    outcome = executor.run(repaired.action, driver.observe())
                }
            }

            outcomes += outcome

            if (!outcome.committed) {
                return ReplayResult(
                    result = TaskResult(
                        goal = goal,
                        outcome = TaskOutcome.ESCALATED,
                        steps = outcomes,
                        llmCalls = llmCalls,
                        totalMs = nowMs() - started,
                        servedBySkill = skill.manifest.id,
                    ),
                    path = if (repairs > 0) ExecutionPath.REPLAY_WITH_REPAIR else ExecutionPath.REPLAY,
                    repairedSteps = repairs,
                    failedAtStep = step.step,
                )
            }
        }

        return ReplayResult(
            result = TaskResult(
                goal = goal,
                outcome = TaskOutcome.SUCCESS,
                steps = outcomes,
                // The headline. Zero unless a step needed repair.
                llmCalls = llmCalls,
                totalMs = nowMs() - started,
                servedBySkill = skill.manifest.id,
            ),
            path = if (repairs > 0) ExecutionPath.REPLAY_WITH_REPAIR else ExecutionPath.REPLAY,
            repairedSteps = repairs,
            failedAtStep = null,
        )
    }

    /**
     * Turn a frozen step into a concrete action, substituting parameters.
     *
     * Returns `null` when a required parameter is missing — a caller that
     * invoked "send {message} to {contact}" without a contact gets a clean
     * failure rather than a tap on the literal string "{contact}".
     */
    private fun materialise(step: CompiledStep, params: Map<String, String>): DeviceAction? {
        val selector = step.selector?.let { s ->
            val slot = step.bindings["selector"]
            val value = if (slot != null) params[slot] ?: return null else s.value
            Target(s.by, value)
        }

        // Slot-bound text wins over the compiled literal, so a parameterised
        // skill types the caller's value; the literal is what makes a skill
        // compiled from an unparameterised run replayable at all.
        val text = step.bindings["text"]?.let { params[it] ?: return null }
            ?: step.args["text"]

        // Payload lookup, with the pre-E22c location as a fallback.
        //
        // Skills compiled before `args` existed stored nothing here, and skills
        // hand-written for the measurement harness put the payload in the
        // selector. Reading both means neither has to be migrated, and a skill
        // that genuinely lacks a payload still fails closed below.
        fun arg(name: String): String? = step.args[name] ?: step.selector?.value

        return when (step.action) {
            "tap" -> selector?.let { DeviceAction.Tap(it, step.expect) }
            "long_press" -> selector?.let { DeviceAction.LongPress(it, step.expect) }
            "input_text" -> selector?.let {
                DeviceAction.InputText(it, text ?: return null, step.expect)
            }
            "launch_app" -> DeviceAction.LaunchApp(arg("app") ?: return null, step.expect)

            // These three used to *default* when the payload was missing — to
            // BACK, UP and DOWN respectively — which meant a skill that had lost
            // its payload replayed a confidently wrong action against a live
            // device. A missing payload is now a failed materialisation, so the
            // step stops and can be repaired or escalated. Refusing to act beats
            // guessing which key the user meant.
            "press_key" -> arg("key")
                ?.let { name -> runCatching { DeviceKey.valueOf(name.uppercase()) }.getOrNull() }
                ?.let { DeviceAction.PressKey(it, step.expect) }

            "swipe" -> arg("direction")
                ?.let { d -> runCatching { Direction.valueOf(d.uppercase()) }.getOrNull() }
                ?.let { DeviceAction.Swipe(it, target = selector, expect = step.expect) }

            "scroll" -> arg("direction")
                ?.let { d -> runCatching { Direction.valueOf(d.uppercase()) }.getOrNull() }
                ?.let { DeviceAction.Scroll(it, target = selector, expect = step.expect) }

            "wait" -> DeviceAction.Wait(
                step.expect,
                timeoutMs = step.args["timeout_ms"]?.toLongOrNull()
                    ?: DeviceAction.Wait.DEFAULT_WAIT_MS,
            )
            else -> null
        }
    }

    private suspend fun repairStep(
        step: CompiledStep,
        goal: Goal,
        params: Map<String, String>,
    ): dev.axon.core.planner.PlanDecision? {
        val p = planner ?: return null
        val tree = driver.observe()
        val state = dev.axon.core.model.CompactState.from(tree)

        return runCatching {
            p.nextAction(
                state = state,
                // The goal is narrowed to *this step's* intent, not the whole
                // task. Handing the planner the original goal mid-skill would
                // invite it to re-plan the entire task from a screen halfway
                // through one, and the repair would compete with the skill rather
                // than serve it.
                goal = goal.copy(utterance = stepIntent(step, goal), stepBudget = 1),
                menu = dev.axon.core.model.ActionMenu.forScreen(state),
            )
        }.getOrNull()
    }

    /** A one-line description of what this step was trying to achieve. */
    private fun stepIntent(step: CompiledStep, goal: Goal): String = buildString {
        append("as part of \"").append(goal.utterance).append("\", ")
        append("make this true: ").append(step.expect.describe())
        step.selector?.let { append(" (originally by ").append(it.by.name.lowercase()).append("=\"").append(it.value).append("\")") }
    }
}

/** Outcome of a replay, with the accounting §14.2 needs. */
public data class ReplayResult(
    val result: TaskResult,
    val path: ExecutionPath,

    /** Steps that needed the planner — the UI-drift rate for this run. */
    val repairedSteps: Int,

    /** Step number that ended the replay, or null on success. */
    val failedAtStep: Int?,
) {
    /** Did the whole skill replay with no model involvement at all? */
    public val wasFullyDeterministic: Boolean
        get() = result.llmCalls == 0 && repairedSteps == 0
}
