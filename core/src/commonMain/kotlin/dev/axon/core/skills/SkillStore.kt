package dev.axon.core.skills

import dev.axon.core.model.CompiledSkill
import dev.axon.core.model.Goal
import dev.axon.core.model.SkillManifest
import dev.axon.core.model.VerifiedTrace

/**
 * Holds installed skills and matches goals to them (spec §9.2, §7.7).
 *
 * The first decision the control loop makes (§7.2): is there a compiled skill
 * for this goal? A hit takes the REPLAY path with no model in the loop; a miss
 * takes the PLAN path. Everything C1 claims about "gets faster with use" flows
 * through this one lookup.
 */
public interface SkillStore {

    /**
     * Find a skill that can serve [goal], or `null` for the PLAN path.
     *
     * Matching is semantic, not lexical (§7.8): "text Ammi that I'm coming" must
     * hit the WhatsApp-send skill whose pattern is "send {message} to {contact}
     * on whatsapp", despite sharing almost no words. A hit also binds the goal's
     * slots, so the implementation returns the skill *and* the parameters, not
     * just a match.
     *
     * A false positive here is worse than a miss: replaying the wrong skill acts
     * on the device before anything can catch the error, whereas a miss merely
     * costs a slower cold run. Implementations should require a high similarity
     * threshold and let borderline cases fall through to the planner.
     */
    public suspend fun match(goal: Goal): SkillMatch?

    /** Install a skill folder (§7.7). Rejects manifests that fail validation. */
    public suspend fun install(folder: SkillFolder): InstallResult

    /** Persist a freshly compiled skill, or a re-compile after UI drift. */
    public suspend fun save(skill: CompiledSkill)

    public suspend fun all(): List<CompiledSkill>

    /** Record a replay outcome so [CompiledSkill.cleanReplayRate] stays honest. */
    public suspend fun recordReplay(skillId: String, neededRepair: Boolean)
}

/**
 * A matched skill with its bound parameters and the confidence of the match.
 */
public data class SkillMatch(
    val skill: CompiledSkill,

    /** Slots extracted from the utterance, e.g. `{"contact": "Ammi"}`. */
    val params: Map<String, String>,

    /** Similarity in `0.0..1.0`. Below [MIN_CONFIDENCE] the store must not match. */
    val confidence: Double,
) {
    init {
        require(confidence in 0.0..1.0) { "confidence out of range: $confidence" }
    }

    public companion object {
        /**
         * Similarity floor for taking the replay path.
         *
         * Set high because the asymmetry above is steep. Calibrate against
         * AXON-Bench (§14) — specifically the false-match rate on goals that
         * *resemble* an installed skill but differ in a way that matters
         * ("call Ammi" vs "call Ammi's office"), which is the case that
         * distinguishes a useful threshold from an arbitrary one.
         */
        public const val MIN_CONFIDENCE: Double = 0.82
    }
}

/**
 * A skill on disk, read as bytes (§7.7).
 *
 * Deliberately not a filesystem path: `:core` is platform-agnostic and has no
 * file API. The Android app reads from assets or app storage, the bench harness
 * reads from `skills/` on the JVM, and both hand the core the same shape — which
 * is what lets AXON-Bench exercise the real skill-loading path in CI.
 */
public data class SkillFolder(
    val id: String,
    val manifest: SkillManifest,

    /** JSON files under `traces/` — compiled scripts, if any are shipped. */
    val compiledSteps: List<CompiledSkill> = emptyList(),

    /** Text files under `prompts/` — optional planner hints, by filename. */
    val prompts: Map<String, String> = emptyMap(),
)

public sealed interface InstallResult {
    public data class Installed(val id: String) : InstallResult

    /** Manifest failed [SkillManifest.validate]. Never partially installed. */
    public data class Rejected(val id: String, val problems: List<String>) : InstallResult

    /**
     * Installed, but some declared capabilities are not yet granted (§7.10).
     *
     * Not an error: the skill is present and visible in the permissions screen,
     * and simply cannot run until the user grants what it asked for. Failing the
     * install instead would hide the skill from the very screen where the user
     * would grant it.
     */
    public data class NeedsCapabilities(
        val id: String,
        val missing: List<String>,
    ) : InstallResult
}

/**
 * Turns a verified trace into a replayable skill (spec §9.2, §7.7) — **C1**.
 *
 * The primary novel contribution. §3.2: *"No existing on-device agent compiles a
 * successful task trace into a deterministic, replayable skill."*
 *
 * The algorithm (§7.7), in four steps:
 *
 *  1. take a trace that reached the goal with every post-condition satisfied;
 *  2. parameterise the variable parts into typed slots;
 *  3. convert each step to `{selector, action, expected_assertion, llm_fallback}`;
 *  4. key it by a goal-pattern for semantic matching.
 *
 * The hard part is step 2, and it is where the contribution actually lives.
 * Step 3 is transcription. Deciding that the tap on "Ammi" was a *parameter* and
 * the tap on "Send" was *structure* — from one run, without being told — is what
 * separates compiling a skill from recording a macro. Get it wrong in one
 * direction and the skill only ever messages Ammi; wrong in the other and it
 * types the contact's name into the message box.
 *
 * Contains no LLM calls (§8): compilation is deterministic, so the same trace
 * always yields the same skill, and a compiled skill can be diffed, reviewed and
 * reproduced — which is what makes it defensible as a research artefact.
 */
public interface SkillCompiler {

    /**
     * Compile a trace, or explain why it cannot be.
     *
     * Refuses traces that are not [VerifiedTrace.isCompilable]: a run that
     * limped to the goal through failures and heals encodes the mistakes as well
     * as the solution, and freezing it would replay them forever.
     */
    public suspend fun compile(trace: VerifiedTrace): CompileResult

    /**
     * Refine an existing skill using a second trace of the same goal.
     *
     * Two runs that differ only in their parameter values reveal which steps
     * were variable — the diff *is* the slot inference, and it is far more
     * reliable than inferring slots from a single run. §7.7 lists this as the
     * intended mechanism ("inferred by diffing repeated runs"), so the compiler
     * is built to improve a skill rather than only to mint one.
     */
    public suspend fun refine(existing: CompiledSkill, trace: VerifiedTrace): CompileResult
}

public sealed interface CompileResult {
    public data class Compiled(val skill: CompiledSkill) : CompileResult

    /** The trace was not clean enough to freeze. */
    public data class Rejected(val reason: String) : CompileResult
}
