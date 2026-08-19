package dev.axon.core.runtime

import dev.axon.core.driver.DeviceDriver
import dev.axon.core.executor.ConfirmationGate
import dev.axon.core.executor.DefaultExecutor
import dev.axon.core.model.ActResult
import dev.axon.core.model.Bounds
import dev.axon.core.model.Capability
import dev.axon.core.model.DeviceAction
import dev.axon.core.model.PostCondition
import dev.axon.core.model.PostConditionType
import dev.axon.core.model.Target
import dev.axon.core.model.TargetBy
import dev.axon.core.model.UiNode
import dev.axon.core.model.UiTree
import dev.axon.core.verifier.PostConditionEvaluator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Each §14.3 switch removes exactly its own mechanism — and nothing else.
 *
 * §14.3's design principle is that turning a feature off must remove *that*
 * feature, so a difference between two arms is attributable to one variable. A
 * switch that also changes timing, or that silently changes nothing, breaks the
 * attribution and the table stops meaning anything.
 *
 * Two of the three switches reached the device only today. Before that, arms A
 * and B were rows in a matrix that could not be run over the corpus: the grammar
 * had a path only through an acceptance harness measuring *generations* rather
 * than tasks, and the verifier had none at all.
 */
class AblationSwitchTest {

    private fun screen(vararg labels: String) = UiTree(
        "com.example", null,
        labels.mapIndexed { i, l ->
            UiNode(i, "button", text = l, contentDescription = l, clickable = true,
                bounds = Bounds(0, i * 60, 200, i * 60 + 50))
        },
        capturedAtMs = 0,
    )

    /** Dispatches happily; the screen never changes, so no post-condition holds. */
    private class InertDevice : DeviceDriver {
        var dispatched = 0
        var observations = 0
        override suspend fun observe(): UiTree {
            observations++
            return UiTree(
                "com.example", null,
                listOf(UiNode(0, "button", text = "Go", contentDescription = "Go",
                    clickable = true, bounds = Bounds(0, 0, 200, 50))),
                capturedAtMs = 0,
            )
        }
        override suspend fun act(action: DeviceAction): ActResult {
            dispatched++
            return ActResult.Dispatched()
        }
        override suspend fun assert(condition: PostCondition) =
            PostConditionEvaluator.evaluate(condition, observe())
        override fun capabilities() = setOf(
            Capability.UI_OBSERVE, Capability.UI_GESTURE, Capability.APP_LAUNCH,
        )
        override val deviceFamily = "fake/test"
    }

    private val neverHolds = PostCondition(PostConditionType.NODE_PRESENT, "Never")
    private val tap = DeviceAction.Tap(Target(TargetBy.CONTENT_DESC, "Go"), neverHolds)

    @Test
    fun `with the verifier on, a dead action is reported as failed`() = runTest {
        val device = InertDevice()
        val outcome = DefaultExecutor(
            device, settleMs = 0, nowMs = { 0L },
            confirmation = ConfirmationGate.ALLOW_FOR_TESTING,
            verify = true,
        ).run(tap, screen("Go"))

        assertTrue(outcome.preOk, "the gate should pass — \"Go\" is on screen")
        assertEquals(false, outcome.postOk, "the post-condition does not hold, and C2 should say so")
        assertEquals(1, device.dispatched)
    }

    @Test
    fun `with the verifier off, the same dead action is reported as succeeded`() = runTest {
        // THE ABLATION. An agent without C2 believes its actions worked; that
        // belief is exactly what is being removed, and the arm is worthless
        // unless the flag actually changes the reported outcome.
        val device = InertDevice()
        val outcome = DefaultExecutor(
            device, settleMs = 0, nowMs = { 0L },
            confirmation = ConfirmationGate.ALLOW_FOR_TESTING,
            verify = false,
        ).run(tap, screen("Go"))

        assertEquals(true, outcome.postOk, "without the verifier the step must report success")
        assertEquals(null, outcome.verifyResult, "no verdict was reached, so none is reported")
        assertEquals(1, device.dispatched, "the action must still be dispatched")
    }

    @Test
    fun `turning the verifier off does not disable the precondition gate`() = runTest {
        // The gate is a different defence and a different row. If arm A lost
        // both at once, any difference between A and C would be attributable to
        // either, and §14.3's attribution would collapse.
        val device = InertDevice()
        val outcome = DefaultExecutor(
            device, settleMs = 0, nowMs = { 0L },
            confirmation = ConfirmationGate.ALLOW_FOR_TESTING,
            verify = false,
        ).run(
            DeviceAction.Tap(Target(TargetBy.CONTENT_DESC, "Absent"), neverHolds),
            screen("Go"),
        )

        assertFalse(outcome.preOk, "the gate must still refuse a target that is not there")
        assertEquals(0, device.dispatched, "nothing may reach the device")
    }

    @Test
    fun `the arms differ in exactly the switches §14_3 specifies`() {
        // Guards the table itself. A typo here would mislabel every result
        // collected under that arm, and the CSV keeps only the letter.
        assertEquals(listOf(false, false, false), with(RunConfig.A) { listOf(grammar, verifier, skillReplay) })
        assertEquals(listOf(true, false, false), with(RunConfig.B) { listOf(grammar, verifier, skillReplay) })
        assertEquals(listOf(true, true, false), with(RunConfig.C) { listOf(grammar, verifier, skillReplay) })
        assertEquals(listOf(true, true, true), with(RunConfig.DEFAULT) { listOf(grammar, verifier, skillReplay) })

        // Adjacent arms differ in exactly one switch, which is what makes a
        // difference between them attributable.
        assertEquals(RunConfig.A.copy(id = "B", grammar = true), RunConfig.B)
        assertEquals(RunConfig.B.copy(id = "C", verifier = true), RunConfig.C)
        assertEquals(RunConfig.C.copy(id = "D", skillReplay = true), RunConfig.DEFAULT)
    }

    @Test
    fun `COLD keeps its own identity`() {
        // Every cold measurement taken before the grammar and verifier switches
        // existed is labelled COLD in its CSV. Re-pointing that name at "C"
        // would silently rewrite what those runs were configured as.
        assertEquals("COLD", RunConfig.of("cold").id)
        assertEquals(RunConfig.C.copy(id = "COLD"), RunConfig.of("cold"))
    }
}
