// Single-row table (id = 1) storing transaction state + pulse count for power-cut recovery.
// Written on every state transition and every N pulses during dispensing.
package app.balancee.smartpump.display.data.db.entities

import androidx.room.ColumnInfo
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
    /**
     * The adapter's own FREE-RUNNING count at the moment this row was written — the anchor the
     * Phase 7h reconciler subtracts from to learn how much fuel moved while the app was down
     * (OPEN_QUESTIONS #25). Distinct from [pulseCount], which is scoped to the transaction and
     * restarts at zero on every relay-open.
     *
     * NULLABLE on purpose, and NULL is not zero. Zero is a legitimate adapter reading (a freshly
     * booted board), so a non-null default would make every pre-7h row claim an anchor of zero and
     * invite the reconciler to attribute the adapter's entire lifetime count as one giant gap.
     * NULL means "no anchor was recorded", which the reconciler refuses to guess from — the same
     * reasoning that made device_config.fuelType nullable at v3.
     */
    val adapterCount: Long?,
    val updatedAt: Long,
    /**
     * Phase 11e (v6): the adapter session the in-flight sale was armed under — see
     * `domain.hardware.SaleSession`. All null / 0 when no sale holds one. A third writer on this
     * row, so it too writes only its own columns (#R12).
     */
    val sessionTransactionRef: String? = null,
    val sessionTag: Long? = null,
    /**
     * `defaultValue` is load-bearing, not tidy. [PulseStateDao.ensureRow] creates this row with an
     * explicit column list, and on a database Room creates fresh a NOT NULL column with no SQL
     * default fails that insert — which `INSERT OR IGNORE` then swallows, so the row never exists
     * and every state and pulse write after it updates nothing. The migration adds the column with
     * `DEFAULT 0`; declaring it here makes a fresh v6 database match a migrated one. Found by
     * PulseRepositoryConcurrencyTest on the SM-T220, 2026-09-22.
     */
    @ColumnInfo(defaultValue = "0")
    val sessionBasePulses: Int = 0,
)
