// Append-only audit row for a completed transaction. Synced to the backend in a later phase.
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
    /**
     * Litres added to this sale by Phase 7h pulse-gap recovery — fuel the adapter counted while
     * the app was not watching. Almost always 0.0. Non-zero means litresDispensed contains a
     * figure the app did not observe pulse-by-pulse, which is what makes the sale explain itself
     * when its litre count is later compared against the dispenser's own totaliser.
     *
     * NOT NULL with a 0.0 default, unlike pulse_state.adapterCount: for a row written before 7h
     * existed, "no recovery was applied" is simply true, so zero is the honest value rather than
     * a guess.
     */
    val recoveredLitres: Double = 0.0,
    /**
     * The server's `BPM-…` reference from `/authorise`. NULL for a cash sale, which nothing
     * authorised and which therefore has nothing to upload — see `Transaction.isUploadable`.
     * Nullable rather than defaulted for the same reason `device_config.fuelType` is: there is no
     * honest value to invent for a row that never had one.
     */
    val paymentReference: String? = null,
    /** Epoch millis when fuel began to flow. NULL on rows written before 10f. */
    val startedAt: Long? = null,
    /**
     * Why this row will never be uploaded — set only for a TERMINAL failure. A row carrying this
     * is deliberately left unsynced: it stays in the audit log, visible, with the reason attached,
     * rather than being retried forever or silently marked done.
     */
    val uploadError: String? = null,
)
