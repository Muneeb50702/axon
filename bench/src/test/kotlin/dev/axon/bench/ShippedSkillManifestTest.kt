package dev.axon.bench

import dev.axon.core.model.AxonJson
import dev.axon.core.model.Capability
import dev.axon.core.model.SkillManifest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Validates every skill folder actually shipped in `skills/` (spec §6.2, §7.7).
 *
 * O5 requires that a new capability is added *as a folder, with no core code
 * change*. The failure mode of that design is that nothing type-checks a
 * manifest — a typo in a capability string or a slot name that does not appear
 * in the goal pattern produces a skill that installs cleanly and then never
 * matches, or matches and cannot run. On a phone that surfaces as "the agent
 * just ignores me", which is close to undiagnosable.
 *
 * Running the real [SkillManifest.validate] against the real files in CI is what
 * makes folder-based extensibility safe to claim. It costs nothing and it is the
 * only check these files will ever get.
 */
class ShippedSkillManifestTest {

    private val skillsDir: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, "skills") }
        .firstOrNull { it.isDirectory }
        ?: fail("could not locate the skills/ directory from ${File(".").absolutePath}")

    private fun manifests(): List<Pair<File, SkillManifest>> =
        skillsDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .map { dir ->
                val file = File(dir, "manifest.json")
                assertTrue(file.isFile, "skill folder ${dir.name} has no manifest.json")
                file to AxonJson.persistence.decodeFromString(
                    SkillManifest.serializer(),
                    file.readText(),
                )
            }

    @Test
    fun `the five reference skills from spec 6_2 are present`() {
        // §6.2 in-scope deliverable: "5 reference skills (WhatsApp send, phone
        // call, alarm, maps navigate, settings toggle)".
        val expected = setOf(
            "whatsapp_send", "phone_call", "set_alarm", "maps_navigate", "settings_toggle",
        )
        val actual = manifests().map { it.second.id }.toSet()
        assertEquals(expected, actual, "shipped skills do not match the §6.2 deliverable list")
    }

    @Test
    fun `every shipped manifest validates`() {
        for ((file, manifest) in manifests()) {
            val problems = manifest.validate()
            assertTrue(problems.isEmpty(), "${file.path}:\n  " + problems.joinToString("\n  "))
        }
    }

    @Test
    fun `folder name matches manifest id`() {
        // The store keys skills by id but the installer walks directories. If
        // these disagree, a skill installs under one name and is looked up under
        // another.
        for ((file, manifest) in manifests()) {
            assertEquals(
                file.parentFile.name,
                manifest.id,
                "folder ${file.parentFile.name} declares id '${manifest.id}'",
            )
        }
    }

    @Test
    fun `every declared capability is well formed`() {
        for ((file, manifest) in manifests()) {
            for (capability in manifest.capabilities()) {
                assertTrue(
                    capability.isWellFormed(),
                    "${file.path} declares malformed capability '$capability' " +
                        "(expected lowercase domain:verb)",
                )
            }
        }
    }

    @Test
    fun `skills that act on the world declare a capability needing confirmation`() {
        // §16: "Destructive/irreversible actions require explicit confirmation
        // (sending money, deleting data, posting publicly)." Sending a message
        // and placing a call are both irreversible and outward-facing, so the
        // manifest must declare a capability the sandbox will stop on. A skill
        // that could send without one would slip past the confirmation gate
        // entirely, and no runtime check would catch it — the gate keys off the
        // declaration.
        val outwardFacing = setOf("whatsapp_send", "phone_call")

        for ((file, manifest) in manifests()) {
            if (manifest.id !in outwardFacing) continue
            val needsConfirmation = manifest.capabilities().any { it.verb in Capability.ALWAYS_CONFIRM }
            assertTrue(
                needsConfirmation,
                "${file.path} performs an irreversible outward action but declares no " +
                    "capability in Capability.ALWAYS_CONFIRM, so §16 confirmation would not fire",
            )
        }
    }

    @Test
    fun `every skill can observe and act`() {
        // A skill that drives the UI but forgets ui:gesture installs fine and
        // then fails on its first action, after the user has granted permissions
        // and started a task.
        for ((file, manifest) in manifests()) {
            val capabilities = manifest.capabilities()
            assertTrue(Capability.UI_OBSERVE in capabilities, "${file.path} cannot observe")
            assertTrue(Capability.UI_GESTURE in capabilities, "${file.path} cannot act")
        }
    }
}
