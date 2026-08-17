package dev.axon.bench

import dev.axon.core.model.PostCondition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One AXON-Bench task (spec §14.1) — contribution C5.
 *
 * §14.1: *"Each task ships a spec: initial condition, goal, and a deterministic
 * success oracle (final + intermediate post-conditions)."*
 *
 * The oracle is [PostCondition] — the same type the planner emits and the
 * verifier evaluates. That reuse is deliberate and load-bearing: a benchmark
 * whose success criteria are expressed in a different language from the system's
 * own assertions can drift from what the system actually checks, and then the
 * evaluation measures a slightly different thing than the runtime enforces.
 * Sharing the type makes that impossible, and means the oracle is exercised by
 * the same tests as the verifier.
 */
@Serializable
data class BenchTask(
    val id: String,
    val tier: BenchTier,

    /** The instruction, phrased as a user would say it. */
    val goal: String,

    val params: Map<String, String> = emptyMap(),

    /**
     * State the device must be in before the run.
     *
     * §14.1's robustness tier varies exactly this — same task, different
     * starting screen — so it is a first-class field rather than a note.
     */
    @SerialName("initial_condition") val initialCondition: InitialCondition,

    /** Must hold when the task is done. The success oracle. */
    @SerialName("success_oracle") val successOracle: List<PostCondition>,

    /**
     * Checkpoints that must hold along the way.
     *
     * Without these, a task that reached the right end state by an absurd route
     * scores identically to one that did it correctly — and on a phone, "right
     * end state, wrong route" can mean the agent wandered through screens it had
     * no business touching. Intermediate conditions are also what make the
     * step-efficiency metric (§14.2) meaningful rather than a raw step count.
     */
    @SerialName("intermediate_conditions") val intermediateConditions: List<PostCondition> = emptyList(),

    /**
     * Fewest actions a competent human would need.
     *
     * Denominator of §14.2's step efficiency (`actual ÷ optimal`). Hand-counted
     * per task; the count belongs in the task file so it is reviewable rather
     * than buried in the harness.
     */
    @SerialName("optimal_steps") val optimalSteps: Int,

    /** Apps that must be installed. Tasks whose apps are absent are skipped, not failed. */
    @SerialName("required_apps") val requiredApps: List<String> = emptyList(),

    /**
     * Does completing this task cross §16's confirmation gate?
     *
     * True for tasks that place a call, send a message, spend money or delete
     * data — the irreversible set, which AXON refuses to perform without the
     * user explicitly approving *that particular act*.
     *
     * Recorded per task rather than discovered at run time because it changes
     * what a result means. A benchmark cannot answer a confirmation prompt, so
     * these tasks can only be scored with the gate opened
     * (`ConfirmationGate.ALLOW_FOR_TESTING`), and a results table that mixes
     * gated and ungated tasks without saying which is which is reporting a
     * system that is not the one users get.
     *
     * The honest consequence: **`requiresConfirmation` tasks measure the agent's
     * competence, not its behaviour in the field**, where a human is in the loop
     * for exactly these. Both numbers are worth having; conflating them is not.
     */
    @SerialName("requires_confirmation") val requiresConfirmation: Boolean = false,

    /**
     * Perturbation applied, for robustness variants (§14.1).
     *
     * `null` on the base task. A variant shares its parent's oracle and differs
     * only here, so a robustness drop is attributable to the perturbation rather
     * than to a differently-written task.
     */
    val perturbation: Perturbation? = null,
)

@Serializable
enum class BenchTier {
    /** 10 tasks: message, call, alarm, navigate, toggle, calendar event, … (§14.1). */
    @SerialName("core") CORE,

    /** Same tasks under perturbation (§14.1). */
    @SerialName("robustness") ROBUSTNESS,

    /** 5 tasks of 6+ composed steps (§14.1). */
    @SerialName("long_horizon") LONG_HORIZON,
}

@Serializable
data class InitialCondition(
    /** Package the device should start on. Null means the launcher. */
    @SerialName("start_package") val startPackage: String? = null,

    /** Human-readable setup a run script or operator performs first. */
    val setup: List<String> = emptyList(),
)

/** A robustness perturbation (§14.1). */
@Serializable
enum class Perturbation {
    /** Start somewhere other than the usual screen. */
    @SerialName("different_start_screen") DIFFERENT_START_SCREEN,

    /** Fire a notification mid-task, so the agent must recover its context. */
    @SerialName("intervening_notification") INTERVENING_NOTIFICATION,

    /**
     * A different app version or A/B layout.
     *
     * The perturbation that most directly probes C1: a compiled skill's
     * assertions should fail here, the LLM fallback should repair the diverged
     * step, and the skill should re-compile. That is the whole "robustness as a
     * feature" claim in §17, so the benchmark has to contain the case.
     */
    @SerialName("layout_variant") LAYOUT_VARIANT,

    /** A permission dialog interrupts the flow. */
    @SerialName("permission_dialog") PERMISSION_DIALOG,
}

/**
 * One cell of the §14.3 ablation matrix — "the money table".
 *
 * ```
 *   config  model  grammar  verifier  replay   expectation
 *   A       3B     ✗        ✗         ✗        low TSR, low valid-action
 *   B       3B     ✓        ✗         ✗        valid-action ≈100%, TSR up
 *   C       3B     ✓        ✓         ✗        TSR up, recovery > 0
 *   D       3B     ✓        ✓         ✓        best TSR, LLM calls collapse
 *   E       7B     ✗        ✗         ✗        the baseline D must beat
 * ```
 *
 * Modelled as data rather than as five code paths so that turning a feature off
 * removes exactly that feature. If each arm were its own runtime, a difference
 * between A and B would be attributable to any of the differences between two
 * programs; here it is attributable to the grammar flag.
 */
@Serializable
data class AblationConfig(
    val id: String,
    val model: String,
    val grammar: Boolean,
    val verifier: Boolean,
    @SerialName("skill_replay") val skillReplay: Boolean,
) {
    companion object {
        val A_NAIVE_SMALL = AblationConfig("A", SMALL, grammar = false, verifier = false, skillReplay = false)
        val B_GRAMMAR = AblationConfig("B", SMALL, grammar = true, verifier = false, skillReplay = false)
        val C_VERIFIER = AblationConfig("C", SMALL, grammar = true, verifier = true, skillReplay = false)
        val D_FULL_AXON = AblationConfig("D", SMALL, grammar = true, verifier = true, skillReplay = true)

        /**
         * The baseline D must meet or beat (§14.3).
         *
         * §14.3's headline claim: a constrained, verified, skill-compiled small
         * model matches or beats a naive larger one on task success while using a
         * fraction of the compute — and on repeated tasks approaches zero model
         * calls. That single chart is what the evaluation is for.
         */
        val E_NAIVE_LARGE = AblationConfig("E", LARGE, grammar = false, verifier = false, skillReplay = false)

        val ALL = listOf(A_NAIVE_SMALL, B_GRAMMAR, C_VERIFIER, D_FULL_AXON, E_NAIVE_LARGE)

        /**
         * Model ids are resolved at run time, not pinned here.
         *
         * §11: *"Do not hard-code versions from this doc — resolve current at
         * implementation."* §20.2 adds that the on-device model field moves
         * quarterly and the checks should be re-run close to submission. The
         * concrete choice as of 2026-08-11 is Gemma 4 E2B (Q4_K_M ≈ 1.3 GB, fits
         * 6 GB devices) for the small arm — superseding the spec's Gemma 3n E2B,
         * which predates it.
         */
        private const val SMALL = "\${axon.model.small}"
        private const val LARGE = "\${axon.model.large}"
    }
}
