// Completed transaction record written to Room and eventually synced to the Balanceè backend.
package app.balancee.smartpump.display.domain.model

import androidx.compose.runtime.Immutable

/**
 * @param id                Unique transaction ID, e.g. "BLC-00847".
 * @param amountKobo        Total amount charged in kobo (100 kobo = ₦1).
 * @param priceKoboPerLitre Fuel price at time of transaction.
 * @param syncedAt          Epoch millis when successfully synced to backend; null if pending sync.
 */
@Immutable
data class Transaction(
    val id: String,
    val mode: TransactionMode,
    val paymentMethod: PaymentMethod,
    val litresDispensed: Double,
    val amountKobo: Long,
    val priceKoboPerLitre: Long,
    val transactionRef: String,
    val attendantNote: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,
)
