package dev.axon.core.skills

import dev.axon.core.model.DeviceAction
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.ResolvedHandles
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.model.TaskOutcome
import dev.axon.core.model.TraceStep
import dev.axon.core.model.VerifiedTrace
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Selector robustness at compile time — §7.7, **E26**.
 *
 * The model picks a selector from what it can see in the prompt, which is
 * usually the visible text. The *gate* then resolves that text to a real node,
 * and that node frequently carries `com.whatsapp:id/send` — a handle the app's
 * own developer controls, immune to translation and copy changes.
 *
 * That handle used to be discarded, which is why `preferStableSelector` was a
 * no-op: it had nothing better to choose from. The compiler now sees what was
 * actually matched and can freeze the sturdier route to the same element.
 *
 * The tests come in two halves, and the second matters more: promotion must
 * never *invent* a handle, and must never touch a parameterised selector.
 */
class SelectorStabilityTest {

    private val compiler = DefaultSkillCompiler()

    private fun traceWith(
        target: Target,
        handles: ResolvedHandles?,
        goal: String = "send it",
        params: Map<String, String> = emptyMap(),
    ) = VerifiedTrace(
        traceId = "t1",
        goal = goal,
        params = params,
        steps = listOf(
            TraceStep(
                action = DeviceAction.Tap(
                    target,
                    PostCondition(PostConditionType.NODE_PRESENT, "Sent"),
                ),
                preOk = true,
                postOk = true,
                latencyMs = 10,
                resolvedHandles = handles,
            ),
        ),
        outcome = TaskOutcome.SUCCESS,
        llmCalls = 1,
        totalMs = 100,
        device = "test",
        model = "test",
    )

    private suspend fun compiledSelector(
        target: Target,
        handles: ResolvedHandles?,
        goal: String = "send it",
        params: Map<String, String> = emptyMap(),
    ): Target? {
        val result = compiler.compile(traceWith(target, handles, goal, params))
        assertIs<CompileResult.Compiled>(result)
        return result.skill.steps.single().selector
    }

    // ------------------------------------------------------------ promotion --

    @Test
    fun `a text selector is promoted to the view id the node actually had`() = runTest {
        // The common case. The model wrote text="Send" because that is what the
        // prompt showed it; the node carried com.whatsapp:id/send, which the
        // app's developer controls and which survives translation.
        val selector = compiledSelector(
            Target(TargetBy.TEXT, "Send"),
            ResolvedHandles(viewId = "com.whatsapp:id/send", contentDescription = "Send", text = "Send"),
        )

        assertEquals(Target(TargetBy.ID, "com.whatsapp:id/send"), selector)
    }

    @Test
    fun `text is promoted to content-desc when there is no view id`() = runTest {
        // Content-description outranks visible text because it does not change
        // when the app's copy does.
        val selector = compiledSelector(
            Target(TargetBy.TEXT, "Send"),
            ResolvedHandles(contentDescription = "Send message", text = "Send"),
        )

        assertEquals(Target(TargetBy.CONTENT_DESC, "Send message"), selector)
    }

    @Test
    fun `a coordinate is promoted to any label available`() = runTest {
        // A coordinate survives almost nothing — not a rotation, not a font-size
        // change, not a different density. Any label beats it.
        val selector = compiledSelector(
            Target(TargetBy.COORD, "540,1200"),
            ResolvedHandles(contentDescription = "Send"),
        )

        assertEquals(Target(TargetBy.CONTENT_DESC, "Send"), selector)
    }

    // ----------------------------------------------------------- refusals ----

    @Test
    fun `a selector is never promoted to a handle the node did not have`() = runTest {
        // The compiler cannot invent a view id. With nothing better observed,
        // the model's own selector stands — which is correct, not a fallback:
        // it is the only handle known to address this element.
        val selector = compiledSelector(
            Target(TargetBy.TEXT, "Send"),
            ResolvedHandles(text = "Send"),
        )

        assertEquals(Target(TargetBy.TEXT, "Send"), selector)
    }

    @Test
    fun `a step with no recorded handles is left exactly as the model wrote it`() = runTest {
        // Traces recorded before E26 carry no handles. They must still compile,
        // unchanged, rather than being rewritten on a guess.
        val selector = compiledSelector(Target(TargetBy.TEXT, "Send"), handles = null)
        assertEquals(Target(TargetBy.TEXT, "Send"), selector)
    }

    @Test
    fun `an id selector is left alone, being already the most stable form`() = runTest {
        val selector = compiledSelector(
            Target(TargetBy.ID, "com.whatsapp:id/send"),
            ResolvedHandles(viewId = "com.whatsapp:id/other", contentDescription = "Send"),
        )

        assertEquals(
            Target(TargetBy.ID, "com.whatsapp:id/send"), selector,
            "an already-stable selector must not be rewritten from the resolved node",
        )
    }

    @Test
    fun `a parameterised selector is never promoted`() = runTest {
        // The one that would be a real bug. "Ammi" is a *parameter*: its value is
        // substituted from the caller's params at replay. Promoting it to the
        // view id observed while messaging Ammi would freeze Ammi's row into
        // every future replay — the skill would silently only ever message her,
        // which is exactly the macro-versus-skill error §7.7 warns about.
        val selector = compiledSelector(
            Target(TargetBy.TEXT, "Ammi"),
            ResolvedHandles(viewId = "com.whatsapp:id/contact_row_3", contentDescription = "Ammi"),
            goal = "send on my way to Ammi",
            params = mapOf("contact" to "Ammi"),
        )

        assertEquals(
            Target(TargetBy.TEXT, "Ammi"), selector,
            "a slot-bound selector must keep its substitutable form",
        )
    }
}
