package dev.axon.core.driver

import dev.axon.core.model.ActResult
import dev.axon.core.model.Capability
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.PostCondition
import dev.axon.core.model.UiTree

/**
 * The portability seam (spec §9.1) — contribution C4.
 *
 * Everything above this interface is platform-agnostic: the planner, executor,
 * verifier, skill compiler and memory know nothing about Android. Everything
 * below is a specific OS. Porting AXON to Linux (AT-SPI2), Windows (UIA) or
 * macOS (AX) means writing one implementation of this interface — the reliability
 * core, which is where the contributions live, is untouched.
 *
 * `:core` has no Android dependency, so that claim is enforced by the build
 * graph rather than asserted in the thesis: a change that made the core need an
 * Android type would not compile.
 *
 * ## The three verbs, and why `act` does not return success
 *
 * `observe` / `act` / `assert` are separated because conflating them is exactly
 * how naive agents go wrong. If `act` returned "success", the notion of success
 * would live in the driver, where it can only mean "the OS accepted my gesture".
 * AXON needs success to mean "the world changed as intended", which is a
 * different question, asked of a *fresh* observation, by the verifier (§7.6).
 * Keeping the verbs apart is what makes C2 possible.
 */
public interface DeviceDriver {

    /**
     * Capture the current UI as a structured, hashable tree.
     *
     * Implementations must apply the §16 sensitive-field refusal *here*, at
     * capture: password, OTP and authenticator fields are never read into
     * memory, and are counted in [UiTree.dropped]. Filtering later would mean
     * the values existed in the process, which is precisely what §16 forbids.
     */
    public suspend fun observe(): UiTree

    /**
     * Perform an action that has already been precondition-gated by the executor.
     *
     * Returns the raw outcome — dispatched, failed, or refused — never a
     * judgement about whether it achieved the goal.
     */
    public suspend fun act(action: DeviceAction): ActResult

    /**
     * Evaluate a post-condition against the *current* UI, deterministically.
     *
     * Named `assert` by §9.1. Implementations must not call a model.
     */
    public suspend fun assert(condition: PostCondition): Boolean

    /** What this driver can do; feeds the capability sandbox (§7.10). */
    public fun capabilities(): Set<Capability>

    /**
     * Device family identifier, e.g. `pixel-8a/android-15`.
     *
     * Recorded on every trace and compiled skill. §17 flags fragmentation as a
     * high-likelihood risk; per-family provenance is the mitigation, and it is
     * cheap only if the driver reports it rather than the core guessing.
     */
    public val deviceFamily: String
}
