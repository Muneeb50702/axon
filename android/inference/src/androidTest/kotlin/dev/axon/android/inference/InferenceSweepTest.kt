package dev.axon.android.inference

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.axon.core.bench.ScreenCorpus
import dev.axon.core.inference.ActionGrammar
import dev.axon.core.planner.PlannerPrompt
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Measures one generation under a given configuration (spec §14.2 latency, D8).
 *
 * ## Why this exists as a separate, tiny test
 *
 * The first attempt at the Phase 1 acceptance run on the target device did not
 * produce a slow number — it produced no number at all. A single generation ran
 * for over nine minutes and the process was then killed by TECNO's OEM memory
 * manager (`Griffin/AdjClean: Kill … adj:0, mem:909Mb`) while in the foreground.
 *
 * A full acceptance run is the wrong instrument for diagnosing that. It takes
 * hours, changes several variables at once, and dies before reporting. This test
 * changes exactly one setting, runs exactly one generation, and prints the
 * prefill/decode split — which is what turns "it is too slow" into a specific
 * cause: model size, thread count, or context size.
 *
 * Every parameter comes from instrumentation arguments, so a sweep needs no
 * rebuild:
 *
 * ```
 * adb shell am instrument -w \
 *   -e class dev.axon.android.inference.InferenceSweepTest \
 *   -e axonModel gemma-3-1b-it-Q4_K_M.gguf \
 *   -e axonThreads 4 -e axonCtx 2048 -e axonMaxTokens 64 \
 *   dev.axon.android.inference.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
class InferenceSweepTest {

    // Explicit Unit: the block's last expression is Log.i, which returns Int, and
    // JUnit4 rejects a non-void test method.
    @Test
    fun sweep(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelName = args.getString("axonModel")
        val threads = args.getString("axonThreads")?.toIntOrNull()
            ?: LlamaEngine.Config.defaultDecodeThreads()
        val threadsBatch = args.getString("axonThreadsBatch")?.toIntOrNull()
            ?: LlamaEngine.Config.defaultPrefillThreads()
        val ctx = args.getString("axonCtx")?.toIntOrNull() ?: 2048
        val maxTokens = args.getString("axonMaxTokens")?.toIntOrNull() ?: 64
        val useGrammar = args.getString("axonGrammar")?.toBooleanStrictOrNull() ?: true

        val model = if (modelName != null) {
            File(MODEL_DIR, modelName)
        } else {
            File(MODEL_DIR).listFiles()?.firstOrNull { it.name.endsWith(".gguf") }
        }
        assumeTrue("no model at $MODEL_DIR — run ./tools/fetch-model.sh --push", model?.isFile == true)
        requireNotNull(model)

        val meminfoBefore = readAvailableMemMb()
        Log.i(TAG, "=== sweep: ${model.name} threads=$threads/$threadsBatch ctx=$ctx " +
            "maxTokens=$maxTokens grammar=$useGrammar availMemBefore=${meminfoBefore}MB ===")

        val loadStart = System.currentTimeMillis()
        val engine = LlamaEngine.load(
            InstrumentationRegistry.getInstrumentation().targetContext,
            model,
            LlamaEngine.Config(nCtx = ctx, nThreads = threads, nThreadsBatch = threadsBatch),
        )
        val loadMs = System.currentTimeMillis() - loadStart

        try {
            val case = ScreenCorpus.ALL.first { it.id == "whatsapp_conversation" }
            val prompt = engine.applyChatTemplate(
                PlannerPrompt.SYSTEM,
                PlannerPrompt.user(case.state, case.goal),
            )

            // Energy is measured around the generation, not inside it: the
            // sampler runs on a separate coroutine so the work being measured is
            // not slowed by the measuring, which would inflate the very number
            // being taken.
            val probe = EnergyProbe(InstrumentationRegistry.getInstrumentation().targetContext)
            val (r, energy) = probe.measure {
                engine.generateInstrumented(
                    prompt = prompt,
                    grammar = if (useGrammar) ActionGrammar.GBNF else null,
                    maxTokens = maxTokens,
                )
            }

            Log.i(TAG, buildString {
                append("\n--- RESULT ${model.name} t=$threads/$threadsBatch ctx=$ctx grammar=$useGrammar ---\n")
                append("model load        : $loadMs ms\n")
                append("prompt tokens     : ${r.result.promptTokens}\n")
                append("output tokens     : ${r.result.completionTokens}\n")
                append("prefill           : ${r.prefillMs} ms " +
                    "(%.1f tok/s)\n".format(r.prefillTokensPerSec))
                append("decode            : ${r.decodeMs} ms " +
                    "(%.1f tok/s)\n".format(r.decodeTokensPerSec))
                append("grammar sampling  : ${r.grammarMs} ms\n")
                append("total             : ${r.result.latencyMs} ms\n")
                append("prefill share     : %.0f%%\n".format(r.prefillShare * 100))
                append("thermal           : ${r.thermalAfter}\n")
                append("energy            : ${energy.render()}\n")
                if (energy.plausible) {
                    append("  J above idle    : %.2f J\n".format(energy.joulesAboveIdle))
                    append("  planning steps  : ~${energy.tasksPerCharge()} per full charge\n")
                }
                append("avail mem after   : ${readAvailableMemMb()} MB\n")
                append("stopped on EOG    : ${r.stoppedOnEog}\n")
                append("output            : ${r.result.text.trim().take(300)}\n")
            })
        } finally {
            engine.close()
        }
    }

    /**
     * `MemAvailable` from /proc/meminfo, in MB.
     *
     * Recorded because the failure that motivated this test was a memory kill,
     * not a timeout. Watching available memory across a sweep is what separates
     * "this model is slow" from "this model does not fit, and the device is
     * paging it back off disk on every forward pass".
     */
    private fun readAvailableMemMb(): Long = runCatching {
        File("/proc/meminfo").readLines()
            .first { it.startsWith("MemAvailable:") }
            .filter { it.isDigit() }
            .toLong() / 1024
    }.getOrDefault(-1)

    private companion object {
        const val TAG = "AxonSweep"
        const val MODEL_DIR = "/data/local/tmp/axon"
    }
}
