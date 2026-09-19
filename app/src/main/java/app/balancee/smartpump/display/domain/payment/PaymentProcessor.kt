// Abstraction over the Balanceè payments backend.
// Real impl (Phase 10c) POSTs /api/pump/authorise and polls /api/pump/transactions/{id}.
// Mock impl emits Pending then Success or Failed after a configurable delay.
package app.balancee.smartpump.display.domain.payment

import app.balancee.smartpump.display.domain.model.PaymentRequest
import app.balancee.smartpump.display.domain.model.PaymentResult
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface PaymentProcessor {

    /**
     * Begin the payment described by [request]. Returns a cold flow that emits:
     *  1. exactly one [PaymentResult.Pending] (with a generated transactionRef) immediately
     *  2. exactly one terminal [PaymentResult.Success] or [PaymentResult.Failed]
     *
     * Cancel by cancelling collection of the returned flow.
     * Cash flows skip this entirely — attendants confirm via UI.
     *
     * **The ref in the Pending is the app's own id, not the server's.** `/authorise` takes a
     * client-generated `transactionId` and echoes it back unchanged (observed at the #32 gate), so
     * the app owns the identity of a sale before the first request leaves the device. That is what
     * makes a payment resumable after a restart: there is an id to ask about, rather than a second
     * sale to create.
     */
    fun process(request: PaymentRequest): Flow<PaymentResult>

    /**
     * Re-attach to a payment that was **already started**, after a restart.
     *
     * The reason this is its own method rather than a flag on [process] (Phase 10d): calling
     * `process` again on boot is exactly what a resumed sale must not do. Against the mock it costs
     * nothing, so it was invisible; against the real backend it POSTs a second `/authorise` and
     * creates a **second sale** for a customer who has already paid for the first. The id is ours
     * and survives the restart, so there is something to ask about.
     *
     * Emits at most one terminal. No [PaymentResult.Pending] — the caller restored the QR, the
     * reference and the deadline from disk and is already showing them; re-emitting would only
     * invite it to overwrite good state with whatever this call happens to learn.
     *
     * @param transactionRef the id the interrupted sale was authorised under.
     * @param request the sale as it was authorised — amounts here are the **authorised** figures
     *   the caller persisted, not a fresh quote. Nothing is re-priced on this path.
     * @param deadline the server's expiry as the caller persisted it. The window kept running while
     *   the app was down, so granting a fresh one here would hold a dead QR on screen; null means
     *   none was recorded and the caller's own countdown is the only bound.
     */
    fun resume(
        transactionRef: String,
        request: PaymentRequest,
        deadline: Instant?,
    ): Flow<PaymentResult>
}
