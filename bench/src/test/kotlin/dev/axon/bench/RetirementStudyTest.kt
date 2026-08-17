package dev.axon.bench

import dev.axon.core.model.CompiledSkill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E27b's arithmetic, checked — and its findings, pinned.
 *
 * The study is an exact calculation rather than a simulation, so it can be
 * verified against closed forms at the points where one exists. That matters
 * more than usual here: an off-by-one in the absorbing condition would produce a
 * plausible table that quietly misstates what the shipping constants cost.
 */
class RetirementStudyTest {

    // ------------------------------------------------------- arithmetic ------

    @Test
    fun `the first judgement matches the binomial by hand`() {
        // At exactly MIN_REPLAYS_TO_JUDGE = 3, retirement needs clean <= 1.
        // With p = 0.9:  P(0 clean) + P(1 clean) = 0.1^3 + 3(0.9)(0.1^2)
        //                                        = 0.001 + 0.027 = 0.028
        val expected = 0.001 + 0.027
        assertEquals(expected, RetirementStudy.probabilityRetired(0.9, horizon = 3), 1e-9)
    }

    @Test
    fun `a coin-flip skill is retired at the first judgement half the time`() {
        // p = 0.5, n = 3: P(clean <= 1) = (1 + 3) / 8 = 0.5. A closed form that
        // the DP must reproduce exactly or its absorbing condition is wrong.
        assertEquals(0.5, RetirementStudy.probabilityRetired(0.5, horizon = 3), 1e-9)
    }

    @Test
    fun `a skill that never needs repair is never retired`() {
        assertEquals(0.0, RetirementStudy.probabilityRetired(1.0), 1e-12)
    }

    @Test
    fun `a skill that always needs repair is retired at the earliest legal moment`() {
        assertEquals(1.0, RetirementStudy.probabilityRetired(0.0, horizon = 3), 1e-12)
        assertEquals(0.0, RetirementStudy.probabilityRetired(0.0, horizon = 2), 1e-12)
    }

    @Test
    fun `the rule restated over counts agrees with the runtime`() {
        // The study reimplements `isHealthy` over raw counts. If the two ever
        // disagree, every number in the table describes a rule that is not the
        // one running.
        for (replays in 0..12) {
            for (clean in 0..replays) {
                val skill = SkillDriftFixtures.withCounters(replays, clean)
                assertEquals(
                    !skill.isHealthy,
                    RetirementStudy.isRetiredAt(replays, clean),
                    "disagreement at replays=$replays clean=$clean",
                )
            }
        }
    }

    // ---------------------------------------------------------- findings -----

    @Test
    fun `retirement risk is front-loaded`() {
        // THE FINDING. Nearly all the lifetime false-retirement risk for a
        // healthy skill is incurred at the very first judgement, which is why
        // the minimum-sample constant is the lever and the rate threshold is
        // not.
        val atFirstJudgement = RetirementStudy.probabilityRetired(0.9, horizon = 3)
        val lifetime = RetirementStudy.probabilityRetired(0.9, horizon = 50)

        assertTrue(lifetime > atFirstJudgement, "later judgements must add something")
        assertTrue(
            atFirstJudgement / lifetime > 0.8,
            "expected the first judgement to dominate; got " +
                "${atFirstJudgement / lifetime} of the lifetime risk",
        )
    }

    @Test
    fun `a working skill can be permanently retired, and the rate is not negligible`() {
        // Retirement is absorbing, so these are permanent losses costing a full
        // cold re-learn. Stated as a range rather than an exact figure so the
        // test survives a deliberate constant change while still failing if the
        // rule silently becomes far harsher or toothless.
        val at90 = RetirementStudy.probabilityRetired(0.9)
        val at80 = RetirementStudy.probabilityRetired(0.8)

        assertTrue(at90 in 0.01..0.10, "P(retire | 90% clean) = $at90")
        assertTrue(at80 in 0.05..0.25, "P(retire | 80% clean) = $at80")
        assertTrue(at80 > at90, "a worse skill must be likelier to be retired")
    }

    @Test
    fun `a genuinely rotten skill is caught almost always`() {
        // The rule does the job it was written for. Worth asserting so the
        // findings above read as "this costs something", not "this is broken".
        assertTrue(RetirementStudy.probabilityRetired(0.3) > 0.99)
        assertTrue(RetirementStudy.probabilityRetired(0.2) > 0.99)
    }

    @Test
    fun `raising the minimum sample reduces false retirement without losing detection`() {
        // The lever, and the reason the study stops short of recommending a
        // value: this looks free and is not, once the extra repaired replays a
        // rotten skill costs before retirement are priced at a planner call
        // each. See the study's own closing note.
        val strict = RetirementStudy.probabilityRetired(0.9, minReplays = 3)
        val lenient = RetirementStudy.probabilityRetired(0.9, minReplays = 6)

        assertTrue(lenient < strict, "a larger sample should retire healthy skills less often")
        assertTrue(
            RetirementStudy.probabilityRetired(0.3, minReplays = 6) > 0.99,
            "detection of rotten skills must survive the change, or this is not a free lever at all",
        )
    }

    @Test
    fun `the shipping constants are the ones described`() {
        // Guards the prose in docs/EXPERIMENTS.md, which quotes both.
        assertEquals(3, CompiledSkill.MIN_REPLAYS_TO_JUDGE)
        assertEquals(0.5, CompiledSkill.MIN_CLEAN_REPLAY_RATE)
    }
}
