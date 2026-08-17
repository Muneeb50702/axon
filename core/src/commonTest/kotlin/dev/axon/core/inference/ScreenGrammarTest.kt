package dev.axon.core.inference

import dev.axon.core.bench.ScreenCorpus
import dev.axon.core.model.CompactState
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Screen-grounded grammar specialisation.
 *
 * The property under test is the one E18 exposed: a label that is not on screen
 * must have no token path. These tests check the grammar *text*; that llama.cpp
 * accepts it is checked separately by `tools/check-grammar.sh` and by the
 * on-device validation at model load.
 */
class ScreenGrammarTest {

    private fun state(vararg labels: String): CompactState = CompactState.from(
        UiTree(
            foregroundPackage = "com.whatsapp",
            screenTitle = "Ammi",
            nodes = labels.mapIndexed { i, l ->
                UiNode(index = i, role = "button", text = l, contentDescription = l, clickable = true)
            },
            capturedAtMs = 0,
        ),
    )

    /**
     * The right-hand side of whichever screen production this grammar carries.
     *
     * Two exist since E31: `screen-target`, the grounded `{by, value}` pair, and
     * `screen-label`, the older value-only form kept for states with no label
     * provenance. Tests about *the alternation* should not care which.
     *
     * Fails loudly when neither is present. `substringAfter` returns the whole
     * input when the delimiter is missing, so the previous spelling of this made
     * `the alternation is capped` pass **vacuously** the moment the production
     * was renamed — it split the grammar's first line on `|`, got 1, and
     * declared the cap respected.
     */
    private fun alternation(g: Gbnf): String {
        val marker = listOf("screen-target ::=", "screen-label ::=")
            .firstOrNull { it in g.source }
            ?: error("grammar carries neither screen production:\n${g.source.takeLast(300)}")
        return g.source.substringAfter(marker).substringBefore('\n')
    }

    @Test
    fun `labels on screen become selectable`() {
        val g = ScreenGrammar.forScreen(state("Send", "Attach", "Camera"))

        assertTrue(ScreenGrammar.isSpecialised(g))
        for (label in listOf("Send", "Attach", "Camera")) {
            assertTrue("\\\"$label\\\"" in g.source, "'$label' should be selectable")
        }
    }

    @Test
    fun `a label not on screen has no token path`() {
        // The E18 case, made structural. The planner proposed
        // tap content_desc="Whatsapp" three times against a screen with no such
        // element. Under this grammar the sampler cannot produce that string.
        val g = ScreenGrammar.forScreen(state("Send", "Attach"))

        assertFalse(
            "\\\"Whatsapp\\\"" in g.source,
            "a label absent from the screen must not appear in the grammar",
        )
    }

    @Test
    fun `target uses the screen alternation while expect stays free`() {
        // A post-condition may legitimately mention text that is not on screen
        // *yet* — that is the entire point of asserting a future state. Only the
        // selector is constrained.
        val g = ScreenGrammar.forScreen(state("Send"))

        val targetRule = g.source.lineSequence().first { it.startsWith("target   ::=") }
        assertTrue(
            "screen-target" in targetRule || "screen-label" in targetRule,
            "target should use the screen alternation; got: $targetRule",
        )

        val expectRule = g.source.substringAfter("expect   ::=").substringBefore(")")
        assertTrue("string" in expectRule, "expect.value must remain a free string")
    }

    @Test
    fun `an empty screen falls back to the base grammar`() {
        // §17's canvas/DRM case. With nothing selectable, specialising would
        // leave the model no legal move at all — device-directed actions must
        // stay reachable so the agent can press back or relaunch.
        val empty = CompactState.from(UiTree.empty("com.example.game", capturedAtMs = 0))
        val g = ScreenGrammar.forScreen(empty)

        assertFalse(ScreenGrammar.isSpecialised(g))
        assertEquals(ActionGrammar.SOURCE, g.source)
    }

    @Test
    fun `labels containing quotes and backslashes are escaped`() {
        // Real apps ship these. An unescaped label produces a grammar that fails
        // to parse — and a grammar that fails to parse is not an error, it is a
        // silently unconstrained run, because llama.cpp declines to install the
        // sampler and generation proceeds anyway. That failure mode cost hours in
        // Phase 1, so escaping here is load-bearing rather than tidy.
        val g = ScreenGrammar.forScreen(state("""Say "hi"""", """back\slash""", "multi\nline"))

        assertTrue(ScreenGrammar.isSpecialised(g))
        val rule = alternation(g)
        assertFalse('\n' in rule, "a newline leaked into the alternation")

        // Since E31 each alternative is a *sequence* of GBNF string literals —
        // `"{" "\"by\"" ":" "\"content_desc\"" "," "\"value\"" ":" "\"Send\"" "}"`
        // — nine of them, of which the eighth carries the label.
        //
        // A label's own quote left unescaped would close its literal early and
        // desynchronise every boundary after it, so the count stops being nine.
        // That is the sharpest available signal at unit-test level, and it
        // matters because the resulting parse failure is *not* an error at run
        // time: llama.cpp declines to install the sampler and generation
        // proceeds unconstrained, i.e. C3 silently absent.
        for (alternative in rule.split('|').map { it.trim() }.filter { it.isNotEmpty() }) {
            val literals = gbnfLiterals(alternative)
            assertEquals(
                9, literals.size,
                "expected nine literals in a grounded selector; a label's quote " +
                    "probably closed its literal early: $alternative",
            )
            assertTrue(
                literals[3] == """\"content_desc\"""" || literals[3] == """\"text\"""",
                "the `by` literal should be a wire name, got: ${literals[3]}",
            )
        }
    }

    /**
     * The bodies of the double-quoted literals in [sequence], in order.
     *
     * A quote preceded by a backslash is part of the literal, not a delimiter —
     * which is the whole property under test, so this scanner has to honour it
     * rather than splitting on `"`.
     */
    private fun gbnfLiterals(sequence: String): List<String> {
        val literals = mutableListOf<String>()
        val current = StringBuilder()
        var inside = false
        var i = 0
        while (i < sequence.length) {
            val c = sequence[i]
            when {
                c == '\\' && i + 1 < sequence.length -> {
                    if (inside) current.append(c).append(sequence[i + 1])
                    i++
                }
                c == '"' -> {
                    if (inside) { literals += current.toString(); current.clear() }
                    inside = !inside
                }
                inside -> current.append(c)
            }
            i++
        }
        assertFalse(inside, "unterminated literal in: $sequence")
        return literals
    }

    @Test
    fun `the alternation is capped`() {
        // The cap is a real loss, not a formality: an element beyond it becomes
        // unnameable and the planner must scroll to reach it. Capped at the same
        // number the planner is shown, so the prompt and the grammar agree about
        // what exists — being told about an element one is forbidden to name
        // would be worse than not being told.
        val many = (1..80).map { "Item $it" }.toTypedArray()
        val g = ScreenGrammar.forScreen(state(*many))

        val alternatives = alternation(g).split('|').size

        assertTrue(alternatives > 1, "the alternation was not found — this test would pass vacuously")
        assertTrue(alternatives <= ScreenGrammar.MAX_LABELS, "alternation not capped: $alternatives")
        assertEquals(ScreenGrammar.MAX_LABELS, CompactState.MAX_NODES)
    }

    @Test
    fun `every corpus screen produces a usable grammar`() {
        // Exercised across the whole §14 corpus so a pathological label in any
        // benchmark screen shows up here rather than mid-run on the device.
        for (case in ScreenCorpus.ALL) {
            val g = ScreenGrammar.forScreen(case.state)
            assertTrue(g.source.isNotBlank(), "${case.id} produced an empty grammar")
            if (case.state.elements.any { it.label != null }) {
                assertTrue(
                    ScreenGrammar.isSpecialised(g),
                    "${case.id} has labels but was not specialised",
                )
            }
        }
    }

    @Test
    fun `plausible targets from the corpus are all selectable`() {
        // If a screen's own plausible target were unreachable, the grammar would
        // be preventing the correct action rather than only the wrong ones.
        for (case in ScreenCorpus.ALL) {
            val g = ScreenGrammar.forScreen(case.state)
            if (!ScreenGrammar.isSpecialised(g)) continue

            for (target in case.plausibleTargets) {
                assertTrue(
                    "\\\"$target\\\"" in g.source,
                    "${case.id}: plausible target '$target' is not selectable",
                )
            }
        }
    }
}
