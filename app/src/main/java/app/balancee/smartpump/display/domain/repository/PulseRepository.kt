// Gateway for persisting transaction state and pulse count across power cuts.
// Written on every state transition and pulse milestone so recovery is always possible.
package app.balancee.smartpump.display.domain.repository

import app.balancee.smartpump.display.domain.model.TransactionState

interface PulseRepository {

    /**
     * Persist [state] to survive a power cut.
     * [transactionRef] is stored alongside (e.g. "BLC-00847") for UI display after recovery.
     */
    suspend fun saveTransactionState(state: TransactionState, transactionRef: String? = null)

    /**
     * Restore the last persisted state on app start.
     * Returns [TransactionState.Idle] if no state has been saved or the stored JSON is corrupt.
     */
    suspend fun restoreTransactionState(): TransactionState

    /**
     * Persist the running pulse count and the timestamp of the last received pulse.
     * Used to detect nozzle shutoff (3 s with no new pulse) after a power-cut recovery.
     */
    suspend fun savePulseCount(count: Int, lastPulseTimeMs: Long)

    /**
     * Restore the pulse count from before the power cut.
     * Returns 0 if nothing was saved.
     */
    suspend fun restorePulseCount(): Int

    /** The transaction reference stored alongside the last saved state, or null. */
    suspend fun getActiveTransactionRef(): String?
}
