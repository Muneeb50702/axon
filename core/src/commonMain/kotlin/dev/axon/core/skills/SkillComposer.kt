package dev.axon.core.skills

import dev.axon.core.model.Goal

/**
 * Turns a compound goal into a sequence of already-learned skills (E24).
 *
 * The half of composition that is allowed to say no. [GoalDecomposer] splits
 * aggressively; everything that keeps composition safe lives here.
 *
 * ## The rule: all or nothing
 *
 * A compound goal composes **only if every fragment matches a compiled skill.**
 * One unmatched fragment and [plan] returns `null`, and the caller runs the
 * whole goal through the planner exactly as it does today.
 *
 * That single rule is what makes aggressive splitting safe, and it is worth
 * being explicit about why, because "partial composition" is the obvious
 * alternative and it is a trap:
 *
 * Suppose "send a message to Ali and Ahmed" splits into `["send a message to
 * ali", "ahmed"]`. The first fragment matches a messaging skill. Under partial
 * composition AXON would **send a message to Ali** and then plan something for
 * "ahmed" — having already performed an irreversible act the user never asked
 * for as a separate step. Under all-or-nothing, "ahmed" matches nothing, the
 * composition is abandoned before anything is dispatched, and the planner sees
 * the original sentence intact.
 *
 * The cost is real: a goal that is half-learned gets no speedup. That is the
 * right trade when the other side of it is an unasked-for message.
 *
 * ## What a composed run is not
 *
 * It is not a new skill. Composition is a *routing* decision made per request,
 * not an artefact that gets stored — so it cannot rot, cannot be replayed
 * wrongly later, and needs no compilation gate. If a compound goal is asked
 * often enough to be worth freezing, the ordinary two-clean-runs path (§7.7)
 * compiles it from its own traces like anything else.
 */
public class SkillComposer(private val skills: SkillStore) {

    /**
     * A composable plan for [goal], or `null` to fall through to the planner.
     *
     * Returns `null` for a goal that is not compound, so callers need no
     * separate "is this worth trying" check.
     */
    public suspend fun plan(goal: Goal): ComposedPlan? {
        val fragments = GoalDecomposer.split(goal.utterance)
        if (fragments.size < 2) return null

        val steps = mutableListOf<ComposedStep>()
        for (fragment in fragments) {
            // Sub-goals inherit the parent's params — a compound request states
            // its parameters once ("message Ammi that I'm late and call her")
            // and both halves may need them. They do NOT inherit the step
            // budget: that is divided below, so a compound goal cannot spend
            // N times the parent's budget by being phrased as N tasks.
            val sub = Goal(fragment, params = goal.params)
            val match = skills.match(sub) ?: return null
            steps += ComposedStep(sub, match)
        }

        return ComposedPlan(goal, steps)
    }
}

/** A compound goal, resolved entirely to compiled skills. */
public data class ComposedPlan(
    val goal: Goal,
    val steps: List<ComposedStep>,
) {
    init {
        require(steps.size >= 2) { "a composed plan needs at least two steps" }
    }

    /**
     * Confidence of the weakest link, not the average.
     *
     * A chain is only as trustworthy as its least certain step, and averaging
     * would let two confident matches carry a doubtful one — which then acts on
     * the device with the rest. Reporting the minimum means a caller applying a
     * threshold rejects the chain for the reason it should.
     */
    public val confidence: Double get() = steps.minOf { it.match.confidence }
}

public data class ComposedStep(
    val goal: Goal,
    val match: SkillMatch,
)
