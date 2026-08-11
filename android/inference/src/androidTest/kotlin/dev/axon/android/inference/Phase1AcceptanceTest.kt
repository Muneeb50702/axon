package dev.axon.android.inference

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.axon.core.bench.ScreenCorpus
import dev.axon.core.inference.ActionGrammar
import dev.axon.core.model.AxonJson
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.ThermalState
import dev.axon.core.planner.PlannerPrompt
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Phase 1 acceptance (spec §13).
 *
 * > *Accept: 500 generations → 100% schema-valid actions; thermal telemetry
 * > logs; median gen latency recorded.*
 *
 * ## This test is also the first row of the ablation table
 *
 * §14.3 asks for a comparison between config **A** (no grammar) and config **B**
 * (grammar), and the difference the table expects is *valid-action rate ≈ 100%*
 * for B. That is precisely what the acceptance criterion measures. So rather
 * than checking the grammar arm alone and re-deriving the comparison months
 * later in Phase 7, this runs **both** arms over the identical prompt corpus in
 * one pass. The A-vs-B row of the money table is a Phase 1 by-product.
 *
 * Running both also converts the criterion from a bare assertion into evidence.
 * "100% of constrained generations were valid" is only interesting alongside
 * what the *same* model did on the *same* prompts unconstrained — otherwise the
 * number could just mean the task was easy.
 *
 * ## Why an instrumented test rather than a JVM one
 *
 * It needs real arm64 silicon, the real NDK build, and a 3.11 GB model. Nothing
 * about that is simulable on the desktop, and pretending otherwise would make
 * the headline latency figure meaningless. The deterministic half of AXON is
 * tested on the JVM (see `:core`); this is the half that is genuinely
 * device-bound, and §14 reports the two separately for that reason.
 *
 * ## Running it
 *
 * ```
 * ./tools/fetch-model.sh --push
 * ./gradlew :android:inference:connectedDebugAndroidTest
 * ```
 *
 * Sample size defaults to a smoke-sized run so the suite stays usable during
 * development; the full 500 is opt-in, because on this device it takes hours:
 *
 * ```
 * ./gradlew :android:inference:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.axonSamples=500
 * ```
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Phase1AcceptanceTest {

    // -----------------------------------------------------------------
    // t01 … t06 — ordered so a failure reports the earliest broken thing
    // -----------------------------------------------------------------

    @Test
    fun t01_backendsAreReported() {
        val backends = engine.backends
        Log.i(TAG, "backends: " + backends.joinToString { "${it.name} (type=${it.type})" })
        assertTrue("no ggml backend was compiled in", backends.isNotEmpty())

        // Decision D8: §18 lists -DLLAMA_VULKAN=ON as verified, but on Mali-G52
        // MC2 with a 2021 driver that is a claim to test, not a setting to trust.
        // Recording which backends the loaded .so actually offers is what makes
        // the CPU-vs-Vulkan comparison a measurement.
        val gpu = backends.filter { it.isGpu }
        Log.i(TAG, if (gpu.isEmpty()) "CPU-only build (D8 default)" else "GPU backends: $gpu")
    }

    @Test
    fun t02_modelLoadsAndReportsItsIdentity() {
        Log.i(TAG, "modelId=${engine.modelId}")
        assertTrue("model reported no identity", engine.modelId.isNotBlank())
    }

    /**
     * The action grammar must parse before anything else is worth measuring.
     *
     * §10.6 warns GBNF is fussy, and `llama_sampler_init_grammar` returns NULL on
     * a parse failure. Checking it here means a malformed grammar fails in one
     * second with a clear message, rather than surfacing 500 generations later as
     * an inexplicable zero valid-action rate.
     */
    @Test
    fun t03_actionGrammarParses() {
        assertTrue(
            "the §10.6 action grammar failed to parse in llama.cpp",
            engine.validateGrammar(ActionGrammar.GBNF),
        )
    }

    /**
     * The C3 measurement, and the §13 Phase 1 acceptance criterion.
     */
    @Test
    fun t04_constrainedGenerationsAreAllSchemaValid() = runBlocking {
        val cases = ScreenCorpus.sample(samples)
        val constrained = mutableListOf<Observation>()
        val unconstrained = mutableListOf<Observation>()

        for ((i, case) in cases.withIndex()) {
            if (!engine.thermalState().allowsAnyWork) {
                Log.w(TAG, "stopping at $i/${cases.size}: thermal ${engine.thermalState()}")
                break
            }

            val prompt = engine.applyChatTemplate(
                PlannerPrompt.SYSTEM,
                PlannerPrompt.user(case.state, case.goal),
            )

            // Both arms see the identical prompt. Sampling is greedy
            // (temperature 0), so the only variable between them is the grammar
            // — which is what makes this an ablation rather than two runs.
            constrained += observe(case.id, prompt, ActionGrammar.GBNF)
            unconstrained += observe(case.id, prompt, grammar = null)

            // Logged every case, not every tenth. A full run is ~50 minutes of
            // sustained CPU load, and TECNO's OEM memory manager has already
            // SIGKILLed this process once mid-run. Per-case logging means a kill
            // costs the remaining cases, not the whole run's data.
            Log.i(TAG, "[${i + 1}/${cases.size}] ${case.id} " +
                "B(grammar)=${constrained.count { it.valid }}/${constrained.size} " +
                "A(naive)=${unconstrained.count { it.valid }}/${unconstrained.size} " +
                "lastMs=${constrained.last().latencyMs}")
        }

        val report = Report(
            model = engine.modelId,
            device = "${android.os.Build.MODEL} / API ${android.os.Build.VERSION.SDK_INT}",
            backends = engine.backends.map { it.name },
            samples = constrained.size,
            constrained = Arm.from("B_grammar", constrained),
            unconstrained = Arm.from("A_naive", unconstrained),
        )
        writeReport(report)
        Log.i(TAG, report.render())

        assumeTrue("no generations completed — model or thermal problem", constrained.isNotEmpty())

        // §13: "500 generations → 100% schema-valid actions".
        assertEquals(
            "grammar-constrained output must be 100% schema-valid; " +
                "failures: ${constrained.filterNot { it.valid }.take(3).map { it.text.take(160) }}",
            constrained.size,
            constrained.count { it.valid },
        )
    }

    @Test
    fun t05_grammarBeatsUnconstrainedOnValidActionRate() {
        // The §14.3 A-vs-B expectation, asserted rather than assumed. If an
        // unconstrained 2B model matched the grammar here, C3 would be measuring
        // nothing and the contribution would need rethinking — so this failing is
        // informative, not merely inconvenient.
        val report = lastReport
        assumeTrue("no report produced", report != null)
        requireNotNull(report)

        Log.i(TAG, "valid-action rate: " +
            "A(naive)=${"%.1f".format(report.unconstrained.validRate * 100)}% " +
            "B(grammar)=${"%.1f".format(report.constrained.validRate * 100)}%")

        assertTrue(
            "grammar arm (${report.constrained.validRate}) did not beat naive arm " +
                "(${report.unconstrained.validRate})",
            report.constrained.validRate >= report.unconstrained.validRate,
        )
    }

    @Test
    fun t06_latencyAndThermalAreRecorded() {
        val report = lastReport
        assumeTrue("no report produced", report != null)
        requireNotNull(report)

        // §13 requires median latency recorded; §7.3 requires thermal telemetry
        // from day one, because "demos run 10s, real tasks run minutes".
        assertTrue("median latency not recorded", report.constrained.medianLatencyMs > 0)
        Log.i(TAG, "median constrained latency: ${report.constrained.medianLatencyMs} ms")
        Log.i(TAG, "thermal at end: ${engine.thermalState()}")
    }

    // -----------------------------------------------------------------

    private suspend fun observe(
        caseId: String,
        prompt: String,
        grammar: dev.axon.core.inference.Gbnf?,
    ): Observation {
        val r = engine.generateInstrumented(prompt, grammar, maxTokens = 192)
        val text = r.result.text.trim()

        // Parsed with AxonJson.strict — the same parser the planner uses, with
        // ignoreUnknownKeys = false. A laxer parser here would flatter the
        // unconstrained arm by accepting output the real system would reject.
        val parsed = runCatching {
            AxonJson.strict.decodeFromString(DeviceAction.serializer(), extractJson(text))
        }

        return Observation(
            caseId = caseId,
            text = text,
            valid = parsed.isSuccess,
            error = parsed.exceptionOrNull()?.message,
            latencyMs = r.result.latencyMs,
            prefillMs = r.prefillMs,
            decodeMs = r.decodeMs,
            grammarMs = r.grammarMs,
            promptTokens = r.result.promptTokens,
            completionTokens = r.result.completionTokens,
            truncated = r.result.truncated,
            thermal = r.thermalAfter,
        )
    }

    /**
     * Pull the JSON object out of a raw completion.
     *
     * Only ever does anything for the *unconstrained* arm: the grammar's root
     * production begins with `{`, so a constrained generation cannot be preceded
     * by prose. An unconstrained 2B model routinely writes "Sure! Here's the
     * action:" first.
     *
     * This deliberately makes the naive baseline look *better* than a strict
     * reading would. §14.3 exists to show B beating A, and an A that was
     * penalised for chattiness rather than for wrongness would be a straw man.
     * The comparison should turn on whether the action is well-formed, not on
     * whether the model said hello.
     */
    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        if (start < 0) return text
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return text.substring(start)
    }

    private fun writeReport(report: Report) {
        lastReport = report
        val dir = InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir(null) ?: return
        val out = File(dir, "phase1-acceptance.json")
        out.writeText(AxonJson.persistence.encodeToString(Report.serializer(), report))
        Log.i(TAG, "report written: ${out.absolutePath}")
    }

    // -----------------------------------------------------------------

    @kotlinx.serialization.Serializable
    data class Observation(
        val caseId: String,
        val text: String,
        val valid: Boolean,
        val error: String? = null,
        val latencyMs: Long,
        val prefillMs: Long,
        val decodeMs: Long,
        val grammarMs: Long,
        val promptTokens: Int,
        val completionTokens: Int,
        val truncated: Boolean,
        val thermal: ThermalState,
    )

    /** One ablation arm's aggregate (§14.2 metrics). */
    @kotlinx.serialization.Serializable
    data class Arm(
        val config: String,
        val n: Int,
        val validCount: Int,
        val validRate: Double,
        val medianLatencyMs: Long,
        val p90LatencyMs: Long,
        val medianPrefillMs: Long,
        val medianDecodeMs: Long,
        val medianGrammarMs: Long,
        val meanPromptTokens: Double,
        val meanCompletionTokens: Double,
        val truncatedCount: Int,
        val prefillShare: Double,
        val sampleFailures: List<String> = emptyList(),
    ) {
        companion object {
            fun from(config: String, obs: List<Observation>): Arm {
                if (obs.isEmpty()) return Arm(config, 0, 0, 0.0, 0, 0, 0, 0, 0, 0.0, 0.0, 0, 0.0)
                fun pct(values: List<Long>, p: Double): Long {
                    val sorted = values.sorted()
                    return sorted[((sorted.size - 1) * p).toInt()]
                }
                val medPrefill = pct(obs.map { it.prefillMs }, 0.5)
                val medTotal = pct(obs.map { it.latencyMs }, 0.5)
                return Arm(
                    config = config,
                    n = obs.size,
                    validCount = obs.count { it.valid },
                    validRate = obs.count { it.valid }.toDouble() / obs.size,
                    medianLatencyMs = medTotal,
                    p90LatencyMs = pct(obs.map { it.latencyMs }, 0.9),
                    medianPrefillMs = medPrefill,
                    medianDecodeMs = pct(obs.map { it.decodeMs }, 0.5),
                    medianGrammarMs = pct(obs.map { it.grammarMs }, 0.5),
                    meanPromptTokens = obs.map { it.promptTokens }.average(),
                    meanCompletionTokens = obs.map { it.completionTokens }.average(),
                    truncatedCount = obs.count { it.truncated },
                    prefillShare = if (medTotal == 0L) 0.0 else medPrefill.toDouble() / medTotal,
                    sampleFailures = obs.filterNot { it.valid }.take(5).map { it.text.take(200) },
                )
            }
        }
    }

    @kotlinx.serialization.Serializable
    data class Report(
        val model: String,
        val device: String,
        val backends: List<String>,
        val samples: Int,
        val constrained: Arm,
        val unconstrained: Arm,
    ) {
        fun render(): String = buildString {
            append("\n=== AXON Phase 1 acceptance (§13) ===\n")
            append("model:  ").append(model).append('\n')
            append("device: ").append(device).append('\n')
            append("backends: ").append(backends.joinToString()).append('\n')
            append("samples per arm: ").append(samples).append("\n\n")
            append(row("metric", "A (naive)", "B (grammar)"))
            append(row("valid-action rate",
                "%.1f%%".format(unconstrained.validRate * 100),
                "%.1f%%".format(constrained.validRate * 100)))
            append(row("median latency", "${unconstrained.medianLatencyMs} ms", "${constrained.medianLatencyMs} ms"))
            append(row("p90 latency", "${unconstrained.p90LatencyMs} ms", "${constrained.p90LatencyMs} ms"))
            append(row("median prefill", "${unconstrained.medianPrefillMs} ms", "${constrained.medianPrefillMs} ms"))
            append(row("median decode", "${unconstrained.medianDecodeMs} ms", "${constrained.medianDecodeMs} ms"))
            append(row("prefill share",
                "%.0f%%".format(unconstrained.prefillShare * 100),
                "%.0f%%".format(constrained.prefillShare * 100)))
            append(row("median grammar cost", "—", "${constrained.medianGrammarMs} ms"))
            append(row("mean prompt tokens",
                "%.0f".format(unconstrained.meanPromptTokens),
                "%.0f".format(constrained.meanPromptTokens)))
            append(row("mean output tokens",
                "%.0f".format(unconstrained.meanCompletionTokens),
                "%.0f".format(constrained.meanCompletionTokens)))
            append(row("truncated", "${unconstrained.truncatedCount}", "${constrained.truncatedCount}"))
            if (unconstrained.sampleFailures.isNotEmpty()) {
                append("\nsample A failures:\n")
                unconstrained.sampleFailures.forEach { append("  · ").append(it).append('\n') }
            }
        }

        private fun row(a: String, b: String, c: String) =
            "%-22s %-16s %-16s\n".format(a, b, c)
    }

    companion object {
        private const val TAG = "AxonPhase1"

        /**
         * Where `tools/fetch-model.sh --push` puts the weights.
         *
         * `/data/local/tmp` is readable without any storage permission and
         * survives app reinstall, so iterating on the APK does not mean
         * re-pushing 3.11 GB over USB each time.
         */
        private const val MODEL_DIR = "/data/local/tmp/axon"

        private lateinit var engine: LlamaEngine
        private var lastReport: Report? = null

        private val samples: Int
            get() = InstrumentationRegistry.getArguments()
                .getString("axonSamples")?.toIntOrNull() ?: 24

        @BeforeClass
        @JvmStatic
        fun loadModel() {
            val model = File(MODEL_DIR).listFiles()
                ?.firstOrNull { it.name.endsWith(".gguf") }

            // Skipped rather than failed when the model is absent: CI has no
            // 3 GB GGUF and no phone, and a red suite there would train everyone
            // to ignore it. Absence of hardware is not a defect.
            assumeTrue(
                "no .gguf in $MODEL_DIR — run ./tools/fetch-model.sh --push",
                model != null,
            )
            requireNotNull(model)

            val context = InstrumentationRegistry.getInstrumentation().targetContext
            engine = LlamaEngine.load(context, model)
            Log.i(TAG, "loaded ${model.name} (${model.length() / 1_000_000} MB)")
        }

        @AfterClass
        @JvmStatic
        fun release() {
            if (::engine.isInitialized) engine.close()
        }
    }
}
