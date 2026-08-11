package dev.axon.core.inference

import dev.axon.core.model.DeviceAction
import dev.axon.core.model.DeviceKey
import dev.axon.core.model.Direction
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.TargetBy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drift detection between the hand-written GBNF grammar and the Kotlin model.
 *
 * The grammar is hand-written because it is a research artefact that has to read
 * clearly in the thesis appendix. The cost of that choice is that it can silently
 * fall out of step with the enums — someone adds an action variant, forgets the
 * grammar, and the model can never emit it. Nothing fails at build time, and the
 * symptom at run time is "the model just never does that", which is
 * indistinguishable from the model being bad at the task. These tests turn that
 * into a CI failure.
 *
 * They check **correspondence**, not conformance. Proving a given string
 * satisfies the grammar needs llama.cpp's own grammar engine, which arrives in
 * Phase 1 — `gbnf-validator` then runs over the §14 corpus as part of the C3
 * measurement. What is testable today is that the grammar and the model describe
 * the same action space, and that is the part that rots.
 *
 * Wire names are read from the serializer descriptors rather than hand-listed,
 * so the tests compare against what actually goes on the wire instead of a
 * second copy of the mapping that could itself drift.
 */
class ActionGrammarTest {

    private val source = ActionGrammar.SOURCE
    private val productions = source.parseProductions()

    @Test
    fun `every action type has its own production`() {
        for (type in DeviceAction.ACTION_TYPES) {
            assertTrue(
                source.declaresLiteral(type),
                "action type '$type' is in ACTION_TYPES but absent from the grammar",
            )
        }
    }

    @Test
    fun `root alternates over exactly the declared action types`() {
        val root = productions.getValue("root")
        val alternatives = root.substringAfter("::=").split('|').map { it.trim() }

        assertEquals(
            DeviceAction.ACTION_TYPES.size,
            alternatives.size,
            "root has ${alternatives.size} alternatives but ${DeviceAction.ACTION_TYPES.size} " +
                "action types exist — grammar and model have drifted. root was: $root",
        )
    }

    @Test
    fun `every TargetBy value is reachable in the grammar`() {
        for (wire in serializer<TargetBy>().descriptor.wireNames()) {
            assertTrue(source.declaresLiteral(wire), "TargetBy '$wire' missing from grammar")
        }
    }

    @Test
    fun `every PostConditionType value is reachable in the grammar`() {
        for (wire in serializer<PostConditionType>().descriptor.wireNames()) {
            assertTrue(source.declaresLiteral(wire), "PostConditionType '$wire' missing from grammar")
        }
    }

    @Test
    fun `every Direction and DeviceKey value is reachable in the grammar`() {
        for (wire in serializer<Direction>().descriptor.wireNames()) {
            assertTrue(source.declaresLiteral(wire), "Direction '$wire' missing from grammar")
        }
        for (wire in serializer<DeviceKey>().descriptor.wireNames()) {
            assertTrue(source.declaresLiteral(wire), "DeviceKey '$wire' missing from grammar")
        }
    }

    @Test
    fun `grammar declares no literal the model cannot represent`() {
        // The reverse direction, and the one that matters more. A stale literal
        // left behind after a rename is a token path the sampler can walk into
        // and the parser will then reject — precisely the C3 failure mode,
        // caused by the grammar itself rather than by the model.
        val known = buildSet {
            addAll(DeviceAction.ACTION_TYPES)
            addAll(serializer<TargetBy>().descriptor.wireNames())
            addAll(serializer<PostConditionType>().descriptor.wireNames())
            addAll(serializer<Direction>().descriptor.wireNames())
            addAll(serializer<DeviceKey>().descriptor.wireNames())
            // Field names — not enum values, but legitimate grammar literals.
            addAll(
                listOf(
                    "action", "target", "expect", "text", "app",
                    "key", "direction", "by", "type", "value",
                ),
            )
        }

        val unknown = ActionGrammar.literals() - known
        assertTrue(unknown.isEmpty(), "grammar declares literals with no model counterpart: $unknown")
    }

    @Test
    fun `field-action coherence is structural, not optional`() {
        // The core C3 refinement over §10.6's reference grammar. "direction" must
        // be reachable only inside swipe/scroll, never through a shared
        // optional-field rule that any action could walk into.
        val tap = productions.getValue("tap")
        assertTrue("direction" !in tap, "tap can reach a direction field: $tap")
        assertTrue("text-f" !in tap, "tap can reach a text field: $tap")

        val launch = productions.getValue("launch-app")
        assertTrue("target-f" !in launch, "launch_app can reach a target: $launch")

        val press = productions.getValue("press-key")
        assertTrue("target-f" !in press, "press_key can reach a target: $press")

        assertTrue("text-f" in productions.getValue("input-text"), "input_text must carry text")
        assertTrue("direction-f" in productions.getValue("swipe"), "swipe must carry direction")
        assertTrue("direction-f" in productions.getValue("scroll"), "scroll must carry direction")
    }

    @Test
    fun `launch_app is constrained to android package syntax`() {
        // The clearest single demonstration of what constrained decoding buys:
        // the sampler cannot emit {"action":"launch_app","app":"WhatsApp"} because
        // no token path reaches an uppercase letter in that position.
        assertTrue("package-name" in productions.getValue("app-f"), "app must use package-name")
        val seg = productions.getValue("pkg-seg")
        assertTrue("a-z" in seg, "package segments must allow lowercase: $seg")
        assertTrue("A-Z" !in seg, "package segments must not allow uppercase: $seg")
    }

    @Test
    fun `every action production requires an expect field`() {
        // C2 depends on this. An action with no stated expected outcome cannot be
        // verified deterministically, so the grammar must not permit one.
        val actionRules = listOf(
            "tap", "long-press", "input-text", "swipe",
            "scroll", "launch-app", "press-key", "wait",
        )
        for (rule in actionRules) {
            assertTrue(
                "expect-f" in productions.getValue(rule),
                "production '$rule' does not require an expect field",
            )
        }
    }

    @Test
    fun `grammar excludes control characters from strings`() {
        val charRule = productions.getValue("char")
        assertTrue(
            "\\x00-\\x1F" in charRule,
            "strings must exclude control characters or the model can emit invalid JSON: $charRule",
        )
    }

    @Test
    fun `grammar wraps into the Gbnf value type`() {
        assertTrue(ActionGrammar.GBNF.source.isNotBlank())
        assertEquals(ActionGrammar.SOURCE, ActionGrammar.GBNF.source)
    }
}

/** `@SerialName` values of an enum, in ordinal order — i.e. the wire names. */
private fun SerialDescriptor.wireNames(): List<String> =
    (0 until elementsCount).map { getElementName(it) }

/** Does the grammar contain `\"literal\"` as a quoted terminal? */
private fun String.declaresLiteral(literal: String): Boolean =
    "\\\"$literal\\\"" in this

/**
 * Split the grammar into `rule name → full production text`, joining the
 * continuation lines that make the source readable.
 */
private fun String.parseProductions(): Map<String, String> {
    val header = Regex("^\\s*([a-z][a-z0-9-]*)\\s*::=")
    val result = LinkedHashMap<String, StringBuilder>()
    var current: StringBuilder? = null

    for (line in lineSequence()) {
        val name = header.find(line)?.groupValues?.get(1)
        if (name != null) {
            current = StringBuilder(line.trim())
            result[name] = current
        } else if (line.isNotBlank() && !line.trimStart().startsWith("#")) {
            current?.append(' ')?.append(line.trim())
        } else {
            // A blank line or comment ends the previous production, so a later
            // rule's text can never be attributed to an earlier one.
            current = null
        }
    }
    return result.mapValues { it.value.toString() }
}
