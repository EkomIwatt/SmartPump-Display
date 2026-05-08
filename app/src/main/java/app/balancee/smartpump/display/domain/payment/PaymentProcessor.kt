// Abstraction over the Balanceè payments backend.
// Real impl (later phase) initiates a charge against the virtual account / NFC reader / USSD gateway.
// Mock impl emits Pending then Success or Failed after a configurable delay.
package app.balancee.smartpump.display.domain.payment

import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.PaymentResult
import kotlinx.coroutines.flow.Flow

interface PaymentProcessor {

    /**
     * Begin a payment of [amountKobo] via [method]. Returns a cold flow that emits:
     *  1. exactly one [PaymentResult.Pending] (with a generated transactionRef) immediately
     *  2. exactly one terminal [PaymentResult.Success] or [PaymentResult.Failed]
     *
     * Cancel by cancelling collection of the returned flow.
     * Cash flows skip this entirely — attendants confirm via UI.
     */
    fun process(method: PaymentMethod, amountKobo: Long): Flow<PaymentResult>
}
