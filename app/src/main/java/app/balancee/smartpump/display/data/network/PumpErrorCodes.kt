// The server's stable error codes, in one place.
//
// TODO #18f settled the rule they follow, and it is a rule rather than an inconsistency: business
// failures carry a `code`, authentication failures carry none and are identified by their 401. So
// these are the vocabulary for every business decision the app makes about a refusal — and the
// messages beside them are never it. Those are human prose with values interpolated in
// ("Amount mismatch for PETROL…"), so matching on them breaks silently the day someone rewords one.
//
// All four were observed on production at the #32 gate; see `docs/api-probes/2026-09-16-prod-gate/`.
package app.balancee.smartpump.display.data.network

object PumpErrorCodes {

    /** `amount != expectedLitres × pricePerUnit` at the server's price. A refusal, and final. */
    const val AMOUNT_MISMATCH = "AMOUNT_MISMATCH"

    /**
     * A dispense was uploaded against a sale the server has not seen payment for (409).
     *
     * **The one refusal in this list that is not final** — see [ApiError.retryPolicy]. The server
     * enforcing its own payment gate is a safety property the app does not have to provide; the
     * trap is that it reads exactly like a "no" and is a "not yet".
     */
    const val PAYMENT_NOT_CONFIRMED = "PAYMENT_NOT_CONFIRMED"

    /** No such transaction. It will not start existing, so nothing is gained by asking again. */
    const val TRANSACTION_NOT_FOUND = "TRANSACTION_NOT_FOUND"

    /** The request itself was malformed. Retrying the same bytes produces the same answer. */
    const val INVALID_REQUEST = "INVALID_REQUEST"

    /**
     * Codes that mean **not yet**, rather than **no**.
     *
     * Deliberately a set of exactly what has been observed to behave this way. Adding a code here
     * on a guess turns a genuine refusal into an upload that retries forever; leaving one out turns
     * a temporary state into a dropped record. Neither is discoverable from the wire, so each entry
     * wants an observation behind it.
     */
    val NOT_YET: Set<String> = setOf(PAYMENT_NOT_CONFIRMED)
}
