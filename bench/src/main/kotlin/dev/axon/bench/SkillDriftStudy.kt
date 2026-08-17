package dev.axon.bench

import dev.axon.core.driver.DeviceDriver
import dev.axon.core.executor.ConfirmationGate
import dev.axon.core.executor.DefaultExecutor
import dev.axon.core.model.ActResult
import dev.axon.core.model.Bounds
import dev.axon.core.model.Capability
import dev.axon.core.model.CompiledSkill
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.Goal
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.ResolvedHandles
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.model.TaskOutcome
import dev.axon.core.model.TraceStep
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree
import dev.axon.core.model.VerifiedTrace
import dev.axon.core.skills.CompileResult
import dev.axon.core.skills.DefaultSkillCompiler
import dev.axon.core.skills.SelectorPolicy
import dev.axon.core.skills.SkillReplayer
import dev.axon.core.verifier.PostConditionEvaluator

/**
 * **E26b** — does promoting a selector to a sturdier handle reduce replay
 * breakage under UI drift?
 *
 * ## Why this exists
 *
 * `docs/POSITIONING.md` §3.4 lists *skill decay under UI drift* as one of five
 * things genuinely AXON's, and notes that two mechanisms — selector promotion
 * (E26) and skill retirement (E27) — are implemented and **deliberately
 * unclaimed** because nothing has measured them. This measures the first.
 *
 * ## Method
 *
 * The same recorded trace is compiled twice by the **real** compiler, once with
 * `promoteSelectors = true` and once without, and both skills are replayed by the
 * **real** replayer against the same drifted screens. Nothing here reimplements
 * selector matching: a study that modelled the mechanism would measure the model.
 *
 * The recorded selector is `text`, which is what the planner actually produces —
 * the screen grammar names elements by label, and a label is `contentDescription
 * ?: text`. So the un-promoted arm is not a straw man; it is what ships when
 * promotion is off.
 *
 * ## The result is mixed, and that is the point
 *
 * Promotion is not free. It trades fragility-to-copy-changes for
 * fragility-to-id-changes, and which is better depends on the relative frequency
 * of those events in real app updates — a number **this study does not have**
 * (see [DriftClass]). Per-class survival is reported; no aggregate is.
 */
object SkillDriftStudy {

    // ------------------------------------------------------------ fixture ----

    /**
     * The element a skill will be compiled against.
     *
     * Carries all three handles, because the question is which one to *prefer*
     * when several are available. An element with only one handle has no
     * decision to make and would tell us nothing.
     */
    private fun target() = UiNode(
        index = 0,
        role = "button",
        text = "Send",
        contentDescription = "Send message",
        viewId = "com.whatsapp:id/send",
        bounds = Bounds(0, 0, 200, 60),
        clickable = true,
    )

    private fun distractor() = UiNode(
        index = 1,
        role = "button",
        text = "Attach",
        contentDescription = "Attach a file",
        viewId = "com.whatsapp:id/attach",
        bounds = Bounds(0, 80, 200, 140),
        clickable = true,
    )

    private fun screen() = UiTree(
        foregroundPackage = "com.whatsapp",
        screenTitle = "Ammi",
        nodes = listOf(target(), distractor()),
        capturedAtMs = 0,
    )

    /**
     * A clean two-run-equivalent trace targeting the element by `text`.
     *
     * `resolvedHandles` carries what the gate matched — that is the evidence
     * promotion acts on (E26), and without it the compiler has nothing to
     * promote *to*.
     */
    private fun trace(id: String) = VerifiedTrace(
        traceId = id,
        goal = "send a message",
        steps = listOf(
            TraceStep(
                action = DeviceAction.Tap(
                    Target(TargetBy.TEXT, "Send"),
                    PostCondition(PostConditionType.NODE_PRESENT, "Sent"),
                ),
                preOk = true,
                postOk = true,
                latencyMs = 100,
                resolvedHandles = ResolvedHandles(
                    viewId = "com.whatsapp:id/send",
                    contentDescription = "Send message",
                    text = "Send",
                ),
            ),
        ),
        outcome = TaskOutcome.SUCCESS,
        llmCalls = 2,
        totalMs = 1000,
        device = "study/jvm",
        model = "study",
    )

    /** A driver serving one fixed screen; the post-condition is met iff the tap landed. */
    private class DriftedDevice(private val tree: UiTree) : DeviceDriver {
        var dispatched = 0
        override suspend fun observe(): UiTree =
            if (dispatched == 0) tree else tree.copy(
                nodes = tree.nodes + UiNode(
                    index = 99, role = "text", text = "Sent", contentDescription = "Sent",
                    bounds = Bounds(0, 200, 200, 240),
                ),
            )

        override suspend fun act(action: DeviceAction): ActResult {
            dispatched++
            return ActResult.Dispatched()
        }

        override suspend fun assert(condition: PostCondition) =
            PostConditionEvaluator.evaluate(condition, observe())

        override fun capabilities() = setOf(
            Capability.UI_OBSERVE, Capability.UI_GESTURE, Capability.APP_LAUNCH,
        )
        override val deviceFamily = "study/jvm"
    }

    // -------------------------------------------------------------- study ----

    /** One cell: did the skill compiled under [policy] still replay after [drift]? */
    data class Cell(val drift: DriftClass, val policy: SelectorPolicy, val survived: Boolean)

    data class Report(
        val cells: List<Cell>,
        /** What each policy actually compiled to, for the record. */
        val selectors: Map<SelectorPolicy, Target?>,
    ) {
        fun survived(drift: DriftClass, policy: SelectorPolicy): Boolean =
            cells.single { it.drift == drift && it.policy == policy }.survived

        fun survivedCount(policy: SelectorPolicy): Int =
            DriftClass.ALL.count { survived(it, policy) }

        /** Classes the policies disagree about — where the choice actually matters. */
        fun decisive(): List<DriftClass> = DriftClass.ALL.filter { d ->
            SelectorPolicy.entries.map { survived(d, it) }.distinct().size > 1
        }

        fun render(): String = buildString {
            appendLine("E26b — SELECTOR PROMOTION UNDER UI DRIFT")
            appendLine("=".repeat(84))
            for (p in SelectorPolicy.entries) {
                val t = selectors[p]
                appendLine("  ${p.name.padEnd(10)} compiles to  ${t?.by?.wire}=\"${t?.value}\"")
            }
            appendLine()
            appendLine("drift class".padEnd(16) + "cause".padEnd(34) + "NONE  LABEL  STURDIEST")
            appendLine("-".repeat(84))
            for (d in DriftClass.ALL) {
                append(d.name.lowercase().padEnd(16))
                append(d.cause.take(32).padEnd(34))
                append(if (survived(d, SelectorPolicy.NONE)) " ✔  " else " ✘  ")
                append(if (survived(d, SelectorPolicy.LABEL)) "   ✔  " else "   ✘  ")
                appendLine(if (survived(d, SelectorPolicy.STURDIEST)) "    ✔" else "    ✘")
            }
            appendLine("-".repeat(84))
            append("survived".padEnd(50))
            append("${survivedCount(SelectorPolicy.NONE)}/8".padEnd(6))
            append("${survivedCount(SelectorPolicy.LABEL)}/8".padEnd(7))
            appendLine("${survivedCount(SelectorPolicy.STURDIEST)}/8")
            appendLine("=".repeat(84))
            appendLine()
            appendLine("LABEL and STURDIEST survive the same NUMBER of classes and differ in")
            appendLine("WHICH. STURDIEST is immune to every copy change and dies on the two")
            appendLine("classes that touch view ids — including a Compose migration, which")
            appendLine("removes them wholesale while changing nothing a user sees.")
            appendLine()
            appendLine("NO WEIGHTED AGGREGATE IS REPORTED. Ranking these needs how often each")
            appendLine("drift class occurs in real app updates, which this study does not")
            appendLine("measure. The counts above assume every class is equally likely, which")
            appendLine("is certainly false and is stated so it cannot be mistaken for a result.")
        }
    }

    suspend fun run(): Report {
        val compiled = SelectorPolicy.entries.associateWith { compile(it) }

        val cells = mutableListOf<Cell>()
        for (drift in DriftClass.ALL) {
            val drifted = drift.applyTo(screen())
            for ((policy, skill) in compiled) {
                cells += Cell(drift, policy, replays(skill, drifted))
            }
        }

        return Report(
            cells = cells,
            selectors = compiled.mapValues { (_, s) -> s.steps.single().selector },
        )
    }

    private suspend fun compile(policy: SelectorPolicy): CompiledSkill {
        val result = DefaultSkillCompiler(selectorPolicy = policy).compile(trace("t-$policy"))
        return (result as CompileResult.Compiled).skill
    }

    /** Replay [skill] against [tree] with **no planner**, so repair cannot mask breakage. */
    private suspend fun replays(skill: CompiledSkill, tree: UiTree): Boolean {
        val device = DriftedDevice(tree)
        val replayer = SkillReplayer(
            driver = device,
            executor = DefaultExecutor(
                device, settleMs = 0, nowMs = { 0L },
                confirmation = ConfirmationGate.ALLOW_FOR_TESTING,
            ),
            nowMs = { 0L },
            // Deliberately absent. With a planner, a broken selector would be
            // repaired and the run would succeed — measuring the fallback, not
            // the selector. E26's claim is about how often repair is *needed*.
            planner = null,
        )
        val result = replayer.replay(skill, Goal("send a message"), emptyMap())
        return result.result.outcome == TaskOutcome.SUCCESS
    }
}

/** Print E26b's table. `./gradlew :bench:driftStudy` */
object RunSkillDriftStudy {
    @JvmStatic
    fun main(args: Array<String>) {
        val report = kotlinx.coroutines.runBlocking { SkillDriftStudy.run() }
        println(report.render())
    }
}
