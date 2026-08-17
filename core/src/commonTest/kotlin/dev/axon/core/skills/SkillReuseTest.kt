package dev.axon.core.skills

import dev.axon.core.model.CompiledSkill
import dev.axon.core.model.CompiledStep
import dev.axon.core.model.Goal
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.SkillManifest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Can any reuse path serve this without the model?" — **E24d**.
 *
 * The regression this exists to prevent has now happened twice in the same
 * place. The app skips an 806 MB model load when a compiled skill can serve the
 * goal (E22d). When composition was added, a *compound* goal — which never
 * matches a single skill, by construction — still answered "no" to that
 * question, so the model was loaded and then never consulted. Measured on the
 * device: 8.35 s end to end, **4.36 s of it the load**, for a run that cost zero
 * model calls.
 *
 * The predicate moved into `:core` specifically so this file could exist. It
 * previously lived in `:android:app`, which has no test suite, which is why both
 * regressions were found by reading a timeline from a real run rather than by a
 * failing test.
 */
class SkillReuseTest {

    private fun skill(id: String, pattern: String, pkg: String) = CompiledSkill(
        manifest = SkillManifest(
            id = id,
            name = id,
            goalPattern = pattern,
            deviceFamily = "fake/test",
        ),
        steps = listOf(
            CompiledStep(
                step = 1,
                // A launch names no on-screen element, so it carries its payload
                // in `args` and has no selector at all (E22c).
                selector = null,
                action = "launch_app",
                expect = PostCondition(PostConditionType.APP_FOREGROUND, pkg),
                args = mapOf("app" to pkg),
            ),
        ),
        sourceTraces = listOf("t0"),
    )

    private suspend fun storeWith(vararg skills: CompiledSkill): SkillStore =
        InMemorySkillStore().apply { skills.forEach { save(it) } }

    @Test
    fun `a single learned goal needs no model`() = runTest {
        val store = storeWith(skill("open_whatsapp", "open whatsapp", "com.whatsapp"))
        assertTrue(store.canServeWithoutModel(Goal("open whatsapp")))
    }

    @Test
    fun `a compound goal of two learned skills needs no model`() = runTest {
        // THE REGRESSION. Both halves are compiled, so the runtime composes this
        // at zero model calls — but `match()` alone returns null for it, and a
        // caller that asks only `match()` pays for a model it will never call.
        val store = storeWith(
            skill("open_whatsapp", "open whatsapp", "com.whatsapp"),
            skill("go_to_linkedin", "go to linkedin", "com.linkedin.android"),
        )

        val compound = Goal("open whatsapp then go to linkedin")
        assertTrue(
            store.match(compound) == null,
            "precondition: a compound goal does not match a single skill — " +
                "if this ever stops being true, this test is no longer testing anything",
        )
        assertTrue(
            store.canServeWithoutModel(compound),
            "a goal the runtime will compose for free must not trigger a model load",
        )
    }

    @Test
    fun `a compound goal with one unlearned half does need the model`() = runTest {
        // Composition is all-or-nothing, so this really will plan, and the model
        // really is required. Answering `true` here would be the opposite and
        // worse failure: the run would start without a planner and fail deep.
        val store = storeWith(skill("open_whatsapp", "open whatsapp", "com.whatsapp"))
        assertFalse(store.canServeWithoutModel(Goal("open whatsapp then book a cab")))
    }

    @Test
    fun `an unknown goal needs the model`() = runTest {
        assertFalse(storeWith().canServeWithoutModel(Goal("open whatsapp")))
    }
}
