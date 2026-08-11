package dev.axon.core.runtime

import dev.axon.core.model.DeviceAction
import dev.axon.core.model.Goal
import dev.axon.core.model.PostCondition
import dev.axon.core.model.TaskResult
import dev.axon.core.model.VerifyResult
import kotlinx.coroutines.flow.Flow

/**
 * Orchestrates the control loop of spec §7.2 (§9.2).
 *
 * ```
 * receive goal
 *   ├─ skill store hit?  ──YES──▶ REPLAY  (deterministic, 0 LLM calls)
 *   │                     ──NO───▶ PLAN
 *   └─ PLAN, until goal or budget:
 *        1. PERCEIVE  observe() → CompactState
 *        2. PLAN      LLM picks ONE action, GBNF-constrained
 *        3. VALIDATE  precondition gate — reject before touching the device
 *        4. ACT       dispatch via driver
 *        5. VERIFY    re-observe → diff vs post-condition
 *                       MATCH    → commit step, continue
 *                       MISMATCH → self-heal (rollback + replan w/ failure ctx)
 *        on success:
 *        6. record trace → compile to skill (C1)
 * ```
 *
 * The runtime is the only component that sees all five stages, and it is
 * deliberately thin — it sequences, it does not decide. Every judgement belongs
 * to a component with a single responsibility (§8), which is what keeps the
 * ablation honest: turning the verifier off (§14.3 config B) removes stage 5 and
 * nothing else, so the measured difference is attributable to the verifier
 * rather than to some tangle of behaviour that changed with it.
 */
public interface AgentRuntime {

    /** Run [goal] to completion, escalation, or budget exhaustion. */
    public suspend fun execute(goal: Goal): TaskResult

    /**
     * Live progress for the UI and the audit log (§16).
     *
     * §16 requires the user to see every action the agent takes. Streaming
     * rather than only reporting at the end is what makes the agent
     * *interruptible*: a user watching a step they did not intend can stop the
     * run before the next one. An agent that reports only on completion is
     * observable but not stoppable, which is not what "visible operation" means.
     */
    public fun events(goal: Goal): Flow<AgentEvent>

    /** Stop the current task at the next step boundary. */
    public suspend fun cancel()
}

/**
 * One observable moment in a run. Serialised into the audit log verbatim.
 */
public sealed interface AgentEvent {

    public data class TaskStarted(val goal: Goal, val path: ExecutionPath) : AgentEvent

    /** Screen captured (§7.2 step 1). */
    public data class Perceived(
        val foregroundPackage: String,
        val elementCount: Int,
        val sensitiveWithheld: Int,
    ) : AgentEvent

    /** Planner chose an action (§7.2 step 2). */
    public data class Planned(
        val action: DeviceAction,
        val llmCalls: Int,
        val latencyMs: Long,
        val reasoning: String? = null,
    ) : AgentEvent

    /** Precondition gate rejected the action before it touched the device (§7.5). */
    public data class Rejected(val action: DeviceAction, val reason: String) : AgentEvent

    /** Action dispatched (§7.2 step 4). */
    public data class Acted(val action: DeviceAction, val latencyMs: Long) : AgentEvent

    /** Verifier verdict (§7.2 step 5). */
    public data class Verified(
        val expected: PostCondition,
        val result: VerifyResult,
    ) : AgentEvent

    /** A heal was attempted after a mismatch (§7.6). */
    public data class Healing(val attempt: Int, val strategy: String) : AgentEvent

    /**
     * The run needs the user (§7.6 escalation, §16 destructive-action consent).
     *
     * Both cases are one event because both mean the same thing to the UI: stop
     * and wait for a human. They differ only in [reason].
     */
    public data class NeedsUser(val reason: String, val question: String) : AgentEvent

    public data class TaskFinished(val result: TaskResult) : AgentEvent
}

/** Which branch of §7.2 served a task. Recorded per run for the C1 evaluation. */
public enum class ExecutionPath {
    /** Cold: the planner drove every step. */
    PLAN,

    /** A compiled skill replayed with no model in the loop. */
    REPLAY,

    /** Replay that hit UI drift and fell back to the planner for some steps. */
    REPLAY_WITH_REPAIR,
}
