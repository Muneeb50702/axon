package dev.axon.bench

import dev.axon.core.skills.SelectorPolicy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E26b's result, pinned.
 *
 * These assertions are the *finding*, not a smoke test. E26 shipped selector
 * promotion described in its own comments as taking "the most drift-resistant
 * form", as though the ordering `id > content_desc > text` made it strictly
 * better. It does not. If the policy is changed later, this file says exactly
 * which drift classes the change trades away — which is what a reasoned policy
 * needs and what E26 shipped without.
 */
class SkillDriftStudyTest {

    @Test
    fun `the three arms really do compile different selectors`() = runTest {
        // Without this the study could be comparing a skill against itself and
        // every row would agree for the wrong reason.
        val r = SkillDriftStudy.run()
        assertEquals("text", r.selectors[SelectorPolicy.NONE]?.by?.wire)
        assertEquals("content_desc", r.selectors[SelectorPolicy.LABEL]?.by?.wire)
        assertEquals("id", r.selectors[SelectorPolicy.STURDIEST]?.by?.wire)
    }

    @Test
    fun `promotion survives copy changes that break the recorded selector`() = runTest {
        // The case E26 was reasoned from, and it holds.
        val r = SkillDriftStudy.run()

        assertTrue(r.survived(DriftClass.COPY_AND_DESC, SelectorPolicy.STURDIEST))
        assertTrue(!r.survived(DriftClass.COPY_AND_DESC, SelectorPolicy.NONE))

        assertTrue(r.survived(DriftClass.ICONIFIED, SelectorPolicy.STURDIEST))
        assertTrue(!r.survived(DriftClass.ICONIFIED, SelectorPolicy.NONE))
    }

    @Test
    fun `promoting to a view id breaks on drift the recorded selector survives`() = runTest {
        // THE FINDING E26 DID NOT ANTICIPATE. A Compose migration removes view
        // ids wholesale while changing nothing a user sees, and the *promoted*
        // skill is the one that dies.
        val r = SkillDriftStudy.run()

        for (drift in listOf(DriftClass.ID_REMOVED, DriftClass.ID_RENAMED)) {
            assertTrue(!r.survived(drift, SelectorPolicy.STURDIEST), "$drift should break id")
            assertTrue(r.survived(drift, SelectorPolicy.NONE), "$drift should not break text")
            assertTrue(r.survived(drift, SelectorPolicy.LABEL), "$drift should not break content_desc")
        }
    }

    @Test
    fun `the shipping policy is not better than the label policy, only different`() = runTest {
        // The result that should govern any future change of default. Equal
        // counts, disjoint failures — so "promote to the sturdiest handle" is a
        // bet on copy changing more often than resource ids, not a free win.
        val r = SkillDriftStudy.run()

        assertEquals(
            r.survivedCount(SelectorPolicy.LABEL),
            r.survivedCount(SelectorPolicy.STURDIEST),
            "if these ever differ, the drift model changed and E26b needs rewriting",
        )

        val labelOnly = DriftClass.ALL.filter {
            r.survived(it, SelectorPolicy.LABEL) && !r.survived(it, SelectorPolicy.STURDIEST)
        }
        val sturdiestOnly = DriftClass.ALL.filter {
            !r.survived(it, SelectorPolicy.LABEL) && r.survived(it, SelectorPolicy.STURDIEST)
        }
        assertTrue(labelOnly.isNotEmpty(), "LABEL must win somewhere or it is dominated")
        assertTrue(sturdiestOnly.isNotEmpty(), "STURDIEST must win somewhere or it is dominated")
    }

    @Test
    fun `a pure reorder breaks nothing`() = runTest {
        // Selectors are attribute-based, so position must not matter. Fails the
        // day someone adds an index- or order-dependent selector kind.
        val r = SkillDriftStudy.run()
        for (policy in SelectorPolicy.entries) {
            assertTrue(r.survived(DriftClass.REORDERED, policy), "$policy broke on a reorder")
        }
    }

    @Test
    fun `no policy survives every drift class`() = runTest {
        // The study's own validity check. If one arm swept the table, the drift
        // model would have been built to reach a conclusion rather than to test
        // one, and every other assertion here would be worthless.
        val r = SkillDriftStudy.run()
        for (policy in SelectorPolicy.entries) {
            assertTrue(
                DriftClass.ALL.any { !r.survived(it, policy) },
                "$policy survived every drift class — the model is rigged",
            )
        }
        assertTrue(r.decisive().size >= 4, "too few decisive classes to say anything")
    }
}
