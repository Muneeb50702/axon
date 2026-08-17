package dev.axon.core.executor

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The last thing standing between the model and an irreversible act (§16, E28).
 *
 * Every test here is a way the gate could let something through that the user
 * did not approve. That asymmetry justifies the file: a false *refusal* costs a
 * stalled task the user can retry, and a false *approval* places a call, sends
 * a message or spends money. Only one of those is recoverable, so the tests are
 * about the paths that must all end in `false`.
 */
class InteractiveConfirmationGateTest {

    private fun reason(effect: String = "place a call") =
        ConfirmationReason(effect, "Ammi", "call")

    @Test
    fun `an approved request goes through`() = runTest {
        val gate = InteractiveConfirmationGate(present = {})
        val answer = async { gate.confirm(reason()) }

        yield()
        gate.approve()

        assertTrue(answer.await())
    }

    @Test
    fun `a denied request does not`() = runTest {
        val gate = InteractiveConfirmationGate(present = {})
        val answer = async { gate.confirm(reason()) }

        yield()
        gate.deny()

        assertFalse(answer.await())
    }

    @Test
    fun `the request is presented before the caller is blocked`() = runTest {
        // If `present` were called after awaiting, nothing would ever appear on
        // screen and every irreversible action would hang until the step budget
        // ran out — indistinguishable, to the user, from the agent freezing.
        var shown: ConfirmationReason? = null
        val gate = InteractiveConfirmationGate(present = { shown = it })

        val answer = async { gate.confirm(reason("send a message")) }
        yield()

        assertEquals("send a message", shown?.effect)
        gate.deny()
        answer.await()
    }

    @Test
    fun `a second request while one is outstanding is refused, not queued`() = runTest {
        // The dangerous case. Queuing would put a second question on screen and
        // apply the user's answer to whichever the UI happened to show; replacing
        // would apply their answer to an act they never saw. Refusing the second
        // keeps the answer attached to the question that was asked.
        val shown = mutableListOf<ConfirmationReason>()
        val gate = InteractiveConfirmationGate(present = { shown += it })

        val first = async { gate.confirm(reason("place a call")) }
        yield()

        val second = gate.confirm(reason("spend money"))

        assertFalse(second, "a competing irreversible action must be refused")
        assertEquals(1, shown.size, "and must never reach the user")

        gate.approve()
        assertTrue(first.await(), "the original question is unaffected")
    }

    @Test
    fun `answering clears the way for the next question`() = runTest {
        val shown = mutableListOf<ConfirmationReason>()
        val gate = InteractiveConfirmationGate(present = { shown += it })

        val first = async { gate.confirm(reason("place a call")) }
        yield()
        gate.approve()
        first.await()

        val second = async { gate.confirm(reason("send a message")) }
        yield()
        gate.deny()

        assertFalse(second.await())
        assertEquals(2, shown.size, "the gate is reusable, not single-shot")
    }

    @Test
    fun `the pending question is exposed and then cleared`() = runTest {
        // A UI that restarts mid-question — a rotation, a process death — has to
        // be able to re-render what is outstanding, and must not show a stale
        // prompt for something already answered.
        val gate = InteractiveConfirmationGate(present = {})
        assertNull(gate.pending)

        val answer = async { gate.confirm(reason()) }
        yield()
        assertEquals("place a call", gate.pending?.effect)

        gate.approve()
        answer.await()
        assertNull(gate.pending, "an answered question must not linger")
    }

    @Test
    fun `dismiss fires so a notification can be taken down`() = runTest {
        var dismissed = 0
        val gate = InteractiveConfirmationGate(present = {}, dismiss = { dismissed++ })

        val answer = async { gate.confirm(reason()) }
        yield()
        gate.deny()
        answer.await()

        assertEquals(1, dismissed, "a question left on screen after it is answered invites a wrong tap")
    }

    @Test
    fun `an approval arriving before anyone asked is ignored`() = runTest {
        // A stale notification action — tapped after the task already ended —
        // must not pre-authorise the next irreversible act.
        val gate = InteractiveConfirmationGate(present = {})
        gate.approve()

        val answer = async { gate.confirm(reason()) }
        yield()
        gate.deny()

        assertFalse(answer.await(), "a stale approval must not carry over to a new question")
    }
}
