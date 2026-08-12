package dev.axon.core.planner

import dev.axon.core.inference.ActionGrammar
import dev.axon.core.inference.InferenceEngine
import dev.axon.core.model.ActionMenu
import dev.axon.core.model.AxonJson
import dev.axon.core.model.CompactState
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.Goal

/**
 * The grammar-constrained planner (spec §7.4, §9.2) — the only LLM caller in the
 * loop.
 *
 * Selects one action per step. Never executes, never verifies, never retries the
 * device: §7.4 is explicit that execution and state transitions belong to the
 * executor, and keeping the deciding and the doing in different objects is what
 * lets the majority of AXON be tested with no model at all.
 *
 * ## What the grammar does and does not buy
 *
 * Under [ActionGrammar], malformed output is unreachable — the sampler cannot
 * walk a token path that leaves the grammar. That eliminates one failure class
 * completely, and it is worth being precise about which one:
 *
 *  - **eliminated:** invalid JSON, invented action types, illegal field
 *    combinations, a `launch_app` whose package is not a package name;
 *  - **not eliminated:** truncation, and choosing the wrong element.
 *
 * Both survivors are handled explicitly below rather than hoped away.
 */
public class ConstrainedPlanner(
    private val engine: InferenceEngine,

    /**
     * Token budget for one action.
     *
     * Generous by design. A grammar constrains shape, not length, so a model that
     * exhausts its budget mid-object produces a valid *prefix* and nothing more —
     * §7.4's explicit caveat. The cost of a loose cap is nothing (the grammar
     * stops generation at the closing brace anyway); the cost of a tight one is a
     * wasted retry at ~50 s per call on the target hardware.
     */
    private val maxTokens: Int = 192,

    /**
     * Whether the grammar is applied at all.
     *
     * Present so the §14.3 ablation can turn exactly one thing off. Arms A and B
     * differ in this flag and nothing else — same prompt, same sampling, same
     * parser — which is what makes the comparison attributable to the grammar
     * rather than to two subtly different programs.
     */
    private val constrained: Boolean = true,
) : Planner {

    override suspend fun nextAction(
        state: CompactState,
        goal: Goal,
        menu: ActionMenu,
        failure: FailureContext?,
    ): PlanDecision {
        var calls = 0
        var retries = 0
        var elapsed = 0L

        val prompt = PlannerPrompt.user(state, goal, menu, failure)

        // One retry, and only for truncation.
        //
        // Retrying a *parse* failure would be pointless under a grammar: the
        // output cannot be malformed, so a parse failure means the grammar was
        // not applied, and retrying would produce the same unconstrained result.
        // Truncation is different — it is a length accident, and a second attempt
        // with a larger budget genuinely differs.
        repeat(2) { attempt ->
            val result = engine.generate(
                prompt = PlannerPrompt.SYSTEM + "\n\n" + prompt,
                grammar = if (constrained) ActionGrammar.GBNF else null,
                maxTokens = if (attempt == 0) maxTokens else maxTokens * 2,
            )
            calls++
            elapsed += result.latencyMs

            if (result.truncated) {
                retries++
                return@repeat
            }

            val action = parse(result.text)
            if (action != null) {
                return PlanDecision(
                    action = action,
                    llmCalls = calls,
                    latencyMs = elapsed,
                    retries = retries,
                )
            }
            retries++
        }

        error(
            "planner produced no parseable action after $calls calls " +
                "(constrained=$constrained). Under a grammar this indicates the " +
                "sampler was not applied — check that the GBNF parsed.",
        )
    }

    /**
     * Parse a completion into an action.
     *
     * Extracts the outermost JSON object before parsing. Under a grammar this is
     * a no-op, because the root production begins with `{`. It matters for the
     * unconstrained ablation arm, where a model routinely prefixes prose — and
     * doing the extraction there is deliberate fairness: §14.3 exists to show the
     * grammar arm winning on *well-formedness*, and an arm penalised for saying
     * hello first would be a straw man.
     */
    private fun parse(text: String): DeviceAction? {
        val json = extractObject(text.trim()) ?: return null
        return runCatching {
            AxonJson.strict.decodeFromString(DeviceAction.serializer(), json)
        }.getOrNull()
    }

    private fun extractObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null

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
        return null
    }
}
