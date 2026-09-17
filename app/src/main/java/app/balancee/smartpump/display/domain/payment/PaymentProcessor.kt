// Abstraction over the Balanceè payments backend.
// Real impl (Phase 10c) POSTs /api/pump/authorise and polls /api/pump/transactions/{id}.
// Mock impl emits Pending then Success or Failed after a configurable delay.
package app.balancee.smartpump.display.domain.payment

import app.balancee.smartpump.display.domain.model.PaymentRequest
import app.balancee.smartpump.display.domain.model.PaymentResult
import kotlinx.coroutines.flow.Flow

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
}
