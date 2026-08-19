package dev.axon.core.inference

import dev.axon.core.model.Bounds
import dev.axon.core.model.CompactState
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **The seam property**: every target the grammar can emit, the gate can resolve.
 *
 * ## Why this file exists rather than three more unit tests
 *
 * Three defects this session were the same defect: two mechanisms that must
 * agree, and did not.
 *
 * | id | disagreement |
 * |---|---|
 * | E31 | the grammar grounded `value` and left `by` free |
 * | E33 | the grammar emitted **trimmed** labels; the gate matched the raw attribute |
 * | E35 | the compiler matched one word of a pattern; the matcher matched the pattern |
 *
 * Each was found by hand, after it had already cost device time, and each was
 * fixed with a test naming that specific case. But a test that names a case only
 * catches the case — E33 slipped past the tests written for E31, in the same
 * function, one commit later.
 *
 * The generalisation is a *property*: `ScreenGrammar` and `UiNode.matches` are
 * two encodings of one question, so **anything the first offers, the second must
 * accept**. That single statement subsumes E31 and E33 and would have failed on
 * either the day it was introduced.
 *
 * ## The labels are adversarial on purpose
 *
 * A generator of tidy labels proves nothing: both defects needed a real-world
 * label shape to appear — a missing content-description, a trailing space. The
 * corpus below is drawn from what this device actually shipped (its launcher
 * carries `'PiKaChUu :) '`) plus the shapes that break string handling
 * generally.
 */
class GrammarGateAgreementTest {

    /** Label shapes that occur in real apps and break naive string handling. */
    private val nastyLabels = listOf(
        "Send",
        "Power ",                      // E33: trailing space, as this device ships
        " Leading",
        "  both  ",
        "Say \"hi\"",                  // quotes must survive GBNF escaping
        "back\\slash",
        "multi\nline",
        "Display & Brightness",        // ampersand, as Settings ships
        "Wi‑Fi",                       // U+2011 non-breaking hyphen, as Settings ships
        "Café",
        "日本語",
        "emoji 🎉 label",
        "PiKaChUu :) ",
        "a".repeat(60),                // long, near the cap
    )

    /**
     * Every combination of "which attributes does this node carry".
     *
     * The `text`-only case is E31; the `contentDescription`-only case is the
     * common one in real apps; both-present is what test fixtures usually build,
     * which is why the fixtures never caught anything.
     */
    private fun nodesFor(label: String): List<UiNode> = listOf(
        UiNode(0, "button", text = label, contentDescription = null,
            bounds = Bounds(0, 0, 100, 40), clickable = true),
        UiNode(0, "button", text = null, contentDescription = label,
            bounds = Bounds(0, 0, 100, 40), clickable = true),
        UiNode(0, "button", text = "different", contentDescription = label,
            bounds = Bounds(0, 0, 100, 40), clickable = true),
        UiNode(0, "button", text = label, contentDescription = "different",
            bounds = Bounds(0, 0, 100, 40), clickable = true),
    )

    /**
     * The `{by, value}` pairs the grammar can actually emit, decoded.
     *
     * Reads the **generated GBNF**, not `CompactState`. The first version of this
     * test asserted that `CompactState.label`/`labelBy` resolve — which is
     * trivially true and caught nothing: reintroducing E33 left it green. A
     * regression test that does not fail on the regression is worse than none,
     * because it converts an open question into false confidence.
     */
    private fun offeredPairs(g: Gbnf): List<Pair<String, String>> {
        val rule = g.source.lineSequence()
            .firstOrNull { it.startsWith("screen-target ::=") } ?: return emptyList()

        return rule.substringAfter("::=").split('|').mapNotNull { alt ->
            val literals = gbnfLiterals(alt.trim())
            if (literals.size != 9) return@mapNotNull null
            val by = unescape(literals[3])
            val value = unescape(literals[7])
            by to value
        }
    }

    /** Bodies of the double-quoted literals, honouring backslash escapes. */
    private fun gbnfLiterals(sequence: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inside = false
        var i = 0
        while (i < sequence.length) {
            val c = sequence[i]
            when {
                c == '\\' && i + 1 < sequence.length -> {
                    if (inside) cur.append(c).append(sequence[i + 1])
                    i++
                }
                c == '"' -> {
                    if (inside) { out += cur.toString(); cur.clear() }
                    inside = !inside
                }
                inside -> cur.append(c)
            }
            i++
        }
        return out
    }

    /** Reverse [ScreenGrammar]'s escaping: strip the JSON quotes, unescape. */
    private fun unescape(literal: String): String =
        literal.removePrefix("\\\"").removeSuffix("\\\"")
            .replace("\\\\\\\"", "\"")
            .replace("\\\\\\\\", "\\")

    @Test
    fun `every selector the grammar offers resolves against the screen`() {
        // THE PROPERTY, checked against the grammar's own output. E31 and E33
        // are both failures of this line; verified by reintroducing E33 and
        // watching it fail.
        var checked = 0
        val violations = mutableListOf<String>()

        for (label in nastyLabels) {
            for (node in nodesFor(label)) {
                val tree = UiTree("com.example", null, listOf(node), capturedAtMs = 0)
                val g = ScreenGrammar.forScreen(CompactState.from(tree))

                for ((by, value) in offeredPairs(g)) {
                    checked++
                    val kind = TargetBy.entries.firstOrNull { it.wire == by } ?: continue
                    if (tree.nodes.none { it.matches(Target(kind, value)) }) {
                        val actual = node.contentDescription ?: node.text
                        violations += "offered $by=\"$value\" for node label \"$actual\" — nothing matches it"
                    }
                }
            }
        }

        assertTrue(checked > 20, "the property must actually be exercised; checked=$checked")
        assertTrue(
            violations.isEmpty(),
            "the grammar can emit ${violations.size} target(s) the gate cannot resolve:\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `the grammar stays parseable for every label shape`() {
        // The other half of the seam: a label that breaks GBNF is not an error at
        // run time — llama.cpp declines to install the sampler and generation
        // proceeds unconstrained (D9). So a label the escaper mishandles removes
        // C3 silently, which is precisely how E33 hid.
        for (label in nastyLabels) {
            for (node in nodesFor(label)) {
                val state = CompactState.from(
                    UiTree("com.example", null, listOf(node), capturedAtMs = 0),
                )
                val g = ScreenGrammar.forScreen(state)
                val rule = g.source.lineSequence().firstOrNull { it.startsWith("screen-target ::=") }
                    ?: continue

                assertTrue('\n' !in rule, "a newline leaked into the alternation for \"$label\"")
                // Balanced literals: an unescaped quote from a label would
                // desynchronise every boundary after it.
                var inside = false
                var i = 0
                while (i < rule.length) {
                    when {
                        rule[i] == '\\' -> i++
                        rule[i] == '"' -> inside = !inside
                    }
                    i++
                }
                assertTrue(!inside, "unterminated GBNF literal for label \"$label\"")
            }
        }
    }
}
