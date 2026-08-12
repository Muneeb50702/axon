package dev.axon.core.verifier

import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree

/**
 * Evaluates a post-condition against a captured screen (spec §7.6) — **C2**.
 *
 * This is the mechanism that replaces "ask the model whether it worked". It is a
 * pure function of a claim and a tree: same inputs, same verdict, no tokens
 * spent, and no way to talk it into the wrong answer.
 *
 * ## Why it lives in `:core` rather than in the Android driver
 *
 * The first draft put it in `AccessibilityDriver`, which built fine and was
 * wrong. C2 is a *contribution*, and a contribution that can only be exercised
 * with a phone plugged in is one a reviewer cannot check. Here it is exercised
 * on the JVM against hand-built trees on every commit, and it transfers
 * unchanged to a Linux or Windows driver.
 *
 * That is the same argument as C4's portability seam, applied to evidence rather
 * than to code: the deterministic half of AXON should be verifiable by someone
 * who does not own the handset.
 */
public object PostConditionEvaluator {

    /** Does [condition] hold on [tree]? */
    public fun evaluate(condition: PostCondition, tree: UiTree): Boolean =
        when (condition.type) {
            PostConditionType.APP_FOREGROUND ->
                tree.foregroundPackage.equals(condition.value, ignoreCase = true)

            PostConditionType.SCREEN_TITLE ->
                tree.screenTitle.equals(condition.value, ignoreCase = true)

            PostConditionType.NODE_PRESENT ->
                tree.nodes.any { it.mentions(condition.value) }

            PostConditionType.NODE_ABSENT ->
                tree.nodes.none { it.mentions(condition.value) }

            PostConditionType.TEXT_MATCHES ->
                TextMatching.matches(condition.value, tree)
        }

    /**
     * Substring containment over the two fields a user could actually read.
     *
     * Containment rather than equality because post-conditions are written by a
     * small model against a screen it has only seen in summary. Demanding an
     * exact match would fail on "Sent" versus "Sent ✓" — a distinction with no
     * meaning to the task and every opportunity to break verification.
     *
     * `viewId` is deliberately excluded: matching a resource name would let an
     * assertion pass on an element the user cannot see, which defeats the point
     * of checking observable consequences.
     */
    private fun UiNode.mentions(value: String): Boolean =
        text?.contains(value, ignoreCase = true) == true ||
            contentDescription?.contains(value, ignoreCase = true) == true
}

/**
 * Pattern matching for [PostConditionType.TEXT_MATCHES], with an untrusted
 * pattern.
 *
 * The value is authored by a ~1B model, so it is not assumed to be a valid
 * regex. §10.6 also warns that PCRE shorthands (`\d \w \s \b`) break parts of
 * the GBNF toolchain, and models reach for them constantly.
 *
 * The policy is therefore: try to compile; if that fails, degrade to
 * case-insensitive literal containment. A malformed pattern must not be able to
 * crash a run, and — more subtly — must not be able to make a step *spuriously
 * fail*, because a false verification failure triggers a heal, burns the heal
 * budget, and can escalate a working task to the user for no reason.
 */
public object TextMatching {

    public fun matches(pattern: String, tree: UiTree): Boolean {
        val regex = compile(pattern)
        return tree.nodes.any { node ->
            val haystack = listOfNotNull(node.text, node.contentDescription)
            if (regex != null) {
                haystack.any { regex.containsMatchIn(it) }
            } else {
                haystack.any { it.contains(pattern, ignoreCase = true) }
            }
        }
    }

    /** The compiled pattern, or `null` when it is not a usable regex. */
    public fun compile(pattern: String): Regex? =
        runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()

    /** True when the pattern failed to compile and literal matching was used. */
    public fun isLiteralFallback(pattern: String): Boolean = compile(pattern) == null
}
