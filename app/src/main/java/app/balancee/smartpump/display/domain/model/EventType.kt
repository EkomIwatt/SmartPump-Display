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
     * `/config` reported a price this pump cannot sell at, and the sync refused it (review #7).
     *
     * `pricePerUnit` is a non-null integer, so a station whose price has never been set does not
     * fail to parse — it arrives as **0**. Zero is not a cheap price, it is the absence of one, and
     * storing it would wipe the last figure this pump knew and stop cash sales too. So the figure
     * is discarded and this is written instead, because an operator whose card sales have stopped
     * needs the log to say why rather than to be silent about the one call that could explain it.
     *
     * Written at most once per rejected figure per app run: `/config` is fetched before every
     * authorise, so a per-fetch row would bury everything else in the log by the end of a shift.
     */
    PRICE_SYNC_REJECTED,

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

    /**
     * A digital payment window closed with the payment unconfirmed, and the app stopped watching
     * (Phase 10g).
     *
     * **The customer may still pay after this.** Observed on production 2026-09-19: three minutes
     * past a transaction's own `expiresAt`, `GET /transactions/{id}` still answered 200 with
     * `PENDING_PAYMENT` and the same live Paystack checkout URL. `expiresAt` is a figure the
     * backend reports, not a deadline it enforces — so the window is ours, not theirs, and money
     * can land in it after the tablet has moved on.
     *
     * Written so that money is not the only record such a sale ever had. Until this existed, an
     * abandoned pre-pay left **no trace at all**: a customer returning to say they had paid and
     * got no fuel could not be answered, because nothing on the pump knew the transaction had
     * existed. The detail carries the transaction id, which is what support needs to look it up.
     *
     * Not an error, and common — most are simply someone changing their mind at the screen.
     */
    PAYMENT_ABANDONED,

    /**
     * The pulse adapter would not start a sale — it refused the command or never acknowledged it
     * (Phase 11). No fuel was authorised. Refused usually means firmware older than the app; no
     * answer means the cable, the adapter's power, or the adapter itself.
     *
     * A paid sale stays on its dispensing screen so the attendant can end it (OQ #22) and the
     * money keeps its record; an unpaid one goes to an error. Either way this row is what tells the
     * operator the pump, not the customer, was the problem.
     */
    ADAPTER_DID_NOT_ARM,

    /**
     * The pulse adapter restarted mid-sale and no longer held the sale's session, so the app
     * re-armed it for what was left (Phase 11). Fuel the adapter counted but had not yet reported
     * before it restarted is in neither count — the same loss 7h's reconciliation reports as
     * unexplained. [pulses] is what the sale had counted when it was re-armed.
     */
    ADAPTER_SESSION_LOST,

    /**
     * A fill-up reached the adapter's runaway ceiling (FILLUP_CEILING_LITRES) and the adapter cut
     * the fuel (Phase 11, spec D4). The ceiling is a backstop, not a limit anyone chose for this
     * customer: it means the app kept the relay open without the nozzle-idle shutoff ending the
     * sale, or the ceiling is set below a real fill. Either way, worth a look.
     */
    FILLUP_CEILING_REACHED,
}
