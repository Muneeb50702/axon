package dev.axon.core.runtime

import dev.axon.core.model.CompiledSkill
import dev.axon.core.model.CompiledStep
import dev.axon.core.model.Goal
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.SkillManifest
import dev.axon.core.skills.InMemorySkillStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compiling asks the same question replaying asks — **E35**.
 *
 * ## The bug
 *
 * `AxonRuntime.compile` decided *"is this goal a further example of a skill I
 * already have?"* with a local helper that compared only the **first word** of
 * the stored pattern:
 *
 * ```kotlin
 * utterance.lowercase().contains(literal.split(" ").first())
 * ```
 *
 * So `"open whatsapp"` reduced to `"open"`, and every goal containing that word
 * was treated as the same skill. Measured on device: `"open the camera"`
 * succeeded cleanly **three times and never compiled**, because each success was
 * routed into `refine(open_whatsapp, cameraTrace)` instead of compiling a new
 * skill. The store held two skills after dozens of successful runs and there was
 * no error anywhere.
 *
 * Worse in a way that matters for the paper: the camera traces were absorbed
 * into `open_whatsapp.sourceTraces`, a field the model documents as *"provenance
 * for the evaluation"*. The skill cited traces of a different task as its own
 * evidence.
 *
 * Nothing broke behaviourally — `refine` kept the WhatsApp step and replay kept
 * launching WhatsApp — which is exactly why it survived. The only symptom was a
 * store that quietly refused to grow, and "the agent has not learned much yet"
 * is indistinguishable from "the agent cannot learn".
 *
 * ## The rule
 *
 * There is one definition of "does this goal correspond to this skill", and it
 * is `GoalMatcher`. A second, looser one in the compiler is not an optimisation;
 * it is a second answer to a question that must have one.
 */
class CompileMatchingTest {

    private fun launchSkill(id: String, pattern: String, pkg: String) = CompiledSkill(
        manifest = SkillManifest(
            id = id, name = id, goalPattern = pattern, deviceFamily = "fake/test",
        ),
        steps = listOf(
            CompiledStep(
                step = 1,
                selector = null,
                action = "launch_app",
                expect = PostCondition(PostConditionType.APP_FOREGROUND, pkg),
                args = mapOf("app" to pkg),
            ),
        ),
        sourceTraces = listOf("t-$id"),
    )

    @Test
    fun `a different app sharing a verb is not the same skill`() = runTest {
        // THE REGRESSION, stated at the level the bug lived at: the store's own
        // matcher must not confuse these, or the compiler will refine one skill
        // with another task's evidence.
        val store = InMemorySkillStore().apply {
            save(launchSkill("open_whatsapp", "open whatsapp", "com.whatsapp"))
        }

        assertNull(
            store.match(Goal("open the camera")),
            "\"open the camera\" must not match the WhatsApp skill — sharing the " +
                "word \"open\" is not sharing a task",
        )
        assertNull(store.match(Goal("open settings")))

        // The genuine article still matches, or the fix would have traded a
        // false positive for a false negative and stopped replay working.
        assertTrue(store.match(Goal("open whatsapp")) != null)
    }

    @Test
    fun `two launch skills can coexist`() = runTest {
        // What the bug prevented: a second launch skill existing at all. Each
        // goal must resolve to its own skill, not to whichever was stored first.
        val store = InMemorySkillStore().apply {
            save(launchSkill("open_whatsapp", "open whatsapp", "com.whatsapp"))
            save(launchSkill("open_camera", "open the camera", "com.transsion.camera"))
        }

        assertEquals("open_whatsapp", store.match(Goal("open whatsapp"))?.skill?.manifest?.id)
        assertEquals("open_camera", store.match(Goal("open the camera"))?.skill?.manifest?.id)
    }
}
