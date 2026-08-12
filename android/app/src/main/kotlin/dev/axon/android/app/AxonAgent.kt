package dev.axon.android.app

import android.content.Context
import android.util.Log
import dev.axon.android.driver.AccessibilityDriver
import dev.axon.android.driver.AxonAccessibilityService
import dev.axon.android.inference.LlamaEngine
import dev.axon.core.executor.ConfirmationGate
import dev.axon.core.executor.ConfirmationReason
import dev.axon.core.executor.DefaultExecutor
import dev.axon.core.model.CompactState
import dev.axon.core.model.Goal
import dev.axon.core.model.TaskResult
import dev.axon.core.planner.ConstrainedPlanner
import dev.axon.core.runtime.DefaultAgentRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The whole agent, assembled (spec §7.2).
 *
 * This is the only place in the project where every layer meets:
 *
 * ```
 *   LlamaEngine ──▶ ConstrainedPlanner ──┐
 *                                        ├──▶ DefaultAgentRuntime
 *   AccessibilityDriver ──▶ DefaultExecutor ──┘
 * ```
 *
 * Everything above the driver is `:core` and has been under test since before
 * the model existed; everything below is Android. Assembly is the last step
 * rather than the first, and the ordering is the argument: if the loop had been
 * written against the phone, none of its safety properties could have been
 * checked without one.
 *
 * ## Loading is deliberately explicit
 *
 * The model is not loaded in a constructor or lazily on first use. Loading takes
 * seconds and hundreds of megabytes, and doing it implicitly means it happens at
 * whatever moment the user first taps something — which on this hardware reads
 * as the app freezing. [load] is a suspend function the caller schedules, and
 * [state] reports progress, so the cost is visible rather than mysterious.
 */
class AxonAgent(private val context: Context) {

    private var engine: LlamaEngine? = null
    private val driver = AccessibilityDriver(context.applicationContext)

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    /**
     * How the user is asked to approve an irreversible action (§16).
     *
     * Defaults to refusing. Until a UI is wired in, AXON declines to place calls,
     * send messages or spend money rather than doing them unasked — the correct
     * direction to fail, since only one of those two mistakes can be undone.
     *
     * Phase 6 replaces this with a notification action, which is what §16's
     * "explicit confirmation" needs to mean when the agent is operating another
     * app and its own UI is not on screen.
     */
    var confirmationGate: ConfirmationGate = ConfirmationGate.DENY

    /** The approval currently awaiting the user, if any. */
    private val _pending = MutableStateFlow<ConfirmationReason?>(null)
    val pending: StateFlow<ConfirmationReason?> = _pending.asStateFlow()

    val isServiceEnabled: Boolean get() = AxonAccessibilityService.isConnected
    val isModelLoaded: Boolean get() = engine != null

    /**
     * Locate the side-loaded GGUF.
     *
     * Weights live in `/data/local/tmp/axon` because AXON holds no `INTERNET`
     * permission and cannot fetch them (D7). That is the cost of making "screen
     * contents cannot leave the device" an OS-enforced property rather than a
     * promise, and it is the right trade — but it does mean a first-run
     * experience that requires adb, which the UI has to explain rather than hide.
     */
    fun findModel(): File? = File(MODEL_DIR).listFiles()
        ?.filter { it.name.endsWith(".gguf") }
        ?.minByOrNull { it.length() }

    suspend fun load(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val file = findModel() ?: error(
                "No model in $MODEL_DIR. Push one with ./tools/fetch-model.sh --push",
            )
            _state.value = _state.value.copy(status = "loading ${file.name}…")

            val loaded = LlamaEngine.load(context, file)

            // Validated at load rather than on first use. A grammar llama.cpp
            // rejects is not installed as a sampler, and generation then runs
            // *unconstrained with no error* — C3 silently absent. Discovering
            // that on the first planning step, 60 s in, would be miserable; here
            // it costs a millisecond.
            val grammarOk = loaded.validateGrammar(dev.axon.core.inference.ActionGrammar.GBNF)
            check(grammarOk) { "the §10.6 action grammar was rejected by llama.cpp" }

            engine = loaded
            _state.value = _state.value.copy(
                status = "ready — ${loaded.modelId}",
                modelId = loaded.modelId,
            )
        }.onFailure { e ->
            Log.e(TAG, "model load failed", e)
            _state.value = _state.value.copy(status = "load failed: ${e.message}")
        }
    }

    /**
     * Run a goal to completion, escalation, or budget exhaustion.
     *
     * @param goalReached the success oracle. Supplied by the caller because AXON
     *   has no way to know when an open-ended request is finished, and inventing
     *   one would mean asking the model whether it had succeeded — exactly the
     *   self-assessment C2 exists to replace. A caller with no oracle passes one
     *   that never fires and relies on the budgets, which is honest about the
     *   fact that the agent does not know.
     */
    suspend fun run(
        goal: Goal,
        goalReached: suspend (CompactState) -> Boolean = { false },
    ): Result<TaskResult> = withContext(Dispatchers.Default) {
        runCatching {
            val eng = engine ?: error("model not loaded")
            check(isServiceEnabled) { "accessibility service is not enabled" }

            val runtime = DefaultAgentRuntime(
                driver = driver,
                planner = ConstrainedPlanner(eng),
                executor = DefaultExecutor(
                    driver,
                    nowMs = System::currentTimeMillis,
                    confirmation = confirmationGate,
                ),
                nowMs = System::currentTimeMillis,
                goalReached = goalReached,
            )

            _state.value = _state.value.copy(running = true, status = "running: ${goal.utterance}")
            val result = runtime.execute(goal)

            _state.value = _state.value.copy(
                running = false,
                status = "${result.outcome} — ${result.steps.size} steps, " +
                    "${result.llmCalls} model calls, ${result.totalMs / 1000}s",
                lastResult = result,
            )
            result
        }.onFailure { e ->
            Log.e(TAG, "task failed", e)
            _state.value = _state.value.copy(running = false, status = "error: ${e.message}")
        }
    }

    fun close() {
        engine?.close()
        engine = null
    }

    private companion object {
        const val TAG = "AxonAgent"
        const val MODEL_DIR = "/data/local/tmp/axon"
    }
}

data class AgentState(
    val status: String = "not loaded",
    val modelId: String? = null,
    val running: Boolean = false,
    val lastResult: TaskResult? = null,
)
