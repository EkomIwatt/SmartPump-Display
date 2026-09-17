// Result emitted by PaymentProcessor after a payment attempt resolves.
//
// Phase 10a widened Pending and Success. The mock only ever needed a reference to print on screen;
// the real processor gets three more things back from `/authorise` that the app cannot function
// without — the Paystack checkout URL the customer actually scans, the server's expiry, and the
// payment reference the dispense upload has to quote. All three are nullable because not every
// payment method has them (USSD has no checkout URL) and the mock does not have real ones.
package app.balancee.smartpump.display.domain.model

import java.time.Instant

sealed class PaymentResult {

    /**
     * Payment confirmed. [transactionRef] matches the ref shown on the QR / USSD screen.
     * The state machine transitions to Dispensing on receipt of this.
     *
     * @param paymentReference the server's own reference (`BPM-…`), distinct from [transactionRef],
     *   which is the id **we** generated and sent. `POST /transactions/upload` requires this one, so
     *   a dispense cannot be reported without it — which is also why a cash sale has nothing to
     *   upload. Null from processors that do not talk to the backend.
     */
    data class Success(
        val transactionRef: String,
        val amountKobo: Long,
        val method: PaymentMethod,
        val paymentReference: String? = null,
    ) : PaymentResult()

    /** Payment definitively failed. [transactionRef] is null if we never received a ref. */
    data class Failed(
        val reason: String,
        val transactionRef: String? = null,
    ) : PaymentResult()

    /**
     * Payment initiated but not yet confirmed — e.g. QR displayed, USSD dialled.
     * The UI shows a waiting state; the processor will emit Success or Failed later.
     *
     * @param checkoutUrl what the customer scans. For the Balanceè processor this is the Paystack
     *   checkout URL from `/authorise`, and it is the QR's entire contents — a QR carrying anything
     *   else cannot be paid. Null for methods with no scannable target (USSD) and for processors
     *   that have no real one.
     * @param expiresAt when the server stops honouring this payment. **Read it; do not assume it.**
     *   Measured at 20 minutes on production (TODO #43), against three places in the app that still
     *   say five — a countdown that gives up early abandons a sale the server would have honoured,
     *   with the customer standing at the pump. Null means the caller falls back to its own default.
     * @param paymentReference see [Success.paymentReference].
     */
    data class Pending(
        val transactionRef: String,
        val method: PaymentMethod,
        val checkoutUrl: String? = null,
        val expiresAt: Instant? = null,
        val paymentReference: String? = null,
    ) : PaymentResult()
}
