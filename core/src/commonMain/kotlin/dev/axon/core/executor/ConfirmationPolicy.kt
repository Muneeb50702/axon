package dev.axon.core.executor

import dev.axon.core.model.DeviceAction
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree

/**
 * Decides which actions the user must approve before they happen (spec §16).
 *
 * > *"Destructive/irreversible actions require explicit confirmation (sending
 * > money, deleting data, posting publicly)."*
 *
 * ## Why this needed writing after E18b rather than before
 *
 * The capability sandbox already declares which verbs always need confirmation
 * (`Capability.ALWAYS_CONFIRM`: send, pay, delete, post, call, purchase). That
 * covers the **skill** path, where a manifest states what a skill does.
 *
 * It does not cover the **PLAN** path, where there is no manifest — the planner
 * simply proposes a tap on an element, and nothing in the action says whether
 * that element sends money or scrolls a list. E18b made the gap concrete: the
 * agent opened a dialer and had five steps of unconstrained budget remaining.
 * Nothing had gone wrong, and nothing was stopping the next step from being
 * `tap "Call"`.
 *
 * So irreversibility has to be inferred from what the action is aimed at. This
 * object does that, deterministically, before dispatch.
 *
 * ## Deliberately over-broad, for an asymmetric reason
 *
 * A false positive costs one prompt the user dismisses. A false negative places
 * a call, sends a message to the wrong person, or spends money. Those are not
 * comparable, so the policy errs heavily toward asking — and the thesis should
 * say so rather than claim a precision it does not have.
 *
 * The same reasoning as [dev.axon.core.perception.SensitivePolicy], and the same
 * multilingual requirement: §2.1 names South Asian users, so a policy that only
 * recognises English "Send" would fail exactly the population the project claims
 * to serve, on exactly the actions that matter most.
 */
public object ConfirmationPolicy {

    /**
     * Does [action] need explicit user approval before it is dispatched?
     *
     * @param resolved the node the action targets, already resolved by the gate.
     * @param tree the screen it will act on — the foreground package matters as
     *   much as the label, since "Confirm" in a calculator and "Confirm" in a
     *   banking app are not the same button.
     */
    public fun requiresConfirmation(
        action: DeviceAction,
        resolved: UiNode?,
        tree: UiTree,
    ): ConfirmationReason? {
        // Device-directed actions change no data and post nothing. Launching an
        // app is not irreversible; what happens inside it will be judged on its
        // own screen.
        if (action is DeviceAction.Wait || action is DeviceAction.PressKey) return null
        if (action is DeviceAction.Scroll || action is DeviceAction.Swipe) return null

        val label = resolved?.label?.lowercase().orEmpty()
        val sensitiveApp = tree.foregroundPackage.lowercase().let { pkg ->
            SENSITIVE_PACKAGES.any { it in pkg }
        }

        // A dialer's call button is the E18b case. Checked first because it is
        // the one that reaches the outside world fastest.
        CALL_TERMS.firstOrNull { it in label }?.let {
            return ConfirmationReason("place a call", label, "call")
        }

        PAYMENT_TERMS.firstOrNull { it in label }?.let {
            return ConfirmationReason("spend money", label, "pay")
        }

        DESTRUCTIVE_TERMS.firstOrNull { it in label }?.let {
            return ConfirmationReason("delete data", label, "delete")
        }

        SEND_TERMS.firstOrNull { it in label }?.let {
            return ConfirmationReason("send a message or post publicly", label, "send")
        }

        // Inside a payments or banking app, a generic affirmative is a
        // transaction. "OK" in a calculator is not worth a prompt; "OK" in a
        // wallet is the last thing standing between the user and a transfer.
        if (sensitiveApp && GENERIC_AFFIRMATIVES.any { it in label }) {
            return ConfirmationReason(
                "confirm something in ${tree.foregroundPackage}",
                label,
                "pay",
            )
        }

        return null
    }

    /**
     * Terms that place a call. Includes Urdu/Roman-Urdu, per §2.1.
     */
    private val CALL_TERMS = listOf(
        "call", "dial", "video call", "voice call", "ring",
        "call kar", "raabta",
    )

    private val PAYMENT_TERMS = listOf(
        "pay", "send money", "transfer", "buy", "purchase", "checkout",
        "subscribe", "confirm payment", "place order", "top up", "recharge",
        "paisay", "adaigi", "bhejo paisay",
    )

    private val DESTRUCTIVE_TERMS = listOf(
        "delete", "remove", "erase", "clear all", "uninstall",
        "block", "unfriend", "leave group", "reset", "format",
        "hataao", "mitao",
    )

    /**
     * Terms that send a message or publish.
     *
     * Broad on purpose. "Send" is the single most common irreversible button on
     * a phone, and §2.1's messaging tasks are exactly where AXON will meet it.
     */
    private val SEND_TERMS = listOf(
        "send", "post", "share", "publish", "tweet", "reply", "submit",
        "bhejo", "bhej",
    )

    /**
     * Words that mean "yes, do it" without saying what.
     *
     * Only treated as irreversible inside a sensitive app, or every dialog on the
     * device would prompt and the user would learn to tap through prompts —
     * which is worse than not having them.
     */
    private val GENERIC_AFFIRMATIVES = listOf(
        "ok", "confirm", "continue", "proceed", "yes", "accept", "agree",
        "haan", "theek hai",
    )

    /**
     * Packages where a generic affirmative is probably a transaction.
     *
     * Matched as substrings, so `com.bank.retail` and `com.easypaisa` both hit.
     * Incomplete by construction — the list cannot enumerate every bank — which
     * is why the term lists above do the primary work and this only widens the
     * net for ambiguous labels.
     */
    private val SENSITIVE_PACKAGES = listOf(
        "bank", "pay", "wallet", "money", "cash", "finance", "upi",
        "easypaisa", "jazzcash", "sadapay", "nayapay",
    )
}

/** Why an action was held for approval, in words the user can act on. */
public data class ConfirmationReason(
    /** What the action would do, e.g. "place a call". */
    val effect: String,

    /** The element label that triggered it. */
    val label: String,

    /** The `Capability.ALWAYS_CONFIRM` verb this maps to. */
    val verb: String,
) {
    /** The question put to the user. */
    public fun question(): String =
        "AXON wants to $effect by tapping \"$label\". Allow?"
}

/**
 * Asks the user to approve an irreversible action.
 *
 * A `fun interface` so the platform decides how to ask — a dialog, a notification
 * action, a voice prompt on a future client.
 *
 * **The default is refusal, deliberately.** An executor constructed without a
 * confirmation gate cannot perform irreversible actions at all. Failing closed
 * means that forgetting to wire this up produces an agent that declines to send
 * messages, not one that sends them silently — and the second failure is not
 * recoverable after the fact.
 */
public fun interface ConfirmationGate {
    public suspend fun confirm(reason: ConfirmationReason): Boolean

    public companion object {
        /** Refuses everything. The safe default when nothing is wired up. */
        public val DENY: ConfirmationGate = ConfirmationGate { false }

        /**
         * Approves everything.
         *
         * For tests and benchmark runs only, where a human cannot answer and the
         * task set is fixed and known. Never for interactive use — and named so
         * that its appearance in a diff is conspicuous.
         */
        public val ALLOW_FOR_TESTING: ConfirmationGate = ConfirmationGate { true }
    }
}
