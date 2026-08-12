package dev.axon.core.skills

import dev.axon.core.model.Capability
import dev.axon.core.model.CompiledSkill
import dev.axon.core.model.CompiledStep
import dev.axon.core.model.Goal
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.SkillManifest
import dev.axon.core.model.SkillParameter
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Goal→skill matching, and the O5 folder-install path.
 *
 * The asymmetry this file is built around: **a false match is much worse than a
 * miss.** A miss costs a slower cold run; a false positive replays the wrong
 * skill against a live device, potentially messaging the wrong person before
 * anything can intervene. Several tests below exist specifically to check that
 * borderline cases fall through to the planner.
 */
class SkillStoreTest {

    private fun skill(
        id: String = "whatsapp_send",
        pattern: String = "send {message} to {contact} on whatsapp",
        params: List<String> = listOf("message", "contact"),
        capabilities: List<String> = listOf("ui:observe", "ui:gesture"),
    ) = CompiledSkill(
        manifest = SkillManifest(
            id = id,
            name = id,
            goalPattern = pattern,
            parameters = params.map { SkillParameter(it, "string", required = true) },
            requiredCapabilities = capabilities,
        ),
        steps = listOf(
            CompiledStep(
                step = 1,
                selector = Target(TargetBy.CONTENT_DESC, "Send"),
                action = "tap",
                expect = PostCondition(PostConditionType.NODE_PRESENT, "Sent"),
            ),
        ),
    )

    private fun store(vararg skills: CompiledSkill) = InMemorySkillStore(
        granted = setOf(Capability.UI_OBSERVE, Capability.UI_GESTURE),
    ).also { s ->
        // `save` bypasses capability checks, which is what the compiler does
        // after a successful run.
        kotlinx.coroutines.runBlocking { skills.forEach { s.save(it) } }
    }

    // -----------------------------------------------------------------

    @Test
    fun `a repeated request matches and its slots are extracted`() = runTest {
        // The C1′ case: the same task, phrased the same way, a second time.
        val match = store(skill()).match(Goal("send on my way to ammi on whatsapp"))

        assertNotNull(match)
        assertEquals("whatsapp_send", match.skill.manifest.id)
        assertEquals("on my way", match.params["message"])
        assertEquals("ammi", match.params["contact"])
    }

    @Test
    fun `different parameter values still match the same skill`() = runTest {
        // Without this, a compiled skill would be a macro — usable only for the
        // exact request it was recorded from.
        val match = store(skill()).match(Goal("send running late to baba on whatsapp"))

        assertNotNull(match)
        assertEquals("running late", match.params["message"])
        assertEquals("baba", match.params["contact"])
    }

    @Test
    fun `an unrelated request does not match`() = runTest {
        assertNull(store(skill()).match(Goal("set an alarm for 6:30 am")))
    }

    @Test
    fun `a request missing a pattern anchor does not match`() = runTest {
        // "on whatsapp" is a literal anchor. Without it the utterance could be a
        // different app entirely, and replaying the WhatsApp skill would send the
        // message somewhere the user did not ask for.
        assertNull(store(skill()).match(Goal("send on my way to ammi")))
    }

    @Test
    fun `a mostly-slot pattern is refused as too weak to trust`() = runTest {
        // "{a} {b}" would otherwise "match" every two-word request. Confidence is
        // the share of the utterance covered by *fixed* words, so an
        // over-parameterised pattern scores low and falls through to the planner.
        val vague = skill(id = "vague", pattern = "{a} {b}", params = listOf("a", "b"))
        assertNull(store(vague).match(Goal("delete everything")))
    }

    @Test
    fun `the more specific skill wins when two could match`() = runTest {
        val general = skill(id = "general", pattern = "send {message} to {contact} on whatsapp")
        val specific = skill(
            id = "specific",
            pattern = "send {message} to ammi on whatsapp",
            params = listOf("message"),
        )

        val match = store(general, specific).match(Goal("send on my way to ammi on whatsapp"))

        assertNotNull(match)
        // More literal text covered → higher confidence → chosen.
        assertEquals("specific", match.skill.manifest.id)
    }

    @Test
    fun `an empty slot value does not match`() = runTest {
        // "send  to ammi on whatsapp" has no message. Matching would replay with
        // an empty message, which is a message the user did not write.
        assertNull(store(skill()).match(Goal("send  to ammi on whatsapp")))
    }

    // -----------------------------------------------------------------
    // O5 — a new capability is a folder, not a code change
    // -----------------------------------------------------------------

    @Test
    fun `a valid folder installs`() = runTest {
        val s = InMemorySkillStore(granted = setOf(Capability.UI_OBSERVE, Capability.UI_GESTURE))
        val result = s.install(
            SkillFolder(
                id = "whatsapp_send",
                manifest = skill().manifest,
                compiledSteps = listOf(skill()),
            ),
        )

        assertIs<InstallResult.Installed>(result)
        assertEquals(1, s.all().size)
    }

    @Test
    fun `an invalid manifest is rejected and nothing is installed`() = runTest {
        val s = InMemorySkillStore()
        val broken = SkillManifest(
            id = "Bad Id",
            name = "x",
            goalPattern = "do {thing}",
            parameters = emptyList(),
        )

        val result = s.install(SkillFolder("bad", broken))

        val rejected = assertIs<InstallResult.Rejected>(result)
        assertTrue(rejected.problems.isNotEmpty())
        assertEquals(0, s.all().size, "a rejected skill must not be partially installed")
    }

    @Test
    fun `a skill missing capabilities still installs but is flagged`() = runTest {
        // §7.10: failing the install would hide the skill from the very screen
        // where the user would grant what it asked for.
        val s = InMemorySkillStore(granted = setOf(Capability.UI_OBSERVE))
        val result = s.install(
            SkillFolder(
                id = "whatsapp_send",
                manifest = skill(capabilities = listOf("ui:observe", "ui:gesture", "whatsapp:send")).manifest,
                compiledSteps = listOf(skill()),
            ),
        )

        val needs = assertIs<InstallResult.NeedsCapabilities>(result)
        assertTrue("ui:gesture" in needs.missing)
        assertTrue("whatsapp:send" in needs.missing)
        assertEquals(1, s.all().size, "the skill should be visible in the permissions screen")
    }

    @Test
    fun `replay outcomes are recorded so clean-replay rate stays honest`() = runTest {
        val s = store(skill())

        s.recordReplay("whatsapp_send", neededRepair = false)
        s.recordReplay("whatsapp_send", neededRepair = false)
        s.recordReplay("whatsapp_send", neededRepair = true)

        val updated = s.all().first()
        assertEquals(3, updated.replayCount)
        assertEquals(1, updated.repairCount)
        // Two of three replays needed no model at all — the UI-drift rate the
        // §14.2 evaluation reports.
        assertEquals(2.0 / 3.0, updated.cleanReplayRate, 0.001)
    }
}
