package dev.axon.core.skills

import dev.axon.core.model.CompiledSkill
import dev.axon.core.model.CompiledStep
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.SkillManifest
import dev.axon.core.model.SkillParameter
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.model.TraceStep
import dev.axon.core.model.VerifiedTrace
import dev.axon.core.model.ResolvedHandles

/**
 * Turns a verified trace into a replayable skill (spec §7.7) — **C1′**.
 *
 * ## What is and is not novel here
 *
 * Trace-to-skill compilation is not a new idea; SkillDroid (arXiv 2604.14872,
 * April 2026) does it for Android GUI agents, and a 2026 cluster does it for
 * agents generally. See `docs/RELATED_WORK.md` — AXON does not claim to have
 * invented this.
 *
 * What differs is the *regime*. Those systems call cloud-scale models, where
 * replay removes a network round-trip. Here planning costs ~60 s and ~83 J per
 * step on the target hardware (E2, E15), so compilation is not an optimisation —
 * it is what lets a multi-step task finish at all, and what makes the difference
 * between ~138 tasks per battery charge and effectively unbounded.
 *
 * ## The algorithm (§7.7)
 *
 * 1. take a trace that reached the goal with every post-condition satisfied;
 * 2. parameterise the variable parts into typed slots;
 * 3. convert each step to `{selector, action, expected_assertion, llm_fallback}`;
 * 4. key it by a goal-pattern for semantic matching.
 *
 * Step 3 is transcription. **Step 2 is where the work is**, and it is what
 * separates compiling a skill from recording a macro: deciding that the tap on
 * "Ammi" was a *parameter* and the tap on "Send" was *structure*. Get it wrong
 * one way and the skill only ever messages Ammi; wrong the other way and it types
 * the contact's name into the message box.
 *
 * Contains no LLM calls (§8). Compilation is deterministic, so the same trace
 * always yields the same skill — which means a compiled skill can be diffed,
 * reviewed and reproduced, and is therefore defensible as a research artefact
 * rather than an opaque artefact of one lucky run.
 */
public class DefaultSkillCompiler(
    /**
     * Which handle a recorded selector is rewritten to (E26, measured by E26b).
     *
     * A parameter rather than a constant because E26 shipped on reasoning and
     * was never measured, and §14.3's rule is that turning a feature off must
     * remove exactly that feature. `SkillDriftStudy` compiles the same trace
     * under each policy and replays them against the same drifted screens;
     * without this it would hand-build the alternatives and measure a
     * reconstruction rather than the compiler.
     */
    private val selectorPolicy: SelectorPolicy = SelectorPolicy.STURDIEST,
) : SkillCompiler {

    override suspend fun compile(trace: VerifiedTrace): CompileResult {
        if (!trace.isCompilable) {
            return CompileResult.Rejected(rejectionReason(trace))
        }

        val slots = inferSlots(trace)
        val steps = trace.steps.mapIndexed { index, step ->
            compileStep(index + 1, step, slots, trace)
        }

        val manifest = SkillManifest(
            id = skillId(trace.goal),
            name = trace.goal.replaceFirstChar { it.uppercase() },
            goalPattern = goalPattern(trace, slots),
            parameters = slots.map { (name, slot) ->
                SkillParameter(name = name, type = slot.type, required = true)
            },
            requiredCapabilities = requiredCapabilities(trace),
            deviceFamily = trace.device,
        )

        val problems = manifest.validate()
        if (problems.isNotEmpty()) {
            return CompileResult.Rejected("generated manifest is invalid: ${problems.joinToString("; ")}")
        }

        return CompileResult.Compiled(
            CompiledSkill(
                manifest = manifest,
                steps = steps,
                sourceTraces = listOf(trace.traceId),
            ),
        )
    }

    /**
     * Improve a skill using a second trace of the same goal.
     *
     * §7.7 names diffing repeated runs as the intended slot-inference mechanism,
     * and it is far more reliable than inferring from one run: two traces that
     * differ only in their parameter values reveal exactly which steps were
     * variable. Single-trace inference (below) is a heuristic; **this is
     * evidence**.
     */
    override suspend fun refine(existing: CompiledSkill, trace: VerifiedTrace): CompileResult {
        if (!trace.isCompilable) return CompileResult.Rejected(rejectionReason(trace))

        val fresh = when (val r = compile(trace)) {
            is CompileResult.Compiled -> r.skill
            is CompileResult.Rejected -> return r
        }

        if (fresh.steps.size != existing.steps.size) {
            // A different number of steps means a different route to the goal,
            // not a parameter variation. Merging them would produce a skill that
            // matches neither run.
            return CompileResult.Rejected(
                "step count differs (${existing.steps.size} vs ${fresh.steps.size}); " +
                    "this is a different route, not a parameterisation",
            )
        }

        // Where two runs of the same goal used *different* selector values, that
        // position was variable — a parameter. Where they agree, it is structure.
        // This is the inference the spec asks for, and it needs no heuristics.
        val merged = existing.steps.zip(fresh.steps).mapIndexed { i, (a, b) ->
            val differs = a.selector != null && b.selector != null && a.selector != b.selector
            if (differs && a.bindings.isEmpty()) {
                a.copy(
                    bindings = mapOf("value" to inferSlotName(a.selector.value, b.selector!!.value, i)),
                    llmFallback = true,
                )
            } else {
                a
            }
        }

        return CompileResult.Compiled(
            existing.copy(
                steps = merged,
                sourceTraces = existing.sourceTraces + trace.traceId,
            ),
        )
    }

    // -----------------------------------------------------------------

    /**
     * Which parts of the trace were parameters, inferred from a single run.
     *
     * The honest description is *pattern matching against the recorded goal
     * parameters*. A trace carries the slots the caller supplied (`{"contact":
     * "Ammi", "message": "on my way"}`), so a step whose selector or typed text
     * equals one of those values is a parameter occurrence.
     *
     * This is weaker than the diff-based inference in [refine] and is labelled as
     * such: it can only find parameters the caller already named. A trace with no
     * declared params compiles to a fully literal skill — correct, replayable,
     * and useful only for the exact task it recorded. §7.7 anticipates this,
     * which is why refinement exists.
     */
    private fun inferSlots(trace: VerifiedTrace): Map<String, Slot> =
        trace.params.mapValues { (name, value) -> Slot(value, typeOf(name, value)) }

    private fun compileStep(
        number: Int,
        step: TraceStep,
        slots: Map<String, Slot>,
        trace: VerifiedTrace,
    ): CompiledStep {
        val action = step.action
        val selector = action.target

        // A selector whose value equals a recorded parameter is that parameter.
        val selectorSlot = selector?.let { s -> slots.entries.firstOrNull { it.value.value == s.value } }
        val textSlot = (action as? DeviceAction.InputText)
            ?.let { t -> slots.entries.firstOrNull { it.value.value == t.text } }

        val bindings = buildMap {
            selectorSlot?.let { put("selector", it.key) }
            textSlot?.let { put("text", it.key) }
        }

        return CompiledStep(
            step = number,
            selector = selector?.let {
                preferStableSelector(
                    target = it,
                    handles = step.resolvedHandles,
                    // A slot-bound selector's value comes from the caller at
                    // replay; rewriting it to a handle observed in one recorded
                    // run would freeze that run's contact into every future one.
                    isParameterised = selectorSlot != null,
                )
            },
            action = wireName(action),
            expect = action.expect,
            llmFallback = allowsFallback(action, trace),
            bindings = bindings,
            args = argsOf(action),
        )
    }

    /**
     * The action's non-selector payload (E22c).
     *
     * Everything a step needs that is not a [Target]. Without this the compiler
     * kept only `action.target`, which is `null` by definition for `launch_app`,
     * `press_key` and `wait`, and which never carried the direction for `swipe`
     * or `scroll` — so those steps compiled to something unreplayable, or worse,
     * to something the replayer would fill in with a default and execute anyway.
     *
     * Exhaustive `when` on purpose: a new [DeviceAction] variant must be handled
     * here, and the compiler will not build until it is. That is the property
     * that was missing, and it matters more than the fix itself — the original
     * bug was not a wrong line, it was a payload that could go missing without
     * anything noticing.
     */
    private fun argsOf(action: DeviceAction): Map<String, String> = when (action) {
        is DeviceAction.LaunchApp -> mapOf("app" to action.app)
        is DeviceAction.PressKey -> mapOf("key" to action.key.name)
        is DeviceAction.Swipe -> mapOf("direction" to action.direction.name)
        is DeviceAction.Scroll -> mapOf("direction" to action.direction.name)
        // The literal typed text. A step whose text is slot-bound overrides this
        // at replay from the caller's params; keeping the literal as well means a
        // skill compiled from an unparameterised run still replays.
        is DeviceAction.InputText -> mapOf("text" to action.text)
        is DeviceAction.Wait -> mapOf("timeout_ms" to action.timeoutMs.toString())
        is DeviceAction.Tap, is DeviceAction.LongPress -> emptyMap()
    }

    /**
     * Rewrite a selector toward the most drift-resistant form available (E26).
     *
     * §7.7 requires selector robustness, and the ordering is not arbitrary —
     * it reflects what survives an app update:
     *
     * | selector | survives |
     * |---|---|
     * | `id` | most updates; the developer's own stable handle |
     * | `content_desc` | most updates; changes with localisation |
     * | `text` | visible-copy changes break it |
     * | `coord` | almost nothing — rotation, font size, density |
     *
     * ## Why this was a no-op until now
     *
     * It returned its argument unchanged, and the comment above described an
     * intention rather than a behaviour. The reason was not neglect: **the
     * compiler had nothing better to choose from.** A `TraceStep` carried only
     * the action, so the only selector in evidence was the one the model wrote —
     * and the model writes what it can see in the prompt, which is the visible
     * text.
     *
     * The gate, meanwhile, had already resolved that text to a real node which
     * frequently carries `com.whatsapp:id/send` — a handle the app's own
     * developer controls, immune to translation and copy changes. That was
     * discarded. E26 records it ([ResolvedHandles]), and this can finally do
     * what it always claimed.
     *
     * ## Promotion is safe; invention is not
     *
     * Only handles **observed on the matched node** are used. The compiler
     * cannot fabricate a view id, and it never promotes to a handle the node did
     * not have — so a rewrite can only ever name the same element by a
     * different, better-attested route.
     *
     * A parameterised selector is left alone entirely: its value is substituted
     * from the caller's params at replay, so rewriting it to a view id observed
     * during *one* recorded run would freeze that run's contact into every
     * future one. Exactly the macro-versus-skill error §7.7 warns about.
     */
    private fun preferStableSelector(
        target: Target,
        handles: ResolvedHandles?,
        isParameterised: Boolean,
    ): Target {
        if (selectorPolicy == SelectorPolicy.NONE || handles == null || isParameterised) {
            return target
        }

        // Already the most stable form available.
        if (target.by == TargetBy.ID) return target

        // E26b measured the cost of this line. Promoting to a view id survives
        // every copy change and dies on the two drift classes that touch ids —
        // including a Compose migration, which removes them wholesale while
        // changing nothing a user sees. LABEL exists so that trade is a choice
        // rather than an accident.
        if (selectorPolicy == SelectorPolicy.STURDIEST) {
            handles.viewId?.let { return Target(TargetBy.ID, it) }
        }

        // A coordinate survives almost nothing, so any label beats it.
        if (target.by == TargetBy.COORD) {
            handles.contentDescription?.let { return Target(TargetBy.CONTENT_DESC, it) }
            handles.text?.let { return Target(TargetBy.TEXT, it) }
        }

        // Content-description outranks visible text: it does not change when the
        // app's copy does. Promote only when the node genuinely carried one.
        if (target.by == TargetBy.TEXT) {
            handles.contentDescription?.let { return Target(TargetBy.CONTENT_DESC, it) }
        }

        return target
    }

    /**
     * May the planner be consulted if this step's assertion fails on replay?
     *
     * The C1 fallback boundary, and the flag that makes replay *robust* rather
     * than merely fast.
     *
     * `false` for structurally fixed steps — launching a package, pressing back —
     * where a failure means something the model cannot fix by guessing, and
     * calling it would spend ~60 s to re-derive an action that was never in
     * doubt. `true` for steps inside an app's own screens, which are exactly what
     * changes between versions.
     */
    private fun allowsFallback(action: DeviceAction, trace: VerifiedTrace): Boolean = when (action) {
        is DeviceAction.LaunchApp -> false
        is DeviceAction.PressKey -> false
        is DeviceAction.Wait -> false
        else -> true
    }

    /**
     * A goal pattern with parameter values replaced by `{slot}` placeholders.
     *
     * Both the lexical key for extracting slots from a future utterance and the
     * text embedded for semantic matching (§7.8), so it must read like something
     * a person would say — "send {message} to {contact} on whatsapp", not a
     * regex.
     */
    private fun goalPattern(trace: VerifiedTrace, slots: Map<String, Slot>): String {
        var pattern = trace.goal.lowercase()
        // Longest first, so a value that contains another value does not get
        // partially replaced and leave a fragment behind.
        for ((name, slot) in slots.entries.sortedByDescending { it.value.value.length }) {
            pattern = pattern.replace(slot.value.lowercase(), "{$name}")
        }
        return pattern
    }

    private fun requiredCapabilities(trace: VerifiedTrace): List<String> = buildSet {
        add("ui:observe")
        add("ui:gesture")
        for (step in trace.steps) {
            when (step.action) {
                is DeviceAction.LaunchApp -> add("app:launch")
                else -> Unit
            }
        }
    }.toList().sorted()

    private fun rejectionReason(trace: VerifiedTrace): String = when {
        trace.outcome != dev.axon.core.model.TaskOutcome.SUCCESS ->
            "trace outcome is ${trace.outcome}, not SUCCESS"
        trace.steps.isEmpty() -> "trace has no steps"
        trace.steps.any { it.healed } ->
            // Freezing a healed trace compiles the mistakes in alongside the
            // solution, and replays them forever. Still valuable as evaluation
            // data — just not as a script.
            "trace contains ${trace.healedSteps} healed step(s); only clean runs compile"
        else -> "trace has unverified steps"
    }

    private fun typeOf(name: String, value: String): String = when {
        name.contains("contact") || name.contains("person") -> "contact_name"
        name.contains("time") || value.matches(Regex("""\d{1,2}[:.]\d{2}.*""")) -> "time"
        name.contains("place") || name.contains("location") -> "place"
        name.contains("app") || name.contains("package") -> "package"
        else -> "string"
    }

    private fun inferSlotName(a: String, b: String, index: Int): String =
        "param_$index"

    private fun skillId(goal: String): String =
        goal.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(48)
            .ifEmpty { "skill" }

    private fun wireName(action: DeviceAction): String = when (action) {
        is DeviceAction.Tap -> "tap"
        is DeviceAction.LongPress -> "long_press"
        is DeviceAction.InputText -> "input_text"
        is DeviceAction.Swipe -> "swipe"
        is DeviceAction.Scroll -> "scroll"
        is DeviceAction.LaunchApp -> "launch_app"
        is DeviceAction.PressKey -> "press_key"
        is DeviceAction.Wait -> "wait"
    }

    private data class Slot(val value: String, val type: String)
}

/** Preference order for selector stability. Lower is more drift-resistant. */
internal fun TargetBy.stabilityRank(): Int = when (this) {
    TargetBy.ID -> 0
    TargetBy.CONTENT_DESC -> 1
    TargetBy.TEXT -> 2
    TargetBy.CLASS -> 3
    TargetBy.COORD -> 4
}
