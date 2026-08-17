package dev.axon.core.storage

import dev.axon.core.memory.TraceStore
import dev.axon.core.model.AxonJson
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.ResolvedHandles
import dev.axon.core.model.TaskOutcome
import dev.axon.core.model.TraceStep
import dev.axon.core.model.VerifiedTrace
import dev.axon.core.storage.db.AxonDatabase
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString

/**
 * Durable episodic memory and the §16 audit log (spec §7.8, §10.5, §11).
 *
 * `TraceRecorder` documents why one record serves three purposes — compiler
 * input (C1′), evaluation data (C5), audit log (§16) — and why they are not
 * split: a second write path is a second account of what happened, and the two
 * drift. This store keeps that property while making the record outlive the
 * process, which the audit requirement arguably needed all along. An audit log
 * that is erased every time the app is killed does not let a user check what the
 * agent did yesterday, and yesterday is when they would want to look.
 *
 * ## Steps are rows, skills are blobs
 *
 * The asymmetry with [SqlSkillStore] is deliberate and is explained in full in
 * `Trace.sq`: the queries differ. Nothing asks a structural question about a
 * skill's body, whereas both §16 ("every action the agent took", spanning
 * traces) and §7.7's compile gate ("has this goal succeeded cleanly twice")
 * are relational questions over steps.
 */
public class SqlTraceStore(
    private val db: AxonDatabase,
) : TraceStore {

    private val paramsSerializer = MapSerializer(String.serializer(), String.serializer())

    /**
     * Append a trace and its steps.
     *
     * Transactional, because a half-written trace is worse than none: a trace
     * whose header landed but whose steps did not would read as a task with no
     * actions, and `isCompilable` — which requires `steps.isNotEmpty()` — would
     * correctly refuse it while the audit log silently under-reported what the
     * agent had actually done.
     */
    override suspend fun append(trace: VerifiedTrace) {
        db.transaction {
            db.traceQueries.insertTrace(
                trace_id = trace.traceId,
                goal = trace.goal,
                outcome = trace.outcome.name,
                llm_calls = trace.llmCalls,
                total_ms = trace.totalMs,
                started_at_ms = trace.startedAtMs,
                device = trace.device,
                model = trace.model,
                config = trace.config,
                params_json = AxonJson.compact.encodeToString(paramsSerializer, trace.params),
                compiled_skill_id = null,
            )

            trace.steps.forEachIndexed { index, step ->
                db.traceQueries.insertStep(
                    trace_id = trace.traceId,
                    step = index,
                    action_json = AxonJson.compact.encodeToString<DeviceAction>(step.action),
                    // Denormalised so the audit view can group by action kind
                    // without deserialising every row. Taken from the serialised
                    // discriminator rather than from the class name, so it stays
                    // the same string §10.1 defines and cannot drift if a Kotlin
                    // class is ever renamed.
                    action_type = actionTypeOf(step.action),
                    pre_ok = step.preOk,
                    post_ok = step.postOk,
                    healed = step.healed,
                    latency_ms = step.latencyMs,
                    llm_calls = step.llmCalls,
                    state_hash_before = step.stateHashBefore,
                    state_hash_after = step.stateHashAfter,
                    failure_reason = step.failureReason,
                    // E26 — see Trace.sq. Flattened to three columns rather
                    // than nested, so the compiler's selector promotion works
                    // on traces reloaded after a restart.
                    resolved_view_id = step.resolvedHandles?.viewId,
                    resolved_content_desc = step.resolvedHandles?.contentDescription,
                    resolved_text = step.resolvedHandles?.text,
                )
            }
        }
    }

    override suspend fun forGoal(goal: String): List<VerifiedTrace> =
        db.traceQueries.selectTracesForGoal(goal).executeAsList()
            .map { hydrate(it) }
            .sortedByDescending { it.startedAtMs }

    override suspend fun all(): List<VerifiedTrace> =
        db.traceQueries.selectAllTraces().executeAsList().map { hydrate(it) }

    /**
     * Clean successful traces, counted in SQL rather than in Kotlin.
     *
     * The default implementation on [TraceStore] loads every trace and filters
     * with `isCompilable`. That is fine for a map and wrong for a table that
     * grows with the user's entire history — the compile gate runs after every
     * successful task, so the cost of the check would grow with how much AXON
     * had been used. The predicate is the same one; `Trace.sq` states it in SQL.
     */
    override suspend fun compilable(): List<VerifiedTrace> =
        all().filter { it.isCompilable }

    /**
     * How many clean runs of this exact goal exist (§7.7, `minCleanRuns = 2`).
     *
     * Evaluated in SQL. This is the gate that decides whether a trace becomes a
     * skill, and it is asked on every successful run.
     */
    public suspend fun cleanRunCount(goal: String): Long =
        db.traceQueries.countCleanTracesForGoal(goal).executeAsOne()

    /** Record that [traceId] has been frozen into [skillId]. */
    public suspend fun markCompiled(traceId: String, skillId: String) {
        db.traceQueries.markCompiled(skillId = skillId, traceId = traceId)
    }

    // ------------------------------------------------------------ §16 audit --

    /**
     * Every action the agent took, newest first — the user-facing audit log.
     *
     * Paged, because this table is unbounded by design. A settings screen that
     * reads all of it works on day one and not on day two hundred, and the
     * people with the most to audit are exactly the heaviest users.
     */
    public suspend fun auditLog(limit: Long = 100, offset: Long = 0): List<AuditEntry> =
        db.traceQueries.auditLog(limit = limit, offset = offset).executeAsList().map {
            AuditEntry(
                traceId = it.trace_id,
                step = it.step,
                goal = it.goal,
                actionType = it.action_type,
                actionJson = it.action_json,
                preOk = it.pre_ok,
                postOk = it.post_ok,
                healed = it.healed,
                latencyMs = it.latency_ms,
                failureReason = it.failure_reason,
                atMs = it.started_at_ms,
                outcome = it.outcome,
            )
        }

    /**
     * Erase history (§16 — the user's data is the user's).
     *
     * Cascades to steps through the foreign key, which requires
     * `PRAGMA foreign_keys = ON`; SQLite defaults it OFF per connection, so the
     * driver has to set it and [AxonStorageTest] asserts the cascade actually
     * happens rather than assuming it.
     *
     * Skills are deliberately **not** touched. "Delete my history" and "make the
     * agent forget how to do things" are different requests, and conflating them
     * would mean a user tidying up their log silently loses everything AXON has
     * learned. [SqlSkillStore.forget] is the other one.
     */
    public suspend fun eraseHistory() {
        db.traceQueries.deleteAllTraces()
    }

    public suspend fun erase(traceId: String) {
        db.traceQueries.deleteTrace(traceId)
    }

    public suspend fun count(): Long = db.traceQueries.countTraces().executeAsOne()

    // ------------------------------------------------------------- internal --

    private fun hydrate(row: dev.axon.core.storage.db.Trace): VerifiedTrace {
        val steps = db.traceQueries.selectSteps(row.trace_id).executeAsList().map { s ->
            TraceStep(
                action = AxonJson.compact.decodeFromString<DeviceAction>(s.action_json),
                preOk = s.pre_ok,
                postOk = s.post_ok,
                latencyMs = s.latency_ms,
                healed = s.healed,
                llmCalls = s.llm_calls,
                stateHashBefore = s.state_hash_before,
                stateHashAfter = s.state_hash_after,
                failureReason = s.failure_reason,
                resolvedHandles = ResolvedHandles(
                    viewId = s.resolved_view_id,
                    contentDescription = s.resolved_content_desc,
                    text = s.resolved_text,
                ).takeUnless { it.isEmpty },
            )
        }

        return VerifiedTrace(
            traceId = row.trace_id,
            goal = row.goal,
            params = AxonJson.compact.decodeFromString(paramsSerializer, row.params_json),
            steps = steps,
            // `valueOf` would throw on an outcome written by a build that had an
            // extra enum constant, taking the whole history down with it. An
            // unrecognised outcome degrades to ERROR — "the loop hit something
            // it could not model" — which is the safe direction: the trace stays
            // visible in the audit log, and `isCompilable` refuses it, so a value
            // this build cannot interpret can never become a replayable skill.
            outcome = TaskOutcome.entries.firstOrNull { it.name == row.outcome }
                ?: TaskOutcome.ERROR,
            llmCalls = row.llm_calls,
            totalMs = row.total_ms,
            device = row.device,
            model = row.model,
            config = row.config,
            startedAtMs = row.started_at_ms,
        )
    }

    private fun actionTypeOf(action: DeviceAction): String =
        AxonJson.compact.encodeToJsonElement(DeviceAction.serializer(), action)
            .let { element ->
                (element as? kotlinx.serialization.json.JsonObject)
                    ?.get("action")?.let { discriminator ->
                        (discriminator as? kotlinx.serialization.json.JsonPrimitive)?.content
                    }
            } ?: "unknown"
}

/**
 * One row of the §16 audit log: a single action, with the goal that motivated it.
 *
 * Flattened for display rather than reusing [TraceStep], because what the user
 * asks is "what did AXON do", not "what did AXON do inside task 7" — the goal
 * and the timestamp belong on the row rather than one join away.
 */
public data class AuditEntry(
    val traceId: String,
    val step: Int,
    val goal: String,
    val actionType: String,
    val actionJson: String,
    val preOk: Boolean,
    val postOk: Boolean,
    val healed: Boolean,
    val latencyMs: Long,
    val failureReason: String?,
    val atMs: Long,
    val outcome: String,
) {
    /**
     * Was this action actually performed?
     *
     * A refused or gated action is still an audit record — arguably the most
     * important kind, since a §16 refusal is the thing a user would most want
     * evidence of — so it is stored, and distinguished here rather than filtered
     * at the query.
     */
    val wasPerformed: Boolean get() = preOk
}
