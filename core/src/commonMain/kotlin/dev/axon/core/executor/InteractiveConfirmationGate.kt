package dev.axon.core.executor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [ConfirmationGate] that suspends until a human answers (§16, E28).
 *
 * ## What this replaces
 *
 * `ConfirmationGate.DENY` was the default and nothing ever replaced it, so AXON
 * **could not perform an irreversible action at all** — it refused every call,
 * message and payment rather than doing them unasked. That is the right way to
 * fail while no UI exists, and it is not what §16 asks for. §16 requires the
 * user be *asked*; a capability that is simply absent honours the letter of "no
 * unconfirmed irreversible actions" while delivering none of its intent.
 *
 * ## Why it lives in `:core`
 *
 * Nothing here is Android. Asking is a *policy* — one question at a time, fail
 * closed on anything unexpected — and only the surface that displays it is
 * platform-specific. Putting the policy here means it is unit-tested on the JVM,
 * which matters more than usual: this is the last thing standing between the
 * model and an irreversible act, and `:android:app` has no test suite at all.
 *
 * ## Blocking is the mechanism, not a side effect
 *
 * By the time this is called the action has passed the grammar and the
 * precondition gate and is one dispatch away. Suspending here is what makes
 * "explicit confirmation" mean *before it happens* rather than *while it
 * happens*. An unanswered prompt is a stalled task — visibly stuck, recoverable
 * — which is the correct failure direction when the alternative is an
 * unrecoverable act.
 */
public class InteractiveConfirmationGate(
    /**
     * Show the request. Called once per question, off the executor's path.
     *
     * Must not block: the answer arrives later through [approve] or [deny].
     */
    private val present: (ConfirmationReason) -> Unit,

    /** Called when a question is resolved or abandoned, so a UI can clear it. */
    private val dismiss: () -> Unit = {},
) : ConfirmationGate {

    private val lock = Mutex()
    private var outstanding: CompletableDeferred<Boolean>? = null

    /** The question awaiting an answer, or `null`. */
    public var pending: ConfirmationReason? = null
        private set

    override suspend fun confirm(reason: ConfirmationReason): Boolean {
        val answer = CompletableDeferred<Boolean>()

        lock.withLock {
            // One question at a time.
            //
            // A second irreversible action arriving while the first is unanswered
            // would otherwise replace the question on screen, and the user's
            // answer would be applied to an act they never saw. Refusing the
            // second is the only safe reading: the first is still the one being
            // asked about.
            if (outstanding != null) return false
            outstanding = answer
            pending = reason
        }

        present(reason)

        return try {
            answer.await()
        } catch (e: CancellationException) {
            // The task was stopped while asking. §16 admits one reading of an
            // unanswered question, and it is "no".
            //
            // Rethrown after clearing, because swallowing cancellation would
            // leave the executor running inside a job the caller has abandoned.
            clear()
            throw e
        } finally {
            clear()
        }
    }

    /** The user approved the outstanding request. */
    public fun approve() {
        outstanding?.complete(true)
    }

    /** The user declined, or dismissed the request without answering. */
    public fun deny() {
        outstanding?.complete(false)
    }

    private fun clear() {
        outstanding = null
        pending = null
        dismiss()
    }
}
