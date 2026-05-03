// Single-row table (id = 1) storing transaction state + pulse count for power-cut recovery.
// Written on every state transition and every N pulses during dispensing.
package app.balancee.smartpump.display.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pulse_state")
data class PulseStateEntity(
    @PrimaryKey val id: Int = 1,
    /** kotlinx-serialization JSON of the current TransactionState. */
    val transactionStateJson: String,
    /** Transaction reference (e.g. "BLC-00847") stored alongside state for UI recovery. */
    val currentTransactionRef: String?,
    /** Cumulative pulse count from the Arduino for this dispensing session. */
    val pulseCount: Int,
    /** Epoch millis of the last received pulse — used for 3 s nozzle-shutoff detection. */
    val lastPulseTimeMs: Long,
    val updatedAt: Long,
)
