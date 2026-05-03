// Result emitted by PaymentProcessor after a payment attempt resolves.
package app.balancee.smartpump.display.domain.model

sealed class PaymentResult {

    /**
     * Payment confirmed. [transactionRef] matches the ref shown on the QR / USSD screen.
     * The state machine transitions to Dispensing on receipt of this.
     */
    data class Success(
        val transactionRef: String,
        val amountKobo: Long,
        val method: PaymentMethod,
    ) : PaymentResult()

    /** Payment definitively failed. [transactionRef] is null if we never received a ref. */
    data class Failed(
        val reason: String,
        val transactionRef: String? = null,
    ) : PaymentResult()

    /**
     * Payment initiated but not yet confirmed — e.g. QR displayed, USSD dialled.
     * The UI shows a waiting state; the processor will emit Success or Failed later.
     */
    data class Pending(
        val transactionRef: String,
        val method: PaymentMethod,
    ) : PaymentResult()
}
