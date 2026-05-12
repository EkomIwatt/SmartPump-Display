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
)
