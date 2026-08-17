package dev.axon.bench

import dev.axon.core.model.CompiledSkill
import dev.axon.core.model.CompiledStep
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.SkillManifest

/** Shared fixtures for the E26b/E27b studies' tests. */
object SkillDriftFixtures {

    /**
     * A skill with the given replay counters and nothing else of interest.
     *
     * Used to check the retirement study's restatement of `isHealthy` against
     * the real property, over every reachable `(replays, clean)` pair. The
     * counters are what the rule reads; the body is irrelevant to it.
     */
    fun withCounters(replays: Int, clean: Int) = CompiledSkill(
        manifest = SkillManifest(
            id = "s", name = "s", goalPattern = "s", deviceFamily = "fake/test",
        ),
        steps = listOf(
            CompiledStep(
                step = 1,
                selector = null,
                action = "press_key",
                expect = PostCondition(PostConditionType.NODE_PRESENT, "x"),
                args = mapOf("key" to "back"),
            ),
        ),
        replayCount = replays,
        repairCount = replays - clean,
    )
}
