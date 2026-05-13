// Debug-build payment processor. Emits Pending immediately, then Success or Failed after
// a configurable delay. The debug screen (Phase 4b) flips `autoApprove` to test the failure
// path, and `pendingDelayMs` to exercise the AwaitingPayment timeout UI.
// `triggerInstantResolve()` is the Flow 5 "SMS just arrived" injector — it bypasses the
// pending delay on the next (or in-flight) payment, resolving via `autoApprove`.
//
// Generates monotonically-increasing transaction refs in the BLC-NNNNN format used in the spec.
package app.balancee.smartpump.display.data.payment

import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.PaymentResult
import app.balancee.smartpump.display.domain.payment.PaymentProcessor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockPaymentProcessor @Inject constructor() : PaymentProcessor {

    private val _autoApprove = MutableStateFlow(true)
    val autoApprove: StateFlow<Boolean> = _autoApprove.asStateFlow()

    private val _pendingDelayMs = MutableStateFlow(DEFAULT_PENDING_DELAY_MS)
    val pendingDelayMs: StateFlow<Long> = _pendingDelayMs.asStateFlow()

    private val _failureReason = MutableStateFlow(DEFAULT_FAILURE_REASON)
    val failureReason: StateFlow<String> = _failureReason.asStateFlow()

    /**
     * CONFLATED so multiple presses of "force resolve" don't queue up — at most one signal
     * is pending. Drained at the start of each [process] call to avoid a stale signal
     * collapsing the next transaction unexpectedly.
     */
    private val instantResolve = Channel<Unit>(capacity = Channel.CONFLATED)

    private val refCounter = AtomicInteger(START_REF_NUMBER)

    fun setAutoApprove(value: Boolean) { _autoApprove.value = value }
    fun setPendingDelayMs(value: Long) { _pendingDelayMs.value = value.coerceAtLeast(0L) }
    fun setFailureReason(value: String) { _failureReason.value = value }

    /**
     * Bypass the pending delay on the in-flight (or next) [process] call. Used by the
     * debug screen as the "SMS just arrived" injector for Flow 5 (USSD) and the generic
     * "force resolve" for all other digital flows.
     */
    fun triggerInstantResolve() { instantResolve.trySend(Unit) }

    override fun process(method: PaymentMethod, amountKobo: Long): Flow<PaymentResult> = flow {
        instantResolve.tryReceive() // drain any stale signal from a previous transaction
        val ref = nextRef()
        emit(PaymentResult.Pending(transactionRef = ref, method = method))

        val delayMs = _pendingDelayMs.value
        if (delayMs > 0) {
            // Wait the configured delay OR until the debug "force resolve" fires.
            withTimeoutOrNull(delayMs) { instantResolve.receive() }
        }

        if (_autoApprove.value) {
            emit(PaymentResult.Success(transactionRef = ref, amountKobo = amountKobo, method = method))
        } else {
            emit(PaymentResult.Failed(reason = _failureReason.value, transactionRef = ref))
        }
    }

    private fun nextRef(): String = "BLC-%05d".format(refCounter.incrementAndGet())

    private companion object {
        const val DEFAULT_PENDING_DELAY_MS = 3_000L
        const val DEFAULT_FAILURE_REASON = "Mock: payment declined"
        const val START_REF_NUMBER = 0
    }
}
