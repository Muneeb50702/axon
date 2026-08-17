package dev.axon.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The action vocabulary the planner may emit (spec §10.1).
 *
 * ## Why this is a sealed hierarchy and not one flat data class
 *
 * The spec's §10.1 sketch is a single object with optional fields:
 *
 * ```
 * { "action": "...", "target": {...}, "text": "...", "direction": "...",
 *   "app": "...", "key": "...", "expect": {...} }
 * ```
 *
 * That shape permits incoherent actions that are nonetheless *schema-valid* —
 * `{"action":"tap","direction":"up"}` parses fine and means nothing. Contribution
 * C3 claims the action space is "structurally valid by construction". A flat
 * object only delivers half of that: valid *syntax*, not valid *combinations*.
 *
 * So AXON tightens it. Each action type is its own variant carrying exactly the
 * fields that action can legally have. This is enforced in three places that are
 * kept in 1:1 correspondence, and that correspondence is the C3 argument:
 *
 * | layer                | mechanism                                      |
 * |----------------------|------------------------------------------------|
 * | model (this file)    | sealed interface — illegal combos don't compile |
 * | wire format          | `action` discriminator — matches §10.1 exactly  |
 * | decoding (§10.6)     | GBNF discriminated union — illegal combos are   |
 * |                      | unreachable token paths during sampling         |
 *
 * The wire format is unchanged from §10.1: every variant still serialises to a
 * flat object whose first key is `"action"`. A conformance test asserts this
 * ([dev.axon.core.model.DeviceActionWireFormatTest]), so §10.1 remains normative
 * for anything reading the JSON — we only removed the field combinations the
 * spec never intended to allow.
 *
 * ## Every action carries its own post-condition
 *
 * [expect] is non-optional on every variant. The verifier (§7.6, C2) is
 * deterministic precisely because the planner is forced to commit, in advance,
 * to an observable consequence of the action. An action with no stated expected
 * outcome cannot be verified, so the type system refuses to represent one.
 */
@Serializable
@JsonClassDiscriminator("action")
sealed interface DeviceAction {

    /** The observable state change this action is expected to cause (§7.6). */
    val expect: PostCondition

    /**
     * The UI element this action addresses, or `null` for actions that address
     * the device rather than an element (`launch_app`, `press_key`, `wait`).
     *
     * The executor's precondition gate (§7.5) resolves this against the live
     * tree *before* the action touches the device; a target that does not
     * resolve is rejected as a hallucination, not attempted.
     */
    val target: Target?

    @Serializable
    @SerialName("tap")
    data class Tap(
        override val target: Target,
        override val expect: PostCondition,
    ) : DeviceAction

    @Serializable
    @SerialName("long_press")
    data class LongPress(
        override val target: Target,
        override val expect: PostCondition,
    ) : DeviceAction

    @Serializable
    @SerialName("input_text")
    data class InputText(
        override val target: Target,
        val text: String,
        override val expect: PostCondition,
    ) : DeviceAction

    /**
     * A directional swipe. [target] is optional: present to swipe *within* a
     * specific element (a carousel, a list row), absent to swipe the screen.
     */
    @Serializable
    @SerialName("swipe")
    data class Swipe(
        val direction: Direction,
        override val target: Target? = null,
        override val expect: PostCondition,
    ) : DeviceAction

    /** Scroll a container. [target] is optional; absent means the scrollable root. */
    @Serializable
    @SerialName("scroll")
    data class Scroll(
        val direction: Direction,
        override val target: Target? = null,
        override val expect: PostCondition,
    ) : DeviceAction

    /** Launch an app by package name, e.g. `com.whatsapp`. */
    @Serializable
    @SerialName("launch_app")
    data class LaunchApp(
        val app: String,
        override val expect: PostCondition,
    ) : DeviceAction {
        override val target: Target? get() = null
    }

    /** Press a hardware/navigation key. */
    @Serializable
    @SerialName("press_key")
    data class PressKey(
        val key: DeviceKey,
        override val expect: PostCondition,
    ) : DeviceAction {
        override val target: Target? get() = null
    }

    /**
     * Wait for the UI to settle. The only action whose *purpose* is to let a
     * post-condition become true without touching the device — how the loop
     * handles animations, network spinners and app cold starts without the
     * planner inventing a fake tap to burn a step.
     */
    @Serializable
    @SerialName("wait")
    data class Wait(
        override val expect: PostCondition,
        val timeoutMs: Long = DEFAULT_WAIT_MS,
    ) : DeviceAction {
        override val target: Target? get() = null

        companion object {
            const val DEFAULT_WAIT_MS: Long = 2_000
        }
    }

    companion object {
        /**
         * The `action` discriminator values, in the order they appear in §10.1.
         *
         * Single source of truth: the GBNF generator (§10.6) and the ablation's
         * valid-action checker (§14.2) both read this list, so the grammar can
         * never drift from the model. Adding a variant without adding it here is
         * caught by [dev.axon.core.model.DeviceActionWireFormatTest].
         */
        val ACTION_TYPES: List<String> = listOf(
            "tap", "long_press", "swipe", "input_text",
            "scroll", "launch_app", "press_key", "wait",
        )
    }
}

/**
 * How to locate a UI element (§10.1 `target.by`). Closed enum — the grammar
 * makes any other selector kind unreachable during sampling.
 */
@Serializable
enum class TargetBy {
    /** Visible text of the node. */
    @SerialName("text") TEXT,

    /** Android view id resource name, e.g. `com.whatsapp:id/send`. */
    @SerialName("id") ID,

    /** Accessibility content description — usually the most stable selector. */
    @SerialName("content_desc") CONTENT_DESC,

    /** Widget class, e.g. `android.widget.EditText`. */
    @SerialName("class") CLASS,

    /**
     * Raw screen coordinates, `"x,y"`.
     *
     * Deliberately last-resort: coordinates do not survive a device rotation, a
     * font-size change or a different screen density, so a skill compiled with
     * coordinate selectors is brittle by construction. The compiler (§7.7)
     * penalises these when choosing a selector.
     */
    @SerialName("coord") COORD;

    /**
     * The name this selector goes by on the wire — the same string `@SerialName`
     * emits.
     *
     * Exists because the screen grammar (E31) writes `by` values directly into
     * GBNF literals, and it must write exactly what the parser will later read.
     * Reaching for `name.lowercase()` would produce `content_desc` correctly and
     * `coord` correctly and then silently disagree the day a variant is added
     * whose Kotlin name is not its wire name — a grammar that emits a token the
     * deserialiser rejects, which is the one failure C3 exists to make
     * impossible.
     *
     * Kept beside the annotations rather than derived from the serial
     * descriptor so the two are edited together and a mismatch is visible on one
     * screen. `TargetByWireTest` asserts they agree.
     */
    public val wire: String
        get() = when (this) {
            TEXT -> "text"
            ID -> "id"
            CONTENT_DESC -> "content_desc"
            CLASS -> "class"
            COORD -> "coord"
        }
}

/** An element selector: a strategy plus the value to match (§10.1). */
@Serializable
data class Target(
    val by: TargetBy,
    val value: String,
)

/** Swipe/scroll direction (§10.1). Closed enum. */
@Serializable
enum class Direction {
    @SerialName("up") UP,
    @SerialName("down") DOWN,
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT,
}

/**
 * Navigation keys the agent may press (§10.1 `key`).
 *
 * Intentionally minimal. Notably absent: anything that could dismiss the
 * foreground-service notification or background the agent invisibly — §16
 * requires the agent's operation to stay visible to the user at all times.
 */
@Serializable
enum class DeviceKey {
    @SerialName("home") HOME,
    @SerialName("back") BACK,
    @SerialName("enter") ENTER,
}
