// Kinds of operational event recorded in the events log (EventEntity.type).
// Stored by NAME, so renaming a constant is a data migration — add new ones instead.
package app.balancee.smartpump.display.domain.model

enum class EventType {
    /**
     * Fuel counted by the adapter while the app was not watching, and confidently attributed to
     * the transaction that was in flight. Recorded even though the customer was billed correctly,
     * because the litre count on that sale jumps on resume and nobody can otherwise explain the
     * difference between the app and the dispenser's own totaliser.
     */
    PULSE_GAP_RECOVERED,

    /**
     * Fuel counted by the adapter while the app was not watching that could NOT be attributed:
     * the adapter had itself restarted, no anchor was stored, the adapter was silent, the gap was
     * too large to be plausible, or there was no transaction in flight. The litres are real and
     * the station has lost them. Needs a human.
     */
    PULSE_GAP_UNEXPLAINED,

    /**
     * The server's price per litre replaced the one this device was holding (Phase 10c-bis).
     *
     * Written only when the figure actually moved, so the log is a record of price changes rather
     * than of every sync. It is the operator's only evidence that the price on the screen changed
     * without anyone visiting the pump — which is the whole point of syncing it, and therefore the
     * thing that must be auditable.
     */
    PRICE_SYNCED,

    /**
     * The price moved between a fill-up ending and its payment being authorised, so the customer is
     * charged an amount other than the one they watched climb on the display.
     *
     * Unavoidable rather than accidental: the server checks the amount against **its own** price, so
     * honouring the struck one would be a refused sale. The station proceeds at the server's price
     * and records this, because a customer querying their receipt is otherwise disputing a number
     * nobody can reconstruct. Seconds-wide and rare; not an error.
     */
    PRICE_CHANGED_MID_SALE,

    /**
     * Fuel was dispensed and the backend refused to record it, for a reason that will not change
     * (Phase 10f).
     *
     * The station has sold fuel the backend's ledger does not know about, and no amount of waiting
     * fixes it — so it needs a person, which is what this log is for. A refusal that *may* pass
     * (no signal, a 500, a payment not yet confirmed) is not written here: the upload job simply
     * asks again, and an event per attempt would bury the ones that matter.
     */
    DISPENSE_UPLOAD_FAILED,
}
