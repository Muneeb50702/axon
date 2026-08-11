package dev.axon.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A machine-checkable claim about the UI after an action (spec §10.1 `expect`).
 *
 * This type is the hinge of contribution C2. AXON never asks the model "did that
 * work?" — small models are unreliable self-judges (§7.6). Instead the planner
 * must state, *before* acting, what observable change the action should produce;
 * the verifier then evaluates that claim against the real accessibility tree with
 * no LLM in the loop. Verification therefore costs no tokens and cannot be
 * talked into a wrong answer.
 *
 * The enum is closed and matches §10.1 exactly. The GBNF grammar (§10.6) makes
 * any other condition kind unreachable during sampling.
 */
@Serializable
data class PostCondition(
    val type: PostConditionType,
    val value: String,
) {
    /**
     * A short human phrase used in the self-heal prompt (§7.6) and the audit log
     * (§16). When a step fails, the planner is told what was expected in the same
     * words a person would use, alongside what the tree actually showed.
     */
    fun describe(): String = when (type) {
        PostConditionType.NODE_PRESENT -> "a node matching \"$value\" appears"
        PostConditionType.NODE_ABSENT -> "the node matching \"$value\" disappears"
        PostConditionType.TEXT_MATCHES -> "text matching \"$value\" appears on screen"
        PostConditionType.SCREEN_TITLE -> "the screen title becomes \"$value\""
        PostConditionType.APP_FOREGROUND -> "the app \"$value\" comes to the foreground"
    }
}

@Serializable
enum class PostConditionType {
    /** Some node's text or content-description matches [PostCondition.value]. */
    @SerialName("node_present") NODE_PRESENT,

    /**
     * No node matches. The workhorse for "the dialog closed", "the spinner
     * finished", "the draft cleared" — cases where success is an absence and a
     * screenshot-based agent has nothing to look at.
     */
    @SerialName("node_absent") NODE_ABSENT,

    /**
     * Anywhere on screen, text matches the pattern.
     *
     * The value is model-authored, so it is treated as an *untrusted* pattern.
     * The verifier applies a safe-pattern policy rather than trusting it blindly
     * (see `TextMatching` in the verifier package): a value that fails to compile
     * as a regex degrades to case-insensitive literal containment instead of
     * throwing. A 2B model writing a malformed regex must not be able to crash
     * the run or, worse, make a step spuriously fail.
     *
     * §10.6 notes PCRE shorthands (`\d \w \s \b`) break the JSON-Schema→GBNF
     * converter. AXON hand-writes its grammar and evaluates patterns in Kotlin,
     * so that constraint does not bind here — but the safe-pattern policy is
     * still required, for the reason above.
     */
    @SerialName("text_matches") TEXT_MATCHES,

    /** The screen's title / header equals the value. */
    @SerialName("screen_title") SCREEN_TITLE,

    /** The named package is the foreground app. The strongest, cheapest check. */
    @SerialName("app_foreground") APP_FOREGROUND,
}
