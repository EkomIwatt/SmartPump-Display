// Completed transaction record. Append-only audit log — never deleted, synced to backend by WorkManager.
package app.balancee.smartpump.display.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val id: String,
    val mode: String,               // TransactionMode.name
    val paymentMethod: String,      // PaymentMethod.name
    val litresDispensed: Double,
    val amountKobo: Long,
    val priceKoboPerLitre: Long,
    val transactionRef: String,
    val attendantNote: String?,
    val createdAt: Long,
    val syncedAt: Long?,
)
