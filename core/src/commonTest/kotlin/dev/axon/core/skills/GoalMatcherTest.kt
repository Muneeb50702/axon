package dev.axon.core.skills

import dev.axon.core.model.CompiledSkill
import dev.axon.core.model.CompiledStep
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.SkillManifest
import dev.axon.core.model.SkillParameter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Goal → skill matching, and the one paraphrase class it handles (E23).
 *
 * Every test here is about the predicate that chooses PLAN versus REPLAY. Two
 * failure directions, wildly asymmetric:
 *
 * - **A miss** costs a cold plan — ~66 s on the target device. Slow, correct.
 * - **A false match** replays the wrong skill against a live device, possibly
 *   messaging the wrong person, before anything can intervene.
 *
 * So the tests come in pairs: each one that widens matching is followed by one
 * that checks the widening did not reach something it should not.
 */
class GoalMatcherTest {

    private fun skill(
        id: String,
        pattern: String,
        params: List<String> = emptyList(),
    ) = CompiledSkill(
        manifest = SkillManifest(
            id = id,
            name = id,
            goalPattern = pattern,
            parameters = params.map { SkillParameter(it, "string", required = true) },
        ),
        steps = listOf(
            CompiledStep(
                step = 1,
                selector = null,
                action = "launch_app",
                expect = PostCondition(PostConditionType.APP_FOREGROUND, "com.whatsapp"),
                args = mapOf("app" to "com.whatsapp"),
            ),
        ),
    )

    private val openWhatsapp = skill("open_whatsapp", "open whatsapp")

    // ------------------------------------------------------- E23: paraphrase --

    @Test
    fun `every launch verb reaches a skill learned under one of them`() {
        // The measured gap: a user taught AXON with "open whatsapp", said
        // "launch whatsapp" the next day, and paid a full cold plan for a task
        // the system had already learned. Nothing explained why it was slow.
        val phrasings = listOf(
            "open whatsapp",
            "launch whatsapp",
            "start whatsapp",
            "go to whatsapp",
            "switch to whatsapp",
            "show me whatsapp",
            // §2.1 names South Asian users; an English-only matcher fails them.
            "kholo whatsapp",
            "khol whatsapp",
            // Casing and trailing punctuation are the user's business, not the
            // matcher's.
            "Launch WhatsApp",
            "open whatsapp.",
        )

        for (utterance in phrasings) {
            val match = GoalMatcher.match(utterance, listOf(openWhatsapp))
            assertNotNull(match, "'$utterance' should reach the learned skill")
            assertEquals("open_whatsapp", match.skill.manifest.id, utterance)
        }
    }

    @Test
    fun `a skill compiled under one verb is reachable by another`() {
        // Both sides are canonicalised, not just the incoming request — so a
        // skill learned from "launch whatsapp" is reachable by "open whatsapp".
        // Normalising only the utterance would make this direction fail, and
        // would also mean every skill compiled before E23 needed recompiling.
        val learnedAsLaunch = skill("launch_whatsapp", "launch whatsapp")
        val match = GoalMatcher.match("open whatsapp", listOf(learnedAsLaunch))
        assertNotNull(match)
        assertEquals("launch_whatsapp", match.skill.manifest.id)
    }

    @Test
    fun `canonicalisation does not reach a different app`() {
        // The widening must not become "any launch goal matches any launch
        // skill". That would replay WhatsApp when the user asked for the camera.
        assertNull(GoalMatcher.match("open camera", listOf(openWhatsapp)))
        assertNull(GoalMatcher.match("launch settings", listOf(openWhatsapp)))
    }

    @Test
    fun `a multi-step goal does not match a bare launch skill`() {
        // "open whatsapp and message ammi" is not a launch request — replaying a
        // one-step launch skill would open WhatsApp, report success, and never
        // send the message. AppIntent's continuation check is what prevents it,
        // reused here rather than restated.
        assertNull(GoalMatcher.match("open whatsapp and message ammi", listOf(openWhatsapp)))
        assertNull(GoalMatcher.match("open whatsapp then call baba", listOf(openWhatsapp)))
    }

    // ------------------------------------------------ unchanged behaviour ----

    @Test
    fun `slot extraction still works and is unaffected by canonicalisation`() {
        val send = skill(
            "whatsapp_send",
            "send {message} to {contact} on whatsapp",
            listOf("message", "contact"),
        )
        val match = GoalMatcher.match("send on my way to ammi on whatsapp", listOf(send))
        assertNotNull(match)
        assertEquals(mapOf("message" to "on my way", "contact" to "ammi"), match.params)
    }

    @Test
    fun `genuine paraphrase still misses, and that is the documented limit`() {
        // "text ammi that I'm coming" against "send {message} to {contact} on
        // whatsapp" needs the embedding index §7.8 specifies. E23 handles launch
        // verbs exactly; it does not pretend to handle this, and a matcher that
        // guessed here would be the false-match case that acts on a live device.
        val send = skill(
            "whatsapp_send",
            "send {message} to {contact} on whatsapp",
            listOf("message", "contact"),
        )
        assertNull(GoalMatcher.match("text ammi that i'm coming", listOf(send)))
    }

    @Test
    fun `a mostly-slots pattern is refused`() {
        // "{a} {b}" would otherwise match every two-word request. Confidence is
        // the share of the utterance covered by *fixed* words, so a degenerate
        // pattern scores near zero and falls through to the planner.
        val greedy = skill("greedy", "{a} {b}", listOf("a", "b"))
        assertNull(GoalMatcher.match("delete everything", listOf(greedy)))
    }

    @Test
    fun `the best-scoring skill wins when several could match`() {
        val specific = skill("specific", "open whatsapp")
        val greedy = skill("greedy", "open {app}", listOf("app"))
        val match = GoalMatcher.match("open whatsapp", listOf(greedy, specific))
        assertNotNull(match)
        assertEquals(
            "specific", match.skill.manifest.id,
            "a fully literal pattern covers more of the utterance than a slotted one",
        )
    }
}
