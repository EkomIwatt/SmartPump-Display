// Room entity for completed transaction records. Append-only audit log.
package app.balancee.smartpump.display.data.db.entity

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
