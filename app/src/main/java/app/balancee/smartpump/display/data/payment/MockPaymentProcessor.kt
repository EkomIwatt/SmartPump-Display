// Debug-build payment processor. Emits Pending immediately, then Success or Failed after
// a configurable delay. The debug screen (Phase 4b) flips `autoApprove` to test the failure
// path, and `pendingDelayMs` to exercise the AwaitingPayment timeout UI.
// `triggerInstantResolve()` is the Flow 5 "SMS just arrived" injector — it bypasses the
// pending delay on the next (or in-flight) payment, resolving via `autoApprove`.
//
// Generates monotonically-increasing transaction refs in the BLC-NNNNN format used in the spec.
package app.balancee.smartpump.display.data.payment

import app.balancee.smartpump.display.domain.model.FailureCopy
import app.balancee.smartpump.display.domain.model.PaymentRequest
import app.balancee.smartpump.display.domain.model.PaymentResult
import app.balancee.smartpump.display.domain.payment.PaymentProcessor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockPaymentProcessor @Inject constructor(
    // Only used to date the fabricated expiry. Injected rather than read off the wall clock so a
    // test can assert the 20 minutes without waiting for them.
    private val clock: Clock,
) : PaymentProcessor {

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

    override fun process(request: PaymentRequest): Flow<PaymentResult> = flow {
        instantResolve.tryReceive() // drain any stale signal from a previous transaction
        val ref = nextRef()
        emit(
            PaymentResult.Pending(
                transactionRef = ref,
                method = request.method,
                // The mock does not re-price, so the requested figures ARE the authorised ones.
                // The real processor quotes against the server and the two come apart there.
                amountKobo = request.amountKobo,
                litres = request.expectedLitres,
                // Shaped like the real thing so the screens that consume it in 10c are exercised
                // by the debug path too — but pointed at a host that cannot take a payment, so a
                // mock QR scanned by accident fails instead of charging someone.
                checkoutUrl = "https://checkout.invalid/mock/$ref",
                expiresAt = clock.instant().plus(MOCK_EXPIRY),
                paymentReference = "BPM-MOCK-$ref",
            )
        )
        awaitResolution(ref, request)
    }

    /**
     * Resume is the same wait without a new reference (Phase 10d).
     *
     * The mock cannot reproduce the defect this method exists for — re-running `process` costs it
     * nothing — but it must honour the same contract, or the debug build exercises a shape the
     * release build does not have. No `Pending`: the caller restored the QR from disk.
     */
    override fun resume(
        transactionRef: String,
        request: PaymentRequest,
        deadline: Instant?,
    ): Flow<PaymentResult> = flow {
        instantResolve.tryReceive()
        awaitResolution(transactionRef, request)
    }

    private suspend fun FlowCollector<PaymentResult>.awaitResolution(
        ref: String,
        request: PaymentRequest,
    ) {
        val delayMs = _pendingDelayMs.value
        if (delayMs > 0) {
            // Wait the configured delay OR until the debug "force resolve" fires.
            withTimeoutOrNull(delayMs) { instantResolve.receive() }
        }

        if (_autoApprove.value) {
            emit(
                PaymentResult.Success(
                    transactionRef = ref,
                    amountKobo = request.amountKobo,
                    method = request.method,
                    paymentReference = "BPM-MOCK-$ref",
                    litresAuthorised = request.expectedLitres,
                )
            )
        } else {
            emit(
                PaymentResult.Failed(
                    // The customer's line is the same one a real declined payment gets; the mock's
                    // reason is a diagnostic and belongs behind the PIN with the rest of them.
                    failure = FailureCopy(
                        customerMessage = FailureCopy.PAYMENT_NOT_COMPLETED,
                        attendantDetail = _failureReason.value,
                    ),
                    transactionRef = ref,
                ),
            )
        }
    }

    private fun nextRef(): String = "BLC-%05d".format(refCounter.incrementAndGet())

    private companion object {
        // 5s so the QR screen is actually visible during demo / preview runs before
        // mock auto-approval. Debug screen overrides this for soak/timeout testing.
        const val DEFAULT_PENDING_DELAY_MS = 5_000L
        const val DEFAULT_FAILURE_REASON = "Mock: payment declined"
        const val START_REF_NUMBER = 0

        /** Matches the 20 minutes measured on production (TODO #43), not the 5 the app used to assume. */
        val MOCK_EXPIRY: Duration = Duration.ofMinutes(20)
    }
}
