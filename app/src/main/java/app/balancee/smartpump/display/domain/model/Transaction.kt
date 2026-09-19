// Completed transaction record. Append-only audit log — synced to the Balanceè backend later.
package app.balancee.smartpump.display.domain.model

import androidx.compose.runtime.Immutable

/**
 * @param id                 Pump-side transaction id, e.g. "BLC-00847".
 * @param flow               Which of the five flows this transaction took (see [TransactionFlow]).
 * @param paymentMethod      Digital method used, or null for cash-only flows (FILLUP_CASH, CASH_FIXED).
 * @param litresDispensed    Final verified litre count from the pulse adapter.
 * @param amountKobo         Total charged, in kobo (100 kobo = ₦1).
 * @param priceKoboPerLitre  Fuel price at time of transaction, in kobo/L.
 * @param transactionRef     Short reference shown to customer, e.g. "BLC-847".
 * @param attendantId        Set for attendant-initiated flows once roles ship in V2; null in V1.
 * @param attendantNote      Optional free-text annotation (cash variance, manual override, etc.).
 * @param createdAt          Epoch millis when the transaction completed.
 * @param syncedAt           Epoch millis when synced to backend; null if pending sync.
 * @param recoveredLitres    Litres of [litresDispensed] added by Phase 7h pulse-gap recovery
 *                           rather than observed pulse-by-pulse. Normally 0.0.
 * @param paymentReference   The server's own reference (`BPM-…`) from `/authorise`. **The upload
 *                           cannot go out without it** and only `/authorise` issues one, so null
 *                           means this sale is not uploadable — which is exactly right for a cash
 *                           sale and a defect for any other (10f).
 * @param startedAt          Epoch millis when fuel began to flow. Null for rows written before
 *                           10f; the uploader falls back to [createdAt] and records that it did.
 * @param uploadError        Why this record will never be uploaded, set only for a failure the
 *                           taxonomy calls TERMINAL. The row stays visible and unsynced rather
 *                           than being retried forever or quietly dropped — #45's stance, applied
 *                           to the queue it was written for. Null on every healthy record.
 */
@Immutable
data class Transaction(
    val id: String,
    val flow: TransactionFlow,
    val paymentMethod: PaymentMethod?,
    val litresDispensed: Double,
    val amountKobo: Long,
    val priceKoboPerLitre: Long,
    val transactionRef: String,
    val attendantId: String? = null,
    val attendantNote: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,
    val recoveredLitres: Double = 0.0,
    val paymentReference: String? = null,
    val startedAt: Long? = null,
    val uploadError: String? = null,
) {
    /**
     * Whether this sale is one the backend expects to hear about at all.
     *
     * A cash sale has no `paymentReference` because nothing authorised it, and
     * `POST /transactions/upload` requires one — so it is not *pending* upload, it is *outside* the
     * upload path. Conflating the two would leave every cash sale sitting in the queue forever,
     * failing on a field it can never have.
     */
    val isUploadable: Boolean get() = !paymentReference.isNullOrBlank()
}
