package dev.axon.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A skill's `manifest.json` (spec §10.3).
 *
 * A skill is a folder, not a class (§7.7). Everything the runtime needs to
 * match, permission-check and parameterise it lives in this file, so installing
 * a capability means dropping in a directory — the O5 acceptance criterion.
 *
 * ```
 * skills/whatsapp_send/
 *   manifest.json      ← this
 *   prompts/           ← optional planner hints
 *   traces/            ← compiled deterministic scripts
 *   assertions.json    ← post-conditions per step
 * ```
 */
@Serializable
data class SkillManifest(
    val id: String,
    val name: String,

    /**
     * Slot-templated phrasing, e.g. `send {message} to {contact} on whatsapp`.
     *
     * Used two ways: lexically, to extract slot values from an utterance that
     * fits the shape, and semantically, as the text embedded into the vector
     * index so that "text Ammi that I'm coming" can match a skill whose pattern
     * never uses the word "text" (§7.8).
     */
    @SerialName("goal_pattern") val goalPattern: String,

    val parameters: List<SkillParameter> = emptyList(),

    @SerialName("required_capabilities") val requiredCapabilities: List<String> = emptyList(),

    val version: Int = 1,

    /**
     * Device family this skill's compiled traces were recorded against.
     *
     * §17 names app/device fragmentation as a high-likelihood risk, and the
     * mitigation is that skills are per-device-family with assertion-driven
     * fallback. Recording provenance here is what lets the store decline to
     * replay a trace compiled on another device's layout instead of failing
     * halfway through it.
     */
    @SerialName("device_family") val deviceFamily: String? = null,
) {
    /** Capabilities as typed values. */
    fun capabilities(): Set<Capability> = requiredCapabilities.map(::Capability).toSet()

    /**
     * Structural problems, empty when the manifest is installable.
     *
     * Validation lives here rather than in the installer so it is testable
     * without a filesystem, and so `bench` and the Android app cannot drift into
     * different notions of a valid skill.
     */
    fun validate(): List<String> = buildList {
        if (id.isBlank()) add("id must not be blank")
        if (!id.all { it.isLowerCase() || it.isDigit() || it == '_' }) {
            add("id must be lower_snake_case: '$id'")
        }
        if (goalPattern.isBlank()) add("goal_pattern must not be blank")

        val slots = SLOT.findAll(goalPattern).map { it.groupValues[1] }.toSet()
        val declared = parameters.map { it.name }.toSet()
        (slots - declared).forEach { add("goal_pattern uses {$it} but no such parameter is declared") }
        (declared - slots).filter { name ->
            parameters.first { it.name == name }.required
        }.forEach { add("required parameter '$it' never appears in goal_pattern") }

        requiredCapabilities.map(::Capability)
            .filterNot { it.isWellFormed() }
            .forEach { add("malformed capability '$it' (expected lowercase domain:verb)") }
    }

    /** Slot names in the goal pattern, in order of appearance. */
    fun slots(): List<String> = SLOT.findAll(goalPattern).map { it.groupValues[1] }.toList()

    // Not private: kotlinx.serialization generates `serializer()` onto the
    // companion, so a private one would make the type unserialisable from
    // outside this module.
    companion object {
        private val SLOT = Regex("""\{([a-z_][a-z0-9_]*)\}""")
    }
}

@Serializable
data class SkillParameter(
    val name: String,

    /**
     * Semantic type, e.g. `contact_name`, `string`, `time`, `place`.
     *
     * Free-form for the same extensibility reason as [Capability]: a new skill
     * may introduce a slot type without editing core.
     */
    val type: String,

    val required: Boolean = true,
)

/**
 * One frozen step of a compiled skill (spec §10.4) — contribution C1.
 *
 * The compiler turns a verified trace step into this: what to look for, what to
 * do, what must then be true, and whether the model is allowed back in if that
 * assertion fails.
 */
@Serializable
data class CompiledStep(
    val step: Int,
    val selector: Target?,

    /** Action discriminator, one of [DeviceAction.ACTION_TYPES]. */
    val action: String,

    val expect: PostCondition,

    /**
     * May the planner be consulted if [expect] fails on replay?
     *
     * This flag is the LLM-fallback boundary named in C1, and it is what makes
     * replay *robust* rather than merely fast. `true` on steps whose UI is
     * expected to drift (an app's own screens, which change between versions);
     * `false` on steps that are structurally fixed (launching a package) where a
     * failure means something is wrong the model cannot fix by guessing.
     */
    @SerialName("llm_fallback") val llmFallback: Boolean = true,

    /**
     * Slot substitutions, as `argument name → parameter name`.
     *
     * How a trace becomes *parameterised* rather than a macro. The recorded run
     * typed "on my way" into a box; the compiled step records
     * `{"text": "message"}`, so replaying with `message = "running late"` types
     * the new value. Without this, a compiled skill could only ever repeat the
     * exact task it was recorded from.
     */
    val bindings: Map<String, String> = emptyMap(),
)

/**
 * A replayable skill: manifest plus frozen steps (§7.7).
 *
 * Replaying this runs the deterministic steps with the model out of the loop,
 * checking each step's assertion as it goes and falling back to the planner only
 * for a step whose assertion failed and whose [CompiledStep.llmFallback] allows
 * it. That is the O4 claim — 0 LLM calls on unchanged UI, ≥5× faster than cold.
 */
@Serializable
data class CompiledSkill(
    val manifest: SkillManifest,
    val steps: List<CompiledStep>,

    /** Trace ids this was compiled from — provenance for the evaluation. */
    @SerialName("source_traces") val sourceTraces: List<String> = emptyList(),

    /** Successful replays so far. Confidence signal for the store. */
    @SerialName("replay_count") val replayCount: Int = 0,

    /** Replays that needed at least one LLM fallback — the UI-drift rate. */
    @SerialName("repair_count") val repairCount: Int = 0,
) {
    /** Share of replays that ran fully deterministically. */
    val cleanReplayRate: Double
        get() = if (replayCount == 0) 0.0 else (replayCount - repairCount).toDouble() / replayCount
}
