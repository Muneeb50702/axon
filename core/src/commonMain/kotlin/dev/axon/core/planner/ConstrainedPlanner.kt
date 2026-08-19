package dev.axon.core.planner

import dev.axon.core.inference.ActionGrammar
import dev.axon.core.inference.Gbnf
import dev.axon.core.inference.InferenceEngine
import dev.axon.core.inference.ScreenGrammar
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

    /**
     * Restrict `target.value` to the labels actually on screen (E18).
     *
     * With this on, a hallucinated target is not caught by the precondition gate
     * — it is unreachable at the sampler. Off by default only so the ablation can
     * measure what it buys; on in the shipping configuration.
     */
    private val screenGrounded: Boolean = true,

    /**
     * Reports how the sampler was constrained on each step (E33).
     *
     * A callback rather than a log line because `:core` carries no logger and
     * should not acquire one; the Android layer wires this to logcat exactly as
     * it wraps the trace store to time it.
     */
    private val onGrounding: (Grounding) -> Unit = {},

    /**
     * Does this grammar parse? Optional, and worth wiring — **E33**.
     *
     * D9's failure mode: llama.cpp does not install a sampler for a grammar it
     * rejects and generation proceeds **unconstrained with no error**, so C3 is
     * silently absent. The app validates the base grammar once at load and has
     * never validated the screen grammar, which is rebuilt every step from live
     * app labels — the only one built from untrusted input, and the only one
     * that can fail in the field.
     */
    private val validateGrammar: ((Gbnf) -> Boolean)? = null,

    /**
     * Resolves "open X" goals to a package (E21).
     *
     * When it answers, the grammar collapses to the single correct action and
     * the model's judgement is removed from a decision it was measurably bad at.
     * Defaults to [AppResolver.NONE], so the narrowing is opt-in and the ablation
     * can measure it.
     */
    private val appResolver: AppResolver = AppResolver.NONE,
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

        // E21: if this is purely an app-launch request and the name resolves,
        // the correct action is determined without consulting the screen. Narrow
        // the grammar to exactly that action rather than asking a 1B model to
        // pick the right icon — which E18b measured it failing to do.
        val launchGrammar = if (constrained && failure == null) {
            AppIntent.appName(goal.utterance)
                ?.let { appResolver.resolve(it) }
                ?.let { ScreenGrammar.forAppLaunch(it) }
        } else {
            null
        }

        // One retry, and only for truncation.
        //
        // Retrying a *parse* failure would be pointless under a grammar: the
        // output cannot be malformed, so a parse failure means the grammar was
        // not applied, and retrying would produce the same unconstrained result.
        // Truncation is different — it is a length accident, and a second attempt
        // with a larger budget genuinely differs.
        repeat(2) { attempt ->
            // Rebuilt each step, because the screen changes. Parsing a
            // ~40-element grammar costs microseconds against a ~50 s step.
            val grammar = when {
                !constrained -> null
                launchGrammar != null -> launchGrammar
                screenGrounded -> ScreenGrammar.forScreen(state)
                else -> ActionGrammar.GBNF
            }

            // Report whether screen grounding actually applied — **E33**.
            //
            // D9's lesson is that a system relying on constrained decoding needs
            // a *positive* check that the constraint is active, because an
            // absent constraint looks exactly like a satisfied one. That check
            // existed for the base grammar (validated once at load) and not for
            // the grammar that is rebuilt every step.
            //
            // `ScreenGrammar.forScreen` falls back to the unconstrained base
            // when the projection offers no labels — a legitimate §17 escape for
            // a canvas or DRM surface, and indistinguishable in every log from
            // grounding that worked. On a search screen whose only interactable
            // element is an unlabelled text field, the planner is silently
            // unconstrained on `target`, which is exactly where a 1B model
            // invents elements (E18b).
            //
            // Cheap and diagnostic: one line per planning step saying whether
            // the sampler was narrowed and by how much.
            // Check that the constraint is installable BEFORE relying on it.
            val grammarParses = grammar?.let { g -> validateGrammar?.invoke(g) }

            onGrounding(
                when {
                    grammar != null && grammarParses == false ->
                        Grounding.GrammarRejected
                    grammar == null -> Grounding.Unconstrained
                    launchGrammar != null -> Grounding.LaunchCollapsed
                    !screenGrounded -> Grounding.BaseGrammar
                    ScreenGrammar.isSpecialised(grammar) ->
                        Grounding.ScreenGrounded(
                            labels = state.elements.count { it.label != null },
                            // Which production: E31's complete {by,value} pairs,
                            // or the older value-only grounding that lets the
                            // model pair a real label with a `by` that cannot
                            // resolve it. `isSpecialised` accepts both, so
                            // without this the log cannot tell them apart --
                            // and that distinction is exactly what a
                            // "no element matching text=X ... this screen has:
                            // X" failure turns on.
                            paired = "screen-target ::=" in grammar.source,
                        )
                    else -> Grounding.FellBackUngrounded(state.elements.size)
                },
            )

            val result = engine.generate(
                prompt = PlannerPrompt.SYSTEM + "\n\n" + prompt,
                grammar = grammar,
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
                // Did the sampler actually ENFORCE the grammar? — **E33**.
                //
                // Everything upstream reports that a paired, parseable screen
                // grammar was installed, and the planner still emitted targets
                // the precondition gate rejects with the self-contradictory
                // "no element matching text=X ... this screen has: X". Under
                // E31's grounding that pair is not in the alternation at all,
                // so either the constraint is not being applied or an
                // assumption about it is wrong.
                //
                // Checking the *output* against the same evidence that built
                // the grammar settles it without trusting either. A violation
                // means C3 is absent for that step while every other signal
                // says it is present — which is exactly the class of failure
                // D9 warns about and nothing else here could detect.
                val emitted = (action as? dev.axon.core.model.DeviceAction.Tap)?.target
                if (grammar != null && screenGrounded && launchGrammar == null && emitted != null) {
                    val permitted = state.elements.any {
                        it.label == emitted.value && it.labelBy == emitted.by
                    }
                    if (!permitted) {
                        onGrounding(
                            Grounding.GrammarViolated(
                                by = emitted.by.wire,
                                value = emitted.value,
                                permittedPairs = state.elements.count { it.labelBy != null },
                                // What the engine says it did. If a violation
                                // arrives with `constrained = true`, the engine
                                // believes it applied a sampler that plainly did
                                // not bind, which localises the fault to the
                                // JNI/llama.cpp layer rather than to how the
                                // grammar was built.
                                engineSaysConstrained = result.constrained,
                            ),
                        )
                    }
                }

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


/**
 * How the sampler was constrained for one planning step — **E33**.
 *
 * D9's lesson is that constrained decoding needs a *positive* check that the
 * constraint is active: a grammar llama.cpp rejects is not installed and
 * generation proceeds unconstrained **with no error**, so an absent constraint
 * is indistinguishable from a satisfied one. That check existed for the base
 * grammar, validated once at load, and not for the screen grammar, which is
 * rebuilt every step from live app labels.
 */
public sealed interface Grounding {
    /**
     * The sampler emitted a target the grammar did not permit — **E33**.
     *
     * Reported by checking the parsed action against the same element list the
     * grammar was built from. If this ever fires, the constraint was built,
     * parsed and passed to the engine and **still did not bind**: C3's
     * guarantee is absent for that step while every other diagnostic says it
     * holds.
     */
    public data class GrammarViolated(
        val by: String,
        val value: String,
        val permittedPairs: Int,
        val engineSaysConstrained: Boolean,
    ) : Grounding

    /**
     * The grammar was built and **llama.cpp will not install it**.
     *
     * Generation then runs unconstrained with no error (D9), so every guarantee
     * C3 makes is absent for that step while every log says a grammar was used.
     * The single most important thing this diagnostic can report.
     */
    public data object GrammarRejected : Grounding

    /** No grammar at all — §14.3's arm A/B configuration. */
    public data object Unconstrained : Grounding

    /** E21: the goal named an app, so the only legal action is launching it. */
    public data object LaunchCollapsed : Grounding

    /** Shape constrained, targets free. Screen grounding was switched off. */
    public data object BaseGrammar : Grounding

    /**
     * Targets restricted to [labels] elements actually on screen (E18).
     *
     * [paired] distinguishes E31's complete `{by, value}` grounding from the
     * older value-only form, which permits a real on-screen label paired with a
     * selector kind that cannot resolve it.
     */
    public data class ScreenGrounded(val labels: Int, val paired: Boolean) : Grounding

    /**
     * Screen grounding was **asked for and did not apply**.
     *
     * The projection offered no usable labels, so `ScreenGrammar` returned the
     * unconstrained base — a legitimate §17 escape for a canvas or DRM surface,
     * and the dangerous case everywhere else. On a search screen whose only
     * interactable element is an unlabelled text field, the planner is free to
     * invent a target, which is precisely the failure E18b measured.
     *
     * [interactables] is how many elements the planner *was* shown, so a reader
     * can tell "empty screen" from "screen full of unlabelled controls".
     */
    public data class FellBackUngrounded(val interactables: Int) : Grounding
}
