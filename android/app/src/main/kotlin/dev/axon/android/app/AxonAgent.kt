package dev.axon.android.app

import android.content.Context
import android.util.Log
import dev.axon.android.driver.AccessibilityDriver
import dev.axon.android.driver.AxonAccessibilityService
import dev.axon.android.driver.PackageAppResolver
import dev.axon.android.inference.LlamaEngine
import dev.axon.core.executor.ConfirmationGate
import dev.axon.core.executor.ConfirmationReason
import dev.axon.core.executor.DefaultExecutor
import dev.axon.core.model.CompactState
import dev.axon.core.model.Goal
import dev.axon.core.model.TaskResult
import dev.axon.core.memory.CompilationPolicy
import dev.axon.core.memory.TraceRecorder
import dev.axon.core.planner.AppIntent
import dev.axon.core.planner.ConstrainedPlanner
import dev.axon.core.runtime.AxonRuntime
import dev.axon.core.runtime.RunOutcome
import dev.axon.core.skills.DefaultSkillCompiler
import dev.axon.core.storage.SqlSkillStore
import dev.axon.core.storage.SqlTraceStore
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

    /**
     * App-name → package lookup (E21), hoisted to a field.
     *
     * Used twice now, and both uses must agree. It decides whether the grammar
     * collapses to a single `launch_app`, and it decides what "finished" means
     * for that goal (E21b). Two instances would be two answers, and the failure
     * would be a task that narrows to launching one app while waiting for a
     * different one to appear.
     */
    private val appResolver = PackageAppResolver(context.applicationContext)

    /**
     * Skills and traces, shared across every task — and now across every launch.
     *
     * SQLite per §11. Until this, learning survived a task but not a restart, so
     * "the system gets faster the more it is used" carried a silent qualifier:
     * *within one run of the app*. On this hardware that qualifier bites hard,
     * because the process does not only end when the user closes it — the OEM
     * power manager SIGKILLs sustained foreground compute after ~7 minutes (E6).
     * A user who taught AXON a task and came back to it later found it had
     * learned nothing.
     */
    private val db = AndroidAxonStorage.database(context)
    private val skills = SqlSkillStore(db, nowMs = System::currentTimeMillis)
    private val traces = SqlTraceStore(db)

    /**
     * The trace store, timed (E22b).
     *
     * Wrapped here rather than instrumented inside `:core`, which has no
     * logger and should not acquire one. The cost being measured is the write
     * at the end of every task — one transaction, one row per action — and the
     * question is whether it is perceptible on a Helio G85 after the ~60 s the
     * user just waited for planning. It should be lost in the noise; that is a
     * prediction, and E22b is where it gets checked rather than assumed.
     */
    private val timedTraces = object : dev.axon.core.memory.TraceStore {
        override suspend fun append(trace: dev.axon.core.model.VerifiedTrace) {
            val startedAt = System.currentTimeMillis()
            traces.append(trace)
            Log.i(
                TAG,
                "E22b trace write: ${trace.steps.size} step(s) in " +
                    "${System.currentTimeMillis() - startedAt} ms",
            )
        }

        override suspend fun forGoal(goal: String) = traces.forGoal(goal)
        override suspend fun all() = traces.all()
        override suspend fun compilable() = traces.compilable()
    }

    /**
     * Trace ids, unique across processes.
     *
     * Was a bare counter — `trace-0`, `trace-1` — which was harmless while the
     * store was a list in memory and actively destructive the moment it became a
     * table with `trace_id` as its primary key: every launch restarted at zero,
     * so the second session's first trace silently overwrote the first
     * session's, and the user's history would have quietly eaten itself one run
     * at a time.
     *
     * The launch timestamp disambiguates sessions; the counter disambiguates
     * within one. Not a UUID because `:core` has no UUID primitive and this
     * needs no unguessability — only uniqueness.
     */
    private val sessionId = System.currentTimeMillis()
    private var traceSeq = 0

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
     *   self-assessment C2 exists to replace. `null` means the caller has no
     *   oracle; AXON then derives one **only** where it can do so without the
     *   model's judgement (see below), and otherwise relies on the budgets,
     *   which is honest about the fact that the agent does not know.
     */
    suspend fun run(
        goal: Goal,
        goalReached: (suspend (CompactState) -> Boolean)? = null,
    ): Result<TaskResult> = withContext(Dispatchers.Default) {
        runCatching {
            val eng = engine ?: error("model not loaded")
            check(isServiceEnabled) { "accessibility service is not enabled" }

            // E21b. A pure "open X" goal is one of the few whose completion test
            // is determinable without asking anything: X is in the foreground.
            // Measured before this existed, "open whatsapp" launched WhatsApp
            // six times and ended in BUDGET_EXHAUSTED at 426 s — every step
            // correct, every assertion satisfied, and no way to notice it had
            // finished at step one.
            val oracle = goalReached
                ?: AppIntent.launchOracle(goal.utterance, appResolver)
                ?: { false }

            val runtime = AxonRuntime(
                driver = driver,
                planner = ConstrainedPlanner(
                    engine = eng,
                    // E21: "open X" resolves to a package, and the grammar then
                    // collapses to that single action. E18b measured a 1B model
                    // failing exactly this choice — it opened the dialer.
                    appResolver = appResolver,
                ),
                executor = DefaultExecutor(
                    driver,
                    nowMs = System::currentTimeMillis,
                    confirmation = confirmationGate,
                ),
                skills = skills,
                traces = timedTraces,
                compiler = DefaultSkillCompiler(),
                recorder = TraceRecorder(
                    device = driver.deviceFamily,
                    model = eng.modelId,
                    newId = { "trace-$sessionId-${traceSeq++}" },
                    nowMs = System::currentTimeMillis,
                ),
                nowMs = System::currentTimeMillis,
                compilationPolicy = CompilationPolicy(minCleanRuns = 2),
                goalReached = oracle,
            )

            _state.value = _state.value.copy(running = true, status = "running: ${goal.utterance}")
            val outcome: RunOutcome = runtime.execute(goal)
            val result = outcome.result

            _state.value = _state.value.copy(
                running = false,
                status = buildString {
                    append(result.outcome).append(" via ").append(outcome.path)
                    append(" — ").append(result.steps.size).append(" steps, ")
                    // The C1′ headline, surfaced where a demo can point at it.
                    append(result.llmCalls).append(" model calls, ")
                    append(result.totalMs / 1000).append("s")
                    outcome.compiled?.let { append(" · compiled skill '").append(it).append("'") }
                },
                lastResult = result,
                lastPath = outcome.path.name,
                skillCount = skills.all().size,
            )
            result
        }.onFailure { e ->
            Log.e(TAG, "task failed", e)
            _state.value = _state.value.copy(running = false, status = "error: ${e.message}")
        }
    }

    /**
     * Load the count of remembered skills into [state].
     *
     * Called at launch, before anything else happens, because it is the one
     * place a user can *see* that §11 works: open the app after a reboot and it
     * already says how many tasks it knows. Reading it only after a run — as the
     * UI did while the store was in memory, when there was nothing to read at
     * startup — would hide the property from exactly the moment that
     * demonstrates it.
     *
     * Cheap: one indexed `count(*)`, plus the lazy hydrate that the first task
     * would have paid for anyway.
     */
    suspend fun refreshLearned() = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        runCatching { skills.all().size }
            .onSuccess { count ->
                // E22b: the one persistence cost a user could actually feel.
                // Hydrating deserialises every stored skill, so this grows with
                // how much AXON has been taught — logged rather than assumed,
                // because "it is only a count(*)" is the kind of claim that
                // stops being true at a scale nobody tested.
                Log.i(
                    TAG,
                    "E22b hydrate: $count skill(s) in " +
                        "${System.currentTimeMillis() - startedAt} ms" +
                        (skills.unreadableOnLoad.takeIf { it > 0 }
                            ?.let { ", $it UNREADABLE" } ?: ""),
                )
                _state.value = _state.value.copy(skillCount = count)
            }
            .onFailure { Log.e(TAG, "could not read learned skills", it) }
        Unit
    }

    /**
     * Every action AXON has ever taken, newest first (§16).
     *
     * The audit log is a promise the project makes in its README and its safety
     * section; before persistence it could only ever have shown the current
     * session, which is not an audit log so much as a status display.
     */
    suspend fun auditLog(limit: Long = 200) = withContext(Dispatchers.IO) {
        runCatching { traces.auditLog(limit) }.getOrDefault(emptyList())
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

    /** PLAN, REPLAY or REPLAY_WITH_REPAIR — which path served the last run. */
    val lastPath: String? = null,

    /** Skills AXON remembers — across launches, not just this session (§11). */
    val skillCount: Int = 0,
)
