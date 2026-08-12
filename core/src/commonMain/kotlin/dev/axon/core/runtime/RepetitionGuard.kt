package dev.axon.core.runtime

import dev.axon.core.model.DeviceAction

/**
 * Refuses an action that has already failed on this exact screen.
 *
 * ## Why this exists — a measured failure, not a hypothetical one
 *
 * Experiment E18. Asked to "open whatsapp", the planner produced
 * `tap content_desc="Whatsapp"` three times in a row. The gate rejected all
 * three, the run escalated, and 182 seconds bought nothing.
 *
 * The failure context was working: [dev.axon.core.planner.FailureContext] was
 * populated, rendered into the prompt, and explicitly said *"already tried and
 * failed on this screen … choose a DIFFERENT action"*. The model read it and
 * repeated itself anyway.
 *
 * That is worth stating as a finding rather than patching around quietly:
 * **prompt-based self-healing does not work at ~1B.** The instruction is
 * understood in the sense that it is present; it simply does not outweigh
 * whatever made the action look best the first time, and nothing about the
 * screen has changed to make it look worse.
 *
 * ## The response is architectural, because that is this project's whole thesis
 *
 * §2.3: *"reliability is an architecture problem, not a model-size problem…
 * AXON solves this not by making the model smarter, but by making the model's
 * freedom smaller."*
 *
 * Applied consistently, that gives a third structural constraint alongside the
 * two already in place:
 *
 * | mechanism | makes impossible | when |
 * |---|---|---|
 * | GBNF grammar (C3) | malformed actions | during sampling |
 * | precondition gate (§7.5) | actions naming absent elements | before acting |
 * | **this guard** | **re-proposing an action already rejected here** | before acting |
 *
 * Each replaces an instruction the model might ignore with a rule it cannot.
 *
 * ## Why the screen hash is part of the key
 *
 * Blocking an action outright would be wrong. Tapping "Send" can fail on one
 * screen and be exactly right two screens later, and an agent that permanently
 * blacklisted it would be unable to finish the task it was blocked from
 * starting. The key is therefore `(screen, action)`: an action is refused only
 * while the world that rejected it is unchanged. As soon as the screen differs —
 * which is to say, as soon as anything the agent did had an effect — the slate
 * is clear.
 */
public class RepetitionGuard {

    private val blocked = mutableSetOf<Key>()

    /**
     * Record that [action] failed on the screen identified by [screenHash].
     */
    public fun recordFailure(screenHash: Int, action: DeviceAction) {
        blocked += Key(screenHash, action.signature())
    }

    /** Has this action already failed on this exact screen? */
    public fun isBlocked(screenHash: Int, action: DeviceAction): Boolean =
        Key(screenHash, action.signature()) in blocked

    /** Actions already known to fail here, for the planner's failure context. */
    public fun blockedOn(screenHash: Int): Set<String> =
        blocked.filter { it.screenHash == screenHash }.map { it.signature }.toSet()

    /** Clear everything. Called between tasks, not between steps. */
    public fun reset() {
        blocked.clear()
    }

    public val size: Int get() = blocked.size

    private data class Key(val screenHash: Int, val signature: String)
}

/**
 * Identity of an action for repetition purposes.
 *
 * Compares what the action *does*, not the object — two `tap`s at the same
 * target are the same attempt even if their `expect` clauses differ, because the
 * model routinely reworks its expectation while proposing the identical gesture.
 * Including `expect` in the key would let a cosmetic change slip the same failing
 * action past the guard, which is exactly the loop being prevented.
 *
 * `wait` is excluded from blocking by its caller rather than here: waiting twice
 * on an unchanged screen is legitimate, since the point of waiting is that the
 * screen has not changed *yet*.
 */
public fun DeviceAction.signature(): String = when (this) {
    is DeviceAction.Tap -> "tap:${target.by}:${target.value}"
    is DeviceAction.LongPress -> "long_press:${target.by}:${target.value}"
    is DeviceAction.InputText -> "input_text:${target.by}:${target.value}:$text"
    is DeviceAction.Swipe -> "swipe:$direction:${target?.value ?: "screen"}"
    is DeviceAction.Scroll -> "scroll:$direction:${target?.value ?: "screen"}"
    is DeviceAction.LaunchApp -> "launch_app:$app"
    is DeviceAction.PressKey -> "press_key:$key"
    is DeviceAction.Wait -> "wait"
}
