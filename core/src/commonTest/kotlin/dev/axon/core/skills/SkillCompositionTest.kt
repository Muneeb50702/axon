package dev.axon.core.skills

import dev.axon.core.model.CompiledSkill
import dev.axon.core.model.CompiledStep
import dev.axon.core.model.Goal
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.SkillManifest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Composing known skills to serve a compound goal — **E24**.
 *
 * The decomposer splits aggressively and the composer refuses aggressively, and
 * the interesting tests are all about the refusals. A wrong split is expected
 * and harmless; what must never happen is a *partially executed* compound
 * request, because half of "send a message to Ali and Ahmed" is an irreversible
 * act the user did not ask for as a separate step.
 */
class SkillCompositionTest {

    private fun skill(id: String, pattern: String) = CompiledSkill(
        manifest = SkillManifest(id = id, name = id, goalPattern = pattern),
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

    private suspend fun storeOf(vararg skills: CompiledSkill) =
        InMemorySkillStore().also { s -> skills.forEach { s.save(it) } }

    // ------------------------------------------------------- decomposition --

    @Test
    fun `sequencing words split a compound goal`() {
        assertEquals(
            listOf("turn on wifi", "open whatsapp"),
            GoalDecomposer.split("turn on wifi then open whatsapp"),
        )
        assertEquals(
            listOf("turn on wifi", "open whatsapp"),
            GoalDecomposer.split("turn on wifi and open whatsapp"),
        )
        // §2.1: Roman-Urdu users are the target, not an afterthought.
        assertEquals(
            listOf("wifi on karo", "whatsapp kholo"),
            GoalDecomposer.split("wifi on karo phir whatsapp kholo"),
        )
    }

    @Test
    fun `a simple goal is returned whole`() {
        assertEquals(listOf("open whatsapp"), GoalDecomposer.split("open whatsapp"))
        assertTrue(!GoalDecomposer.isCompound("open whatsapp"))
    }

    @Test
    fun `a name containing a sequencer is not split`() {
        // "Andleeb" and "Anderson" both contain "and". Splitting inside a word
        // would produce fragments from a name.
        assertEquals(listOf("call andleeb"), GoalDecomposer.split("call andleeb"))
        assertEquals(listOf("open android settings"), GoalDecomposer.split("open android settings"))
    }

    // ---------------------------------------------------------- composition --

    @Test
    fun `a compound goal composes when every part is known`() = runTest {
        val store = storeOf(skill("wifi", "turn on wifi"), skill("wa", "open whatsapp"))
        val plan = SkillComposer(store).plan(Goal("turn on wifi then open whatsapp"))

        assertNotNull(plan)
        assertEquals(2, plan.steps.size)
        assertEquals(listOf("wifi", "wa"), plan.steps.map { it.match.skill.manifest.id })
    }

    @Test
    fun `one unknown part refuses the whole composition`() = runTest {
        // The rule that makes aggressive splitting safe. Half a compound task is
        // worse than none of it: the alternative — partial composition — would
        // run the known half and plan the rest, having already performed an act
        // the user asked for only as part of a larger request.
        val store = storeOf(skill("wifi", "turn on wifi"))
        val plan = SkillComposer(store).plan(Goal("turn on wifi then send a report to ammi"))

        assertNull(plan, "an unmatched fragment must abandon the composition entirely")
    }

    @Test
    fun `a mis-split conjunction composes nothing rather than half-sending`() = runTest {
        // "Send a message to Ali and Ahmed" is ONE task. The decomposer splits it
        // — deliberately, since recognising it as one would be semantics — and
        // produces a fragment reading "ahmed", which matches no skill. The
        // composition is abandoned before anything is dispatched.
        //
        // Under partial composition AXON would have messaged Ali and then
        // planned something for "ahmed". This test is the reason that design was
        // rejected.
        val store = storeOf(skill("send", "send a message to ali"))
        val plan = SkillComposer(store).plan(Goal("send a message to ali and ahmed"))

        assertNull(plan, "a wrong split must cost an opportunity, never an action")
    }

    @Test
    fun `sub-goals inherit the parent's params`() = runTest {
        // A compound request states its parameters once and either half may need
        // them: "message Ammi I'm late and call her".
        val store = storeOf(skill("wifi", "turn on wifi"), skill("wa", "open whatsapp"))
        val plan = SkillComposer(store).plan(
            Goal("turn on wifi then open whatsapp", params = mapOf("contact" to "Ammi")),
        )

        assertNotNull(plan)
        assertTrue(plan.steps.all { it.goal.params["contact"] == "Ammi" })
    }

    @Test
    fun `plan confidence is the weakest link, not the average`() = runTest {
        // A chain is only as trustworthy as its least certain step. Averaging
        // would let two confident matches carry a doubtful one, which then acts
        // on the device with the rest.
        val store = storeOf(skill("wifi", "turn on wifi"), skill("wa", "open whatsapp"))
        val plan = SkillComposer(store).plan(Goal("turn on wifi then open whatsapp"))

        assertNotNull(plan)
        assertEquals(plan.steps.minOf { it.match.confidence }, plan.confidence)
    }

    @Test
    fun `a non-compound goal yields no plan, so callers need no special case`() = runTest {
        val store = storeOf(skill("wa", "open whatsapp"))
        assertNull(SkillComposer(store).plan(Goal("open whatsapp")))
    }

    @Test
    fun `an empty store composes nothing`() = runTest {
        assertNull(SkillComposer(InMemorySkillStore()).plan(Goal("turn on wifi then open whatsapp")))
    }

    @Test
    fun `composition works with paraphrased verbs, since matching is shared`() = runTest {
        // E23 and E24 meet here: the parts go through GoalMatcher like any other
        // goal, so a skill learned as "open whatsapp" is reachable inside a
        // compound request phrased with a different verb.
        val store = storeOf(skill("wifi", "turn on wifi"), skill("wa", "open whatsapp"))
        val plan = SkillComposer(store).plan(Goal("turn on wifi then launch whatsapp"))

        assertNotNull(plan)
        assertEquals("wa", plan.steps[1].match.skill.manifest.id)
    }
}
