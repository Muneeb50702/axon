package dev.axon.core.inference

import dev.axon.core.model.AxonJson
import dev.axon.core.model.Bounds
import dev.axon.core.model.CompactState
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A target the grammar permits must be one the gate can resolve — **E31**.
 *
 * ## The bug, and why it was not cosmetic
 *
 * E18's screen grammar grounds `target.value` in the labels on screen and leaves
 * `target.by` free. `UiNode.label` is `contentDescription ?: text`, so an element
 * carrying only `text` is advertised under a name that
 * `matches(CONTENT_DESC)` will never accept.
 *
 * Measured on device (E24e), the planner proposed `tap content_desc="Search"`
 * against exactly such an element and the gate replied:
 *
 * > *no element matching content_desc="Search" exists on this screen;
 * > this screen has: Search*
 *
 * Both halves true. Cost: one planning step (~84 s on the target hardware) and a
 * self-contradictory failure reason fed back to the planner.
 *
 * C3's claim is that a target which cannot resolve is **unreachable at the
 * sampler**, not caught later. Grounding half the object delivered half the
 * claim, and the shortfall was invisible on any screen whose elements all carry
 * content-descriptions — which is most test fixtures and few real apps.
 *
 * These tests pin the whole `{by, value}` pair.
 */
class GroundedSelectorTest {

    private fun node(
        index: Int,
        text: String? = null,
        contentDescription: String? = null,
    ) = UiNode(
        index = index,
        role = "button",
        text = text,
        contentDescription = contentDescription,
        bounds = Bounds(0, index * 60, 200, index * 60 + 50),
        clickable = true,
    )

    private fun stateOf(vararg nodes: UiNode) =
        CompactState.from(UiTree("com.example", null, nodes.toList(), capturedAtMs = 0))

    // ------------------------------------------------------- provenance ------

    @Test
    fun `label provenance follows UiNode label exactly`() {
        // These two must agree or the grammar grounds targets on a selector that
        // does not resolve — which is the bug, restored.
        val described = CompactState.from(
            UiTree("com.example", null, listOf(node(0, text = "T", contentDescription = "CD")), 0),
        ).elements.single()
        assertEquals("CD", described.label)
        assertEquals(TargetBy.CONTENT_DESC, described.labelBy)

        val textOnly = stateOf(node(0, text = "Search")).elements.single()
        assertEquals("Search", textOnly.label)
        assertEquals(TargetBy.TEXT, textOnly.labelBy, "a text-only element must be selected by text")
    }

    // ---------------------------------------------------------- grammar ------

    /**
     * The `screen-target` production alone.
     *
     * Asserting against the whole grammar text would be wrong: the base grammar
     * still *defines* `by ::= "text" | "content_desc" | …`, and after E31 nothing
     * references it, so those strings are present with no token path to them.
     * The claim under test is about what is **reachable**, and reachability here
     * means "appears in the alternation `target` now expands to".
     */
    private fun screenTargets(g: Gbnf): String =
        g.source.substringAfter("screen-target ::=", missingDelimiterValue = "")

    @Test
    fun `the grammar pairs a text-only label with by=text`() {
        // THE REGRESSION, at the sampler. Before E31 this grammar admitted
        // {"by":"content_desc","value":"Search"} — well-formed, grounded, and
        // unresolvable.
        val targets = screenTargets(ScreenGrammar.forScreen(stateOf(node(0, text = "Search"))))

        assertTrue(
            "\\\"text\\\"" in targets && "Search" in targets,
            "the text-only element must be reachable as by=text; got:\n$targets",
        )
        assertTrue(
            "content_desc" !in targets,
            "no content_desc target may be reachable — nothing on this screen has one",
        )
    }

    @Test
    fun `the grammar pairs a described label with by=content_desc`() {
        val targets = screenTargets(
            ScreenGrammar.forScreen(stateOf(node(0, text = "raw", contentDescription = "Send"))),
        )

        assertTrue("content_desc" in targets && "Send" in targets)
        // "raw" is not the label, so it is not nameable — the planner was never
        // shown it, and a grammar offering it would let the model name something
        // absent from its own view of the screen.
        assertTrue("raw" !in targets, "only the label the planner was shown may be nameable")
    }

    @Test
    fun `a mixed screen grounds each element by its own attribute`() {
        val targets = screenTargets(
            ScreenGrammar.forScreen(
                stateOf(
                    node(0, contentDescription = "Send"),
                    node(1, text = "Search"),
                ),
            ),
        )

        assertTrue("content_desc" in targets, "the described element")
        assertTrue("\\\"text\\\"" in targets, "the text-only element")
        assertTrue("Send" in targets && "Search" in targets)
    }

    @Test
    fun `every target the grammar names resolves against the screen`() {
        // The property the whole change exists for, asserted directly rather
        // than through the grammar's text: for each element, the selector the
        // grammar would emit must actually match it.
        val tree = UiTree(
            "com.example", null,
            listOf(
                node(0, contentDescription = "Send"),
                node(1, text = "Search"),
                node(2, text = "Attach", contentDescription = "Attach a file"),
            ),
            capturedAtMs = 0,
        )
        val state = CompactState.from(tree)

        for (element in state.elements) {
            val by = element.labelBy
            val value = element.label
            if (by == null || value == null) continue

            assertTrue(
                tree.nodes.any { it.matches(Target(by, value)) },
                "the grammar would offer ${by.wire}=\"$value\", which nothing on screen matches",
            )
        }
    }

    @Test
    fun `an unlabelled screen still falls back to the base grammar`() {
        // §17's empty-tree escape. Device-directed actions must stay reachable
        // or the agent has no legal move at all.
        val g = ScreenGrammar.forScreen(stateOf(node(0)))
        assertEquals(ActionGrammar.GBNF.source, g.source)
    }

    @Test
    fun `a label with surrounding whitespace is offered exactly as the gate will match it`() {
        // **E33's root cause.** ScreenGrammar trimmed the label before putting it
        // in the alternation; `UiNode.matches` compares the raw attribute. So on
        // a screen whose label is "Power " the grammar offered "Power", the model
        // emitted "Power" -- correctly, it was the only thing on offer -- and the
        // gate refused it with
        //
        //   no element matching text="Power" ... this screen has: Power
        //
        // the same self-contradiction E31 fixed for `by`, reaching it through
        // whitespace instead. Real OEM skins ship these: this device's launcher
        // carries a label 'PiKaChUu :) ' with a trailing space.
        //
        // Measured on device before the fix: three GrammarViolated reports in one
        // run, each `by=text value=Power` against 24 permitted pairs.
        val padded = UiNode(
            index = 0, role = "button",
            text = "Power ", contentDescription = null,
            bounds = Bounds(0, 0, 100, 40), clickable = true,
        )
        val tree = UiTree("com.android.settings", null, listOf(padded), capturedAtMs = 0)
        val targets = screenTargets(ScreenGrammar.forScreen(CompactState.from(tree)))

        // The grammar must offer the raw label, trailing space and all, because
        // that is the string the gate will compare against.
        assertTrue(
            "Power " in targets,
            "the grammar offered a trimmed label the gate cannot match; got:\n$targets",
        )
    }

    // ------------------------------------------------------------- wire ------

    @Test
    fun `TargetBy wire names match what the serialiser emits`() {
        // The grammar writes `by` values into GBNF literals as raw text. If they
        // ever disagreed with @SerialName, the sampler would be constrained to
        // emit a token the parser then rejects — the exact failure C3 exists to
        // make impossible, arriving from the one direction nothing else checks.
        for (by in TargetBy.entries) {
            val json = AxonJson.strict.encodeToString(
                DeviceAction.serializer(),
                DeviceAction.Tap(
                    Target(by, "x"),
                    dev.axon.core.model.PostCondition(
                        dev.axon.core.model.PostConditionType.NODE_PRESENT, "y",
                    ),
                ),
            )
            assertTrue(
                "\"${by.wire}\"" in json,
                "TargetBy.$by.wire is '${by.wire}' but the serialiser wrote: $json",
            )
        }
    }
}
