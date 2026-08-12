package dev.axon.android.inference

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.axon.core.bench.ScreenCorpus
import dev.axon.core.driver.DeviceDriver
import dev.axon.core.executor.ConfirmationGate
import dev.axon.core.executor.DefaultExecutor
import dev.axon.core.inference.ScreenGrammar
import dev.axon.core.model.ActResult
import dev.axon.core.model.Capability
import dev.axon.core.model.CompiledSkill
import dev.axon.core.model.CompiledStep
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.Goal
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.SkillManifest
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.model.UiTree
import dev.axon.core.planner.PlannerPrompt
import dev.axon.core.skills.SkillReplayer
import dev.axon.core.verifier.PostConditionEvaluator
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * **E17 — the C1′ headline.** Cold planning versus compiled replay, measured.
 *
 * Every replay figure quoted so far has been arithmetic: cold-path measurements
 * (E2, E15) with replay assumed to cost "essentially nothing". That assumption is
 * almost certainly true and it is still an assumption, so the paper cannot use it
 * until it is a number. This test produces the number.
 *
 * ## What is compared, and what is controlled
 *
 * Both arms perform **the same two device actions** against the same screens. The
 * only difference is where the actions come from:
 *
 * | arm | actions come from | model involved |
 * |---|---|---|
 * | cold PLAN | a grammar-constrained generation per step | yes |
 * | compiled REPLAY | a frozen `CompiledSkill` | no |
 *
 * A synthetic driver supplies the screens rather than a live app. That is
 * deliberate: on a real app the cold arm would frequently pick the *wrong*
 * element (E18b), and the comparison would then be measuring task success rather
 * than the cost of deciding. Holding the actions fixed isolates exactly the
 * quantity C1′ claims to remove — **the cost of consulting the model.**
 *
 * The energy figures are whole-device differentials against an idle baseline
 * (see [EnergyProbe]); the caveats there apply here.
 */
class ReplayCostTest {

    private val expectSent = PostCondition(PostConditionType.NODE_PRESENT, "Sent")

    /** Two screens: before the action, and after. Shared by both arms. */
    private fun screens(): List<UiTree> {
        val case = ScreenCorpus.ALL.first { it.id == "whatsapp_conversation" }
        return listOf(case.tree, case.tree)
    }

    /** Dispatches nothing. Both arms pay the same (zero) device cost. */
    private class NoOpDriver(private val screen: UiTree) : DeviceDriver {
        var acted = 0
        override suspend fun observe(): UiTree = screen
        override suspend fun act(action: DeviceAction): ActResult {
            acted++
            return ActResult.Dispatched()
        }
        override suspend fun assert(condition: PostCondition) =
            PostConditionEvaluator.evaluate(condition, screen)
        override fun capabilities() = setOf(Capability.UI_GESTURE)
        override val deviceFamily = "measurement/no-op"
    }

    @Test
    fun e17_coldPlanVersusCompiledReplay(): Unit = runBlocking {
        val model = File(MODEL_DIR).listFiles()
            ?.filter { it.name.endsWith(".gguf") }
            ?.minByOrNull { it.length() }
        assumeTrue("no model in $MODEL_DIR", model != null)
        requireNotNull(model)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = EnergyProbe(context)
        val engine = LlamaEngine.load(context, model)

        try {
            val case = ScreenCorpus.ALL.first { it.id == "whatsapp_conversation" }
            val steps = 2

            // ---- arm A: cold PLAN ------------------------------------------
            //
            // Two grammar-constrained generations, exactly as the planner would
            // issue them. The action is not executed — the quantity of interest
            // is the cost of *deciding*, and executing would add identical
            // device cost to both arms while adding noise.
            val prompt = engine.applyChatTemplate(
                PlannerPrompt.SYSTEM,
                PlannerPrompt.user(case.state, case.goal),
            )
            val grammar = ScreenGrammar.forScreen(case.state)

            val (planStats, planEnergy) = probe.measure {
                (1..steps).map {
                    engine.generateInstrumented(prompt, grammar, maxTokens = 96)
                }
            }
            val planMs = planStats.sumOf { it.result.latencyMs }
            val planTokens = planStats.sumOf { it.result.completionTokens }

            // ---- arm B: compiled REPLAY ------------------------------------
            //
            // The same two actions, frozen. No model is consulted; the replayer
            // is given no planner at all, so a fallback is not merely unused but
            // impossible.
            val driver = NoOpDriver(case.tree)
            val skill = handCompiledSkill(steps)
            val replayer = SkillReplayer(
                driver = driver,
                executor = DefaultExecutor(
                    driver, settleMs = 0, nowMs = System::currentTimeMillis,
                    confirmation = ConfirmationGate.ALLOW_FOR_TESTING,
                ),
                nowMs = System::currentTimeMillis,
                planner = null,
            )

            val (replay, replayEnergy) = probe.measure {
                replayer.replay(skill, Goal("send a message"), emptyMap())
            }

            // ---- report -----------------------------------------------------
            val speedup = if (replay.result.totalMs == 0L) Double.POSITIVE_INFINITY
            else planMs.toDouble() / replay.result.totalMs

            Log.i(TAG, buildString {
                append("\n=== E17: cold PLAN vs compiled REPLAY ($steps steps) ===\n")
                append("model             : ${engine.modelId}\n\n")
                append("--- arm A: cold PLAN (model in the loop) ---\n")
                append("  model calls     : $steps\n")
                append("  output tokens   : $planTokens\n")
                append("  wall clock      : $planMs ms\n")
                append("  energy          : ${planEnergy.render()}\n")
                append("  J above idle    : %.2f J\n".format(planEnergy.joulesAboveIdle))
                append("\n--- arm B: compiled REPLAY (no model) ---\n")
                append("  model calls     : ${replay.result.llmCalls}\n")
                append("  wall clock      : ${replay.result.totalMs} ms\n")
                append("  energy          : ${replayEnergy.render()}\n")
                append("  J above idle    : %.2f J\n".format(replayEnergy.joulesAboveIdle))
                append("  outcome         : ${replay.result.outcome}\n")
                append("  fully determ.   : ${replay.wasFullyDeterministic}\n")
                append("\n--- ratio ---\n")
                append("  speedup         : %.0fx\n".format(speedup))
                // An energy RATIO is deliberately not reported.
                //
                // Replay finishes in ~17 ms and the probe samples every 250 ms,
                // so the replay arm yields one sample or none — its energy figure
                // is below the instrument's resolution, not measured as zero.
                // Dividing by it produces a spectacular number that means
                // nothing, and the first run printed 56119x before this was
                // fixed. What is defensible is stated instead.
                append("  energy (replay) : below probe resolution ")
                append("(${replayEnergy.samples} sample(s) over ${replay.result.totalMs} ms; ")
                append("probe interval 250 ms)\n")
                append("  inference energy: %.2f J cold vs 0 J replayed ".format(planEnergy.joulesAboveIdle))
                append("(exact — no inference occurred)\n")
            })

            // The claim, asserted rather than reported.
            org.junit.Assert.assertEquals(
                "replay must cost zero model calls", 0, replay.result.llmCalls,
            )
            org.junit.Assert.assertTrue(
                "replay should be dramatically faster than planning",
                replay.result.totalMs < planMs / 10,
            )
        } finally {
            engine.close()
        }
    }

    /**
     * A hand-written skill equivalent to what the compiler produces.
     *
     * Built directly rather than compiled from a live run because obtaining two
     * *clean* runs on a real app requires the planner to pick correctly twice,
     * which E18b showed a 1B model does not reliably do. Since the quantity being
     * measured is replay cost — not whether the planner can produce a compilable
     * trace — hand-writing the skill isolates it. `SkillCompilerTest` covers the
     * compiler producing this shape.
     */
    private fun handCompiledSkill(steps: Int) = CompiledSkill(
        manifest = SkillManifest(
            id = "measurement_skill",
            name = "measurement",
            goalPattern = "send a message",
        ),
        steps = (1..steps).map { i ->
            CompiledStep(
                step = i,
                selector = Target(TargetBy.CONTENT_DESC, "Message"),
                action = "tap",
                // An assertion that holds on the fixed screen, so the replay
                // completes and the measurement covers a full successful run.
                expect = PostCondition(PostConditionType.NODE_PRESENT, "Message"),
                llmFallback = false,
            )
        },
    )

    private companion object {
        const val TAG = "AxonE17"
        const val MODEL_DIR = "/data/local/tmp/axon"
    }
}
