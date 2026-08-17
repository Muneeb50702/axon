package dev.axon.bench

import dev.axon.core.model.PostConditionType
import dev.axon.core.verifier.TextMatching
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The AXON-Bench corpus is a **research artefact**, so it is checked like one.
 *
 * A benchmark's credibility rests on properties a reader cannot verify by
 * reading it: that ids are unique, that oracles are actually evaluable, that a
 * robustness variant differs from its parent *only* in the perturbation. Each of
 * those, broken, produces a results table that looks fine and means something
 * else — and unlike a bug in the runtime, nothing at run time would complain.
 */
class BenchCorpusTest {

    @Test
    fun `the corpus has the tiers §14_1 specifies`() {
        assertEquals(10, BenchCorpus.CORE.size, "§14.1: ten core tasks")
        assertEquals(5, BenchCorpus.LONG_HORIZON.size, "§14.1: five long-horizon tasks")
        assertTrue(BenchCorpus.ROBUSTNESS.isNotEmpty(), "§14.1: robustness variants")
        assertEquals(
            BenchCorpus.CORE.size + BenchCorpus.ROBUSTNESS.size + BenchCorpus.LONG_HORIZON.size,
            BenchCorpus.ALL.size,
        )
    }

    @Test
    fun `task ids are unique`() {
        // Results are keyed by id. A duplicate silently overwrites a row, and
        // the table still adds up.
        val ids = BenchCorpus.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}")
    }

    @Test
    fun `every task has an oracle and a positive optimal step count`() {
        for (t in BenchCorpus.ALL) {
            assertTrue(t.successOracle.isNotEmpty(), "${t.id} has no success oracle")
            assertTrue(t.optimalSteps > 0, "${t.id} has optimalSteps=${t.optimalSteps}")
            assertTrue(t.goal.isNotBlank(), "${t.id} has no goal")
        }
    }

    @Test
    fun `every regex oracle actually compiles`() {
        // A TEXT_MATCHES oracle whose pattern is malformed degrades to literal
        // containment rather than throwing (the verifier's safe-pattern policy,
        // which exists so a 1B model cannot crash a run). That is right for
        // model-authored patterns and wrong for hand-authored ones: a typo in a
        // task file would silently become a literal search for "[0-9]+%" and the
        // task would fail for a reason unrelated to the agent.
        for (t in BenchCorpus.ALL) {
            for (c in t.successOracle + t.intermediateConditions) {
                if (c.type != PostConditionType.TEXT_MATCHES) continue
                assertTrue(
                    runCatching { Regex(c.value) }.isSuccess,
                    "${t.id}: oracle pattern does not compile: '${c.value}'",
                )
            }
        }
    }

    @Test
    fun `a robustness variant differs from its parent only in the perturbation`() {
        // The entire value of the tier. If a variant also reworded its goal or
        // changed its oracle, a drop against the parent would be attributable to
        // the rewrite rather than to the perturbation, and the tier would
        // measure nothing.
        for (v in BenchCorpus.ROBUSTNESS) {
            assertTrue(v.perturbation != null, "${v.id} is in the robustness tier with no perturbation")

            val parent = BenchCorpus.CORE.firstOrNull { it.goal == v.goal }
            assertTrue(parent != null, "${v.id} has no core parent sharing its goal")

            assertEquals(parent.successOracle, v.successOracle, "${v.id} changed its parent's oracle")
            assertEquals(parent.optimalSteps, v.optimalSteps, "${v.id} changed its parent's step count")
            assertEquals(parent.params, v.params, "${v.id} changed its parent's params")
        }
    }

    @Test
    fun `long-horizon tasks are actually long`() {
        // §14.1 says "6+ step compositions". A tier that quietly contained
        // four-step tasks would understate exactly the cost C1′ claims to remove.
        for (t in BenchCorpus.LONG_HORIZON) {
            assertTrue(t.optimalSteps >= 6, "${t.id} has only ${t.optimalSteps} optimal steps")
        }
    }

    @Test
    fun `irreversible tasks are flagged for the confirmation gate`() {
        // §16: sending and calling need explicit approval. A benchmark cannot
        // answer a prompt, so these run only with the gate opened — and the
        // results table has to say which tasks those were. Flagging them by hand
        // is checked here against the goal text, so a new send/call task cannot
        // be added unflagged.
        //
        // Matches the verb in *verb position* — at the start of the goal, or
        // after a conjunction — rather than anywhere in it. A substring search
        // flagged "find Ammi's last message and set an alarm", where "message"
        // is a noun and the task only reads one. Over-flagging is not harmless:
        // it would move a task out of the UNGATED set, shrinking the subset that
        // reports what AXON does unattended, which is the number closest to what
        // a user actually gets.
        val irreversibleVerb = Regex(
            """(^|\b(?:and|then|,)\s+)(send|call|message|tell|delete|pay)\b""",
            RegexOption.IGNORE_CASE,
        )
        val looksIrreversible = BenchCorpus.ALL.filter { irreversibleVerb.containsMatchIn(it.goal) }
        for (t in looksIrreversible) {
            assertTrue(
                t.requiresConfirmation,
                "${t.id} ('${t.goal}') crosses §16's gate but is not flagged",
            )
        }

        assertTrue(BenchCorpus.UNGATED.size < BenchCorpus.ALL.size, "some tasks must be gated")
        assertTrue(BenchCorpus.UNGATED.none { it.requiresConfirmation })
    }

    @Test
    fun `read-only tasks exist, so navigation can be distinguished from luck`() {
        // A corpus made entirely of state changes cannot tell "navigated
        // correctly" from "changed something and got lucky".
        val readOnly = listOf("battery_level", "find_setting").map(BenchCorpus::byId)
        for (t in readOnly) {
            assertTrue(!t.requiresConfirmation, "${t.id} should not change anything")
        }
    }

    @Test
    fun `oracle values are non-empty, since an empty pattern matches everything`() {
        for (t in BenchCorpus.ALL) {
            for (c in t.successOracle + t.intermediateConditions) {
                assertTrue(c.value.isNotBlank(), "${t.id}: an oracle with a blank value passes trivially")
            }
        }
    }

    @Test
    fun `a percentage oracle matches a real battery reading`() {
        // Spot-check that the shipped patterns mean what they look like, using
        // the same matcher the verifier uses rather than a fresh Regex — so the
        // test fails if the verifier's semantics ever diverge from the corpus's
        // assumptions.
        val pattern = BenchCorpus.byId("battery_level").successOracle
            .first { it.type == PostConditionType.TEXT_MATCHES }.value

        // Uses the verifier's own compiler, not a fresh Regex, so this fails if
        // the verifier's semantics ever diverge from the corpus's assumptions.
        val regex = TextMatching.compile(pattern)
        assertTrue(regex != null, "the shipped pattern must be a usable regex, not a literal fallback")
        assertTrue(regex.containsMatchIn("Battery 42%"), "should match a real reading")
        assertTrue(!regex.containsMatchIn("Battery low"), "should not match without a number")
    }
}
