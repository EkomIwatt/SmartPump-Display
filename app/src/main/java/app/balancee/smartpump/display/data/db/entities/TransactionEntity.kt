// Append-only audit row for a completed transaction. Synced to backend by WorkManager.
package app.balancee.smartpump.display.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val id: String,
    val flow: String,                  // TransactionFlow.name
    val paymentMethod: String?,        // PaymentMethod.name, null for cash-only flows
    val litresDispensed: Double,
    val amountKobo: Long,
    val priceKoboPerLitre: Long,
    val transactionRef: String,
    val attendantId: String?,
    val attendantNote: String?,
    val createdAt: Long,
    val syncedAt: Long?,
)
