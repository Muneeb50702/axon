package dev.axon.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Proves the sealed [DeviceAction] hierarchy serialises to exactly the wire
 * format spec §10.1 defines.
 *
 * This is the test that lets AXON claim the tightening described in
 * [DeviceAction]'s docs is a *refinement* of §10.1 rather than a departure from
 * it. §9 says the schemas are normative; anything reading AXON's JSON — the
 * benchmark harness, a future client, an examiner — must see §10.1's shape.
 * Kotlin's richer types are an internal representation, and this test is what
 * keeps that true as the hierarchy grows.
 */
class DeviceActionWireFormatTest {

    private val json = AxonJson.strict

    private val expect = PostCondition(PostConditionType.NODE_PRESENT, "Sent")

    @Test
    fun `every action serialises with action as the discriminator key`() {
        val all: List<DeviceAction> = listOf(
            DeviceAction.Tap(Target(TargetBy.TEXT, "Send"), expect),
            DeviceAction.LongPress(Target(TargetBy.CONTENT_DESC, "Message"), expect),
            DeviceAction.InputText(Target(TargetBy.CLASS, "android.widget.EditText"), "hi", expect),
            DeviceAction.Swipe(Direction.UP, expect = expect),
            DeviceAction.Scroll(Direction.DOWN, expect = expect),
            DeviceAction.LaunchApp("com.whatsapp", expect),
            DeviceAction.PressKey(DeviceKey.BACK, expect),
            DeviceAction.Wait(expect),
        )

        for (action in all) {
            val obj = json.encodeToJsonElement(DeviceAction.serializer(), action) as JsonObject
            assertTrue(
                "action" in obj,
                "${action::class.simpleName} must carry an \"action\" key per §10.1",
            )
        }
    }

    @Test
    fun `discriminator values match the ACTION_TYPES list exactly`() {
        // Guards the single-source-of-truth claim in DeviceAction.ACTION_TYPES:
        // the GBNF generator and the §14.2 valid-action checker both read that
        // list, so a variant added without updating it must fail here.
        val emitted = listOf(
            DeviceAction.Tap(Target(TargetBy.TEXT, "x"), expect),
            DeviceAction.LongPress(Target(TargetBy.TEXT, "x"), expect),
            DeviceAction.Swipe(Direction.UP, expect = expect),
            DeviceAction.InputText(Target(TargetBy.TEXT, "x"), "y", expect),
            DeviceAction.Scroll(Direction.UP, expect = expect),
            DeviceAction.LaunchApp("com.x.y", expect),
            DeviceAction.PressKey(DeviceKey.HOME, expect),
            DeviceAction.Wait(expect),
        ).map { action ->
            val obj = json.encodeToJsonElement(DeviceAction.serializer(), action) as JsonObject
            obj["action"].toString().trim('"')
        }

        assertEquals(DeviceAction.ACTION_TYPES.toSet(), emitted.toSet())
    }

    @Test
    fun `spec section 10_1 sample json parses into the right variant`() {
        // Literally the §10.1 example shape, as a grammar-conforming emission.
        val raw = """
            {"action":"tap","target":{"by":"content_desc","value":"Send"},
             "expect":{"type":"text_matches","value":"sent"}}
        """.trimIndent()

        val action = json.decodeFromString(DeviceAction.serializer(), raw)

        assertTrue(action is DeviceAction.Tap)
        assertEquals(TargetBy.CONTENT_DESC, action.target.by)
        assertEquals("Send", action.target.value)
        assertEquals(PostConditionType.TEXT_MATCHES, action.expect.type)
    }

    @Test
    fun `enum wire names use spec snake_case not kotlin names`() {
        val action = DeviceAction.InputText(
            Target(TargetBy.CONTENT_DESC, "Message"),
            "on my way",
            PostCondition(PostConditionType.APP_FOREGROUND, "com.whatsapp"),
        )
        val encoded = json.encodeToString(DeviceAction.serializer(), action)

        assertTrue("\"input_text\"" in encoded, "expected snake_case action name in: $encoded")
        assertTrue("\"content_desc\"" in encoded, "expected snake_case target.by in: $encoded")
        assertTrue("\"app_foreground\"" in encoded, "expected snake_case expect.type in: $encoded")
        assertTrue("CONTENT_DESC" !in encoded, "Kotlin enum name leaked into the wire format")
    }

    @Test
    fun `round trip preserves every action variant`() {
        val all: List<DeviceAction> = listOf(
            DeviceAction.Tap(Target(TargetBy.ID, "com.whatsapp:id/send"), expect),
            DeviceAction.InputText(Target(TargetBy.CONTENT_DESC, "Message"), "on my way", expect),
            DeviceAction.Swipe(Direction.LEFT, Target(TargetBy.TEXT, "row"), expect),
            DeviceAction.LaunchApp("com.google.android.deskclock", expect),
            DeviceAction.PressKey(DeviceKey.ENTER, expect),
            DeviceAction.Wait(expect, timeoutMs = 5_000),
        )

        for (action in all) {
            val encoded = json.encodeToString(DeviceAction.serializer(), action)
            assertEquals(action, json.decodeFromString(DeviceAction.serializer(), encoded))
        }
    }

    @Test
    fun `strict parser rejects unknown keys`() {
        // AxonJson.strict sets ignoreUnknownKeys = false on purpose: the grammar
        // cannot emit an unknown key, so one appearing means the grammar was not
        // applied. Tolerating it would let an unconstrained run masquerade as a
        // constrained one and blur the §14.3 ablation arms.
        val smuggled = """
            {"action":"tap","target":{"by":"text","value":"Send"},
             "direction":"up","expect":{"type":"node_present","value":"Sent"}}
        """.trimIndent()

        assertFailsWith<Exception> {
            AxonJson.strict.decodeFromString(DeviceAction.serializer(), smuggled)
        }
    }

    @Test
    fun `persistence parser tolerates unknown keys so old traces still load`() {
        // The mirror of the previous test. Episodic memory must survive schema
        // growth, or every schema change discards the benchmark history.
        val fromOlderBuild = """
            {"action":"tap","target":{"by":"text","value":"Send"},
             "expect":{"type":"node_present","value":"Sent"},"retired_field":7}
        """.trimIndent()

        val action = AxonJson.persistence
            .decodeFromString(DeviceAction.serializer(), fromOlderBuild)
        assertTrue(action is DeviceAction.Tap)
    }
}

/** Convenience so the tests above can inspect structure, not just text. */
private fun Json.encodeToJsonElement(
    serializer: kotlinx.serialization.SerializationStrategy<DeviceAction>,
    value: DeviceAction,
) = parseToJsonElement(encodeToString(serializer, value))
