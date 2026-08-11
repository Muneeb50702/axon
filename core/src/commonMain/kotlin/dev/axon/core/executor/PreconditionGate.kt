package dev.axon.core.executor

import dev.axon.core.model.DeviceAction
import dev.axon.core.model.Target
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree

/**
 * Resolves an action's target against live UI state, or refuses it (spec §7.5).
 *
 * > *"Before any action touches the device, the executor confirms the target
 * > element/state exists in the current tree. A hallucinated 'tap Send button'
 * > with no Send node present is rejected here, before harm."*
 *
 * ## Why this is a separate, pure object
 *
 * It is the second of AXON's three defences and it catches a class the other two
 * cannot:
 *
 * | defence | rejects | when |
 * |---|---|---|
 * | grammar (C3) | malformed output — *wrong shape* | during sampling |
 * | **this gate** | well-formed action naming something absent — *wrong world* | **before the device is touched** |
 * | verifier (C2) | plausible action that did not work — *wrong outcome* | after acting |
 *
 * A hallucinated target is the most common small-model failure, and it is the
 * only one of the three that can be caught *before* the device is touched. That
 * makes it a safety property, not merely an accuracy one: an ungated agent that
 * fails to resolve "Send" does not do nothing — it dispatches a tap at whatever
 * occupies those coordinates.
 *
 * Being pure and free of any driver dependency, the whole gate is exercised on
 * the JVM with hand-built trees, which is what lets Phase 2's behaviour be tested
 * without a phone.
 */
public object PreconditionGate {

    /**
     * Check [action] against [state].
     *
     * @param state the tree the planner chose from — *not* a freshly captured
     *   one. Gating against a newer screen would turn an ordinary race into an
     *   unexplainable rejection, and would mean the planner is judged on a world
     *   it never saw.
     */
    public fun check(action: DeviceAction, state: UiTree): GateResult {
        val selector = action.target
            // Device-directed actions (launch_app, press_key, wait) address no
            // element, so there is nothing to resolve and nothing to reject.
            ?: return GateResult.Allowed(null)

        val requirement = action.affordance()
        val matches = state.nodes.filter { it.matches(selector) }

        if (matches.isEmpty()) {
            return GateResult.Rejected(
                PreconditionFailure.TargetNotFound(selector.describe()),
                alternatives = state.suggestAlternatives(selector),
            )
        }

        val enabled = matches.filter { it.enabled }
        if (enabled.isEmpty()) {
            return GateResult.Rejected(PreconditionFailure.TargetDisabled(selector.describe()))
        }

        // Narrow to nodes that can actually receive this action *before* judging
        // ambiguity. A screen may hold several nodes with the same label where
        // only one is interactive — a heading and its button, say — and calling
        // that ambiguous would refuse a perfectly determinate action.
        val capable = enabled.filter { requirement.isSatisfiedBy(it) }

        // Explicit evidence beats absence of evidence.
        //
        // Requirements admit nodes that merely fail to contradict the action,
        // because Android's flags are unreliable (see [Affordance]). That
        // tolerance would otherwise manufacture ambiguity: a section heading and
        // its button share a label, the heading declares nothing and so passes,
        // and a determinate action gets refused as ambiguous. When any candidate
        // *declares* the affordance, only the declaring ones are considered.
        val declaring = capable.filter { requirement.isDeclaredBy(it) }
        val candidates = declaring.ifEmpty { capable }

        return when {
            candidates.isEmpty() -> GateResult.Rejected(
                PreconditionFailure.WrongAffordance(selector.describe(), requirement.description),
            )

            candidates.size == 1 -> GateResult.Allowed(candidates.single())

            else -> GateResult.Rejected(
                PreconditionFailure.AmbiguousTarget(selector.describe(), candidates.size),
                alternatives = candidates.mapNotNull { it.label }.distinct(),
            )
        }
    }

    /**
     * What an action needs of its target.
     *
     * Android's accessibility flags are advisory and widely wrong — plenty of
     * tappable rows never set `clickable`. Requirements are therefore treated as
     * *positive evidence* rather than as necessary conditions: a node that
     * declares the affordance passes, and a node that declares nothing is given
     * the benefit of the doubt. Only a node that positively contradicts the
     * action — typing into something that declares itself a non-editable button
     * — is refused.
     *
     * The alternative, insisting on correct flags, would make AXON fail on real
     * apps for reasons that have nothing to do with the model.
     */
    public sealed interface Affordance {
        public val description: String

        /** May this node receive the action — i.e. does it not contradict it? */
        public fun isSatisfiedBy(node: UiNode): Boolean

        /**
         * Does the node *positively declare* the affordance?
         *
         * Separate from [isSatisfiedBy] so the gate can prefer explicit evidence
         * when several candidates match, without rejecting nodes whose flags are
         * simply missing.
         */
        public fun isDeclaredBy(node: UiNode): Boolean

        public data object Clickable : Affordance {
            override val description: String get() = "clickable"
            override fun isSatisfiedBy(node: UiNode): Boolean =
                node.clickable || (!node.editable && !node.scrollable)
            override fun isDeclaredBy(node: UiNode): Boolean = node.clickable
        }

        public data object Editable : Affordance {
            override val description: String get() = "a text field"
            // Strict: typing into a non-editable node cannot work, and unlike a
            // missed tap it can land keystrokes somewhere unintended.
            override fun isSatisfiedBy(node: UiNode): Boolean = node.editable
            override fun isDeclaredBy(node: UiNode): Boolean = node.editable
        }

        public data object Scrollable : Affordance {
            override val description: String get() = "scrollable"
            override fun isSatisfiedBy(node: UiNode): Boolean = node.scrollable
            override fun isDeclaredBy(node: UiNode): Boolean = node.scrollable
        }

        public data object Any : Affordance {
            override val description: String get() = "present"
            override fun isSatisfiedBy(node: UiNode): Boolean = true
            // Nothing to declare, so no candidate is privileged over another.
            override fun isDeclaredBy(node: UiNode): Boolean = false
        }
    }
}

/** The affordance an action requires of its target. */
private fun DeviceAction.affordance(): PreconditionGate.Affordance = when (this) {
    is DeviceAction.Tap, is DeviceAction.LongPress -> PreconditionGate.Affordance.Clickable
    is DeviceAction.InputText -> PreconditionGate.Affordance.Editable
    is DeviceAction.Scroll -> PreconditionGate.Affordance.Scrollable
    // A swipe may be aimed at a container that does not declare scrollability —
    // a carousel, a dismissible card — so presence is all that is required.
    is DeviceAction.Swipe -> PreconditionGate.Affordance.Any
    is DeviceAction.LaunchApp, is DeviceAction.PressKey, is DeviceAction.Wait ->
        PreconditionGate.Affordance.Any
}

/** Human-readable selector for failure messages and the audit log (§16). */
public fun Target.describe(): String = "${by.name.lowercase()}=\"$value\""

/**
 * Labels close to what the planner asked for.
 *
 * Fed back into the re-plan (§7.6). "No element matching text=\"Send\"; this
 * screen has: Send message, Attach, Camera" is recoverable; "target not found"
 * invites the model to try the same thing again.
 */
private fun UiTree.suggestAlternatives(selector: Target): List<String> {
    val wanted = selector.value.lowercase()
    return interactable()
        .mapNotNull { it.label }
        .filter { label ->
            val l = label.lowercase()
            l.contains(wanted) || wanted.contains(l)
        }
        .distinct()
        .take(5)
}

/** Outcome of the gate. */
public sealed interface GateResult {

    /**
     * The action may proceed.
     *
     * [node] is the single resolved element, or `null` for device-directed
     * actions. Returning the node rather than a bare boolean means the driver
     * acts on the element the gate approved, instead of re-resolving the
     * selector and possibly landing on a different one.
     */
    public data class Allowed(val node: UiNode?) : GateResult

    public data class Rejected(
        val failure: PreconditionFailure,

        /** Labels present on screen that resemble the request. */
        val alternatives: List<String> = emptyList(),
    ) : GateResult {
        /** Message handed to the planner as an invalid-action signal (§7.5). */
        public fun explain(): String = buildString {
            append(failure.explanation)
            if (alternatives.isNotEmpty()) {
                append("; this screen has: ").append(alternatives.joinToString(", "))
            }
        }
    }
}
