// The two halves of a failure, as OQ #17 split them and 2026-09-12 approved: one plain line the
// customer can act on, and the diagnostic half that goes behind the PIN in the swipe-up attendant
// panel.
//
// It exists because the split has to survive the trip out of the data layer. Until 10e the
// processor handed back a single `reason` string and the ViewModel wrapped it in a fixed sentence,
// so every server failure read "Payment was not completed." to the customer and the server's raw
// prose to the attendant — one row of docs/journal/closed/ERROR_COPY_DRAFT.md's Catalogue A, applied to all twelve.
// Deciding the copy is the mapper's job (see `ApiError.toFailureCopy`); carrying it is this type's.
package app.balancee.smartpump.display.domain.model

/**
 * @param customerMessage one plain line for the pump's customer-facing display. Never a code, a
 *   naira figure the customer did not type, or a server string — a customer can act on "your
 *   payment did not go through" and cannot act on "request timestamp is not fresh".
 * @param attendantDetail the same failure said to whoever can fix it. Null when there is nothing an
 *   attendant could do that [customerMessage] does not already say.
 * @param recoverable whether trying again could work — **after a human does whatever the detail
 *   says**, which is a different question from [app.balancee.smartpump.display.data.network.RetryPolicy],
 *   which asks whether re-sending the same bytes unattended could work. An amount mismatch is
 *   TERMINAL to a retry loop and recoverable to an attendant who fixes the price. The two only
 *   meet at one point, stated in the mapper: a *retry later* failure is never shown as a dead end.
 */
data class FailureCopy(
    val customerMessage: String,
    val attendantDetail: String? = null,
    val recoverable: Boolean = true,
) {
    companion object {
        /**
         * The customer's universal fallback, approved 2026-09-12. It repeats a lot, and that is
         * deliberate: it is always the true next step.
         */
        const val SEE_ATTENDANT = "Could not start — please see attendant."

        /** A payment that definitively did not happen, as opposed to one that has not yet. */
        const val PAYMENT_NOT_COMPLETED = "Payment was not completed."

        /**
         * The *not yet*. Distinct from [PAYMENT_NOT_COMPLETED] on purpose — the backend has not
         * seen the money land, which it may do a minute later, and telling the customer their
         * payment failed would send them away from a sale that is about to complete.
         */
        const val PAYMENT_NOT_CONFIRMED = "Payment not confirmed yet."

        /** The tendered amount buys nothing dispensable. The figure is the attendant's, not theirs. */
        const val AMOUNT_TOO_SMALL = "Amount is too small — please see attendant."
    }
}

/** The state the customer screen renders this failure as. Mechanical — the wording is decided by now. */
fun FailureCopy.toErrorState(): TransactionState.Error = TransactionState.Error(
    message = customerMessage,
    recoverable = recoverable,
    attendantDetail = attendantDetail,
)
