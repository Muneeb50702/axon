package dev.axon.bench

import dev.axon.core.driver.DeviceDriver
import dev.axon.core.executor.ConfirmationGate
import dev.axon.core.executor.DefaultExecutor
import dev.axon.core.model.ActResult
import dev.axon.core.model.ActionMenu
import dev.axon.core.model.Bounds
import dev.axon.core.model.Capability
import dev.axon.core.model.CompactState
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.Goal
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.model.TaskOutcome
import dev.axon.core.model.TaskResult
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree
import dev.axon.core.planner.FailureContext
import dev.axon.core.planner.PlanDecision
import dev.axon.core.planner.Planner
import dev.axon.core.runtime.DefaultAgentRuntime
import dev.axon.core.verifier.PostConditionEvaluator

/**
 * **E9** — the C2 recovery-rate measurement, structural half.
 *
 * ## What this measures, and what it cannot
 *
 * §14.2's recovery rate is *"the share of failed steps that were healed"*. On a
 * real device that number is a product of two things: whether the **loop** gives
 * the planner another chance with usable context and executes a corrected
 * action, and whether the **1B model** proposes a better action when asked. Only
 * the first is measurable without a phone.
 *
 * So this reports both bounds rather than one number:
 *
 * - **`RECOVERING` planner** — always proposes a workable alternative after a
 *   failure. This is the architecture's **ceiling**: the recovery rate the loop
 *   permits when the model cooperates.
 * - **`STUBBORN` planner** — re-proposes the action that just failed. Not a straw
 *   man: E18 *measured* this behaviour, a 1B planner repeating an identical
 *   rejected action three times despite a failure context naming it and asking
 *   for something else. This is the **floor**, and what it tests is whether the
 *   loop terminates cleanly instead of burning its budget.
 *
 * The true device number lies between them and needs the model (E9b).
 *
 * ## Why it is worth measuring the ceiling at all
 *
 * Because it was not 100%, and because measuring it found that
 * `TaskResult.healsSucceeded` — documented as "the numerator of recovery rate" —
 * was never incremented anywhere. C2's headline metric was pinned at zero by
 * construction.
 */
object RecoveryStudy {

    /** A failure the executor will actually observe, and why a real device produces it. */
    enum class FailureMode(val cause: String) {
        /** The named element is not on screen: the gate refuses before dispatch. */
        STALE_SELECTOR("app updated; the recorded label is gone"),

        /** The tap lands but nothing changes: the verifier catches it. */
        DEAD_TAP("a disabled control, or a tap swallowed by an overlay"),

        /** The post-condition names something that never appears. */
        WRONG_EXPECTATION("the planner asserted a state this action cannot produce"),
        ;
    }

    enum class PlannerKind { RECOVERING, STUBBORN }

    // ------------------------------------------------------------ fixtures ---

    private fun node(index: Int, label: String, clickable: Boolean = true) = UiNode(
        index = index, role = "button", text = label, contentDescription = label,
        bounds = Bounds(0, index * 60, 200, index * 60 + 50), clickable = clickable,
    )

    private fun screen(vararg labels: String) = UiTree(
        "com.example", "Home", labels.mapIndexed { i, l -> node(i, l) }, capturedAtMs = 0,
    )

    private val done = PostCondition(PostConditionType.NODE_PRESENT, "Done")

    /** The action that will fail, per mode. */
    private fun failing(mode: FailureMode): DeviceAction = when (mode) {
        FailureMode.STALE_SELECTOR ->
            DeviceAction.Tap(Target(TargetBy.CONTENT_DESC, "Vanished"), done)
        FailureMode.DEAD_TAP ->
            DeviceAction.Tap(Target(TargetBy.CONTENT_DESC, "Inert"), done)
        FailureMode.WRONG_EXPECTATION ->
            DeviceAction.Tap(
                Target(TargetBy.CONTENT_DESC, "Inert"),
                PostCondition(PostConditionType.NODE_PRESENT, "Never appears"),
            )
    }

    /** The action that works. */
    private fun working() = DeviceAction.Tap(Target(TargetBy.CONTENT_DESC, "Proceed"), done)

    /**
     * A device where tapping "Proceed" reveals "Done" and everything else is inert.
     *
     * "Inert" is `clickable`, so it passes the gate and fails at the verifier —
     * which is the point: the two defences must be exercised separately or the
     * study only ever measures one of them.
     */
    private class Fixture : DeviceDriver {
        private var proceeded = false
        var dispatched = 0

        override suspend fun observe(): UiTree =
            if (proceeded) screen("Proceed", "Inert", "Done") else screen("Proceed", "Inert")

        override suspend fun act(action: DeviceAction): ActResult {
            dispatched++
            val target = (action as? DeviceAction.Tap)?.target?.value
            if (target == "Proceed") proceeded = true
            return ActResult.Dispatched()
        }

        override suspend fun assert(condition: PostCondition) =
            PostConditionEvaluator.evaluate(condition, observe())

        override fun capabilities() =
            setOf(Capability.UI_OBSERVE, Capability.UI_GESTURE, Capability.APP_LAUNCH)

        override val deviceFamily = "study/jvm"
    }

    /** Proposes the failing action once, then behaves as [kind] dictates. */
    private class ScriptedPlanner(
        private val mode: FailureMode,
        private val kind: PlannerKind,
    ) : Planner {
        var calls = 0
        override suspend fun nextAction(
            state: CompactState, goal: Goal, menu: ActionMenu, failure: FailureContext?,
        ): PlanDecision {
            calls++
            val action = when {
                failure == null -> failing(mode)
                kind == PlannerKind.RECOVERING -> working()
                // E18's measured behaviour: the failure context is ignored.
                else -> failing(mode)
            }
            return PlanDecision(action, llmCalls = 1)
        }
    }

    // --------------------------------------------------------------- study ---

    data class Cell(
        val mode: FailureMode,
        val kind: PlannerKind,
        val outcome: TaskOutcome,
        val healAttempts: Int,
        val healsSucceeded: Int,
        val steps: Int,
        val llmCalls: Int,
    ) {
        val recovered: Boolean get() = outcome == TaskOutcome.SUCCESS && healsSucceeded > 0
    }

    data class Report(val cells: List<Cell>) {
        fun cell(mode: FailureMode, kind: PlannerKind) =
            cells.single { it.mode == mode && it.kind == kind }

        /** §14.2 recovery rate for one planner kind: healed / attempted. */
        fun recoveryRate(kind: PlannerKind): Double? {
            val rows = cells.filter { it.kind == kind }
            val attempts = rows.sumOf { it.healAttempts }
            return if (attempts == 0) null else rows.sumOf { it.healsSucceeded }.toDouble() / attempts
        }

        fun render(): String = buildString {
            appendLine("E9 — RECOVERY RATE (C2), STRUCTURAL HALF")
            appendLine("=".repeat(86))
            appendLine("failure mode".padEnd(20) + "planner".padEnd(13) + "outcome".padEnd(12) +
                "heals".padEnd(8) + "healed".padEnd(9) + "steps".padEnd(7) + "llm")
            appendLine("-".repeat(86))
            for (mode in FailureMode.entries) {
                for (kind in PlannerKind.entries) {
                    val c = cell(mode, kind)
                    appendLine(
                        mode.name.lowercase().padEnd(20) +
                            kind.name.lowercase().padEnd(13) +
                            c.outcome.name.padEnd(12) +
                            "${c.healAttempts}".padEnd(8) +
                            "${c.healsSucceeded}".padEnd(9) +
                            "${c.steps}".padEnd(7) +
                            "${c.llmCalls}",
                    )
                }
            }
            appendLine("=".repeat(86))
            appendLine()
            appendLine("  ceiling (RECOVERING planner) : ${pct(recoveryRate(PlannerKind.RECOVERING))}")
            appendLine("  floor   (STUBBORN planner)   : ${pct(recoveryRate(PlannerKind.STUBBORN))}")
            appendLine()
            appendLine("These are BOUNDS, not the device number. The ceiling is the recovery the")
            appendLine("loop permits when the model proposes a workable alternative; the floor is")
            appendLine("what happens when it repeats itself, which E18 measured a 1B planner doing")
            appendLine("three times in a row. The real rate depends on the model and needs a phone")
            appendLine("(E9b). What the floor shows is that a non-recovering planner ESCALATES")
            appendLine("rather than looping — the repetition guard converting a hang into an exit.")
        }

        private fun pct(v: Double?) = v?.let { "${(it * 1000).toInt() / 10.0}%" } ?: "n/a"
    }

    suspend fun run(): Report {
        val cells = mutableListOf<Cell>()
        for (mode in FailureMode.entries) {
            for (kind in PlannerKind.entries) {
                cells += runOne(mode, kind)
            }
        }
        return Report(cells)
    }

    private suspend fun runOne(mode: FailureMode, kind: PlannerKind): Cell {
        val device = Fixture()
        val planner = ScriptedPlanner(mode, kind)
        val runtime = DefaultAgentRuntime(
            driver = device,
            planner = planner,
            executor = DefaultExecutor(
                device, settleMs = 0, nowMs = { 0L },
                confirmation = ConfirmationGate.ALLOW_FOR_TESTING,
            ),
            nowMs = { 0L },
            goalReached = { s -> s.elements.any { it.label == "Done" } },
        )

        val result: TaskResult = runtime.execute(
            Goal("finish the task", stepBudget = 6, healBudget = 2),
        )

        return Cell(
            mode = mode,
            kind = kind,
            outcome = result.outcome,
            healAttempts = result.healAttempts,
            healsSucceeded = result.healsSucceeded,
            steps = result.steps.size,
            llmCalls = result.llmCalls,
        )
    }
}

/** Print E9's table. `./gradlew :bench:recoveryStudy` */
object RunRecoveryStudy {
    @JvmStatic
    fun main(args: Array<String>) {
        println(kotlinx.coroutines.runBlocking { RecoveryStudy.run() }.render())
    }
}
