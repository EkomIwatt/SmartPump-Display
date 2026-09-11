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
     *
     * [adapterCount] is the adapter's own free-running count at this instant, stored as the
     * anchor the Phase 7h reconciler subtracts from after a restart. It is a REQUIRED parameter
     * rather than a defaulted one so that no call site can quietly stop maintaining the anchor;
     * pass null only when the adapter's count is genuinely unknown, which stores null and makes
     * the reconciler decline to attribute rather than guess.
     */
    suspend fun savePulseCount(count: Int, lastPulseTimeMs: Long, adapterCount: Long?)

    /**
     * Restore the pulse count from before the power cut.
     * Returns 0 if nothing was saved.
     */
    suspend fun restorePulseCount(): Int

    /**
     * The adapter's free-running count as of the last persisted write, or null if none was
     * recorded (a pump updated from before Phase 7h, or a row written while the adapter was
     * silent). Null is not zero — see PulseStateEntity.adapterCount.
     */
    suspend fun restoreAdapterAnchor(): Long?

    /** The transaction reference stored alongside the last saved state, or null. */
    suspend fun getActiveTransactionRef(): String?
}
