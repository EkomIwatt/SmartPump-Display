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
        /**
         * Litres the server authorised, when a server authorised any.
         *
         * Without it the caller re-derives litres from [amountKobo] at the device's own cutoff, and
         * gets a **different** number: the quote lands on a payable litre step
         * (`SaleQuote.litreStepMicros`) while `DeviceConfig.litresCutoff` floors to 2 dp. At ₦1,490
         * a ₦5,000 tender authorises 3.355 L and re-derivation gives 3.35 — the pump would stop
         * 5 ml short of what the customer paid for, every time, on a figure 10f will later
         * reconcile against the server's own record.
         *
         * Null for processors with nothing authoritative to offer; the caller keeps its fallback.
         */
        val litresAuthorised: Double? = null,
    ) : PaymentResult()

    /**
     * Payment definitively failed. [transactionRef] is null if we never received a ref.
     *
     * @param failure both halves of what to say about it — the customer's one plain line and the
     *   attendant's diagnostic detail. It was a single `reason` string until 10e, which meant the
     *   ViewModel had to invent the customer's sentence at the call site: every server failure read
     *   "Payment was not completed." no matter what the server had actually refused, and a
     *   *not yet* was indistinguishable from a *no*. Deciding the words is the processor's job,
     *   because only it knows which failure this is.
     */
    data class Failed(
        val failure: FailureCopy,
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
        /**
         * **What will actually be collected**, which is not what the customer tendered.
         *
         * The server checks `amount == expectedLitres × pricePerUnit` exactly and Paystack charges
         * whole kobo, so a round tender is usually not payable: at ₦1,490/L a ₦5,000 pre-pay is
         * authorised at ₦4,998.95 (`SaleQuote`). Until 10d the screen went on showing ₦5,000 beside
         * a checkout page that said ₦4,998.95 — two numbers for one sale, with the customer looking
         * at both. Required rather than defaulted: a processor that does not answer this is
         * showing someone the wrong price.
         */
        val amountKobo: Long,
        /** Litres [amountKobo] buys, on a payable step. The figure the dispense counts toward. */
        val litres: Double,
        val checkoutUrl: String? = null,
        val expiresAt: Instant? = null,
        val paymentReference: String? = null,
    ) : PaymentResult()
}
