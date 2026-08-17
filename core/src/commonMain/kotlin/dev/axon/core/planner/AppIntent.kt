package dev.axon.core.planner

import dev.axon.core.model.CompactState

/**
 * Recognises "open <app>" goals and resolves them to a package (E21).
 *
 * ## The failure this addresses
 *
 * E18b: asked to "open whatsapp" from the launcher, the planner produced a
 * well-formed, screen-grounded, gate-approved action — and tapped the **phone
 * dialer**. Every structural defence held. What failed was choosing which of
 * several legal, present icons corresponds to "whatsapp", which is semantic
 * selection, and no decoding constraint supplies it.
 *
 * The prompt already instructs *"To open an app, use launch_app with its package
 * name"*. The model ignored it, exactly as it ignored the failure context in
 * E18. Instruction-following does not hold at ~1B.
 *
 * ## The response, in this project's usual shape
 *
 * "Open X" is one of the few goals whose correct action is fully determined
 * without looking at the screen at all: if the device has an app named X, the
 * right move is `launch_app` with X's package, whatever happens to be visible.
 *
 * So when the goal is recognised as an app-launch intent **and** the name
 * resolves to an installed package, the grammar is narrowed to a single legal
 * action. Not preferred — *the only one*. The dialer becomes unreachable rather
 * than merely wrong.
 *
 * ## Scope, honestly
 *
 * This handles one narrow class of goal. It is not a general solution to
 * semantic selection, and the paper should not present it as one — the
 * substitution surface (POSITIONING §3.1) still has to measure where model
 * capacity binds. What it demonstrates is the *method*: when a goal's correct
 * action is determinable without the model's judgement, remove the judgement.
 */
public object AppIntent {

    /**
     * The app name in an "open X" style goal, or `null`.
     *
     * Deliberately conservative. "open whatsapp and send a message" is **not** an
     * app-launch intent — it is a multi-step task whose first step happens to be
     * a launch, and narrowing the grammar for the whole task would leave the
     * agent unable to do the rest. Only goals that are *entirely* a launch
     * request match.
     */
    public fun appName(goal: String): String? {
        val text = goal.trim().lowercase().removeSuffix(".").removeSuffix(" app")

        for (verb in LAUNCH_VERBS) {
            if (!text.startsWith("$verb ")) continue
            val rest = text.removePrefix("$verb ").trim()
            if (rest.isEmpty()) return null

            // A conjunction means the goal continues past the launch.
            if (CONTINUATIONS.any { " $it " in " $rest " }) return null

            return rest.removePrefix("the ").trim().ifEmpty { null }
        }
        return null
    }

    /**
     * A success oracle for a pure launch goal, or `null` if this is not one.
     *
     * ## Why the grammar collapse was only half of E21
     *
     * Measured on device (E21b): with the grammar narrowed, all six generations
     * emitted `launch_app com.whatsapp`, the gate approved every one and the
     * verifier confirmed every one. The mechanism worked exactly as claimed —
     * and the task still ended in `BUDGET_EXHAUSTED` at 426 seconds, having
     * launched WhatsApp six times.
     *
     * Nothing was wrong with any individual decision. The runtime simply had no
     * way to know it was finished, because a caller with no oracle passes one
     * that never fires, and `RepetitionGuard` — which blocks actions that
     * *failed* — correctly stayed silent while every step succeeded.
     *
     * ## The oracle is determined by the same reasoning that collapsed the grammar
     *
     * If "open X" has exactly one correct action without consulting the model,
     * it has exactly one success condition too: **X is in the foreground.** The
     * package lookup that makes the action determinable makes the completion
     * test determinable, and reading it off the observed state costs nothing and
     * asks the model nothing — which is the same reason C2 replaced LLM
     * self-assessment with a deterministic verifier.
     *
     * Returns `null` for anything that is not entirely a launch request, so a
     * multi-step goal never inherits a success test that would fire after its
     * first step. That is [appName]'s conservatism, reused rather than restated.
     */
    public suspend fun launchOracle(
        goal: String,
        resolver: AppResolver,
    ): (suspend (CompactState) -> Boolean)? {
        val name = appName(goal) ?: return null
        val packageName = resolver.resolve(name) ?: return null
        return { state -> state.foregroundPackage == packageName }
    }

    /** Verbs that mean "bring this app to the front". */
    private val LAUNCH_VERBS = listOf(
        "open", "launch", "start", "go to", "switch to", "show me", "show",
        // Roman-Urdu, per §2.1.
        "kholo", "khol",
    )

    /**
     * Words that indicate the goal does not end at the launch.
     *
     * Without this, "open whatsapp and message ammi" would be narrowed to a
     * single `launch_app` and the agent would consider the task complete after
     * step one.
     */
    private val CONTINUATIONS = listOf("and", "then", "to", "aur", "phir")
}

/**
 * Resolves a human app name to an installed package.
 *
 * Declared in `:core` and implemented in `:android:driver`, for the same reason
 * as [dev.axon.core.driver.DeviceDriver]: the reliability core states what it
 * needs, and the platform supplies it. A Linux port would answer this from
 * `.desktop` files.
 */
public fun interface AppResolver {

    /**
     * The package for [name], or `null` if no installed app matches.
     *
     * `null` is the *safe* answer and callers must treat it as ordinary: an app
     * that is not installed cannot be launched, and the planner should fall back
     * to normal screen-grounded planning rather than being handed a grammar with
     * no legal action in it.
     */
    public suspend fun resolve(name: String): String?

    public companion object {
        /** Resolves nothing. The default, so the narrowing is opt-in. */
        public val NONE: AppResolver = AppResolver { null }
    }
}
