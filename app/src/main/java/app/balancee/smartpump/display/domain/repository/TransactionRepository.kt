// Gateway for completed transaction records — the permanent audit log.
// State persistence lives in PulseRepository; price config lives in DeviceConfigRepository.
package app.balancee.smartpump.display.domain.repository

import app.balancee.smartpump.display.domain.model.Transaction
import kotlinx.coroutines.flow.Flow

interface TransactionRepository {

    /** Persist a completed transaction record. Append-only — records are never deleted. */
    suspend fun saveTransaction(transaction: Transaction)

    /**
     * One record by its pump-side id, or null if it was never saved.
     *
     * Null is a real outcome, not just a defensive branch: `saveTransaction` is called
     * best-effort after a dispense completes (the customer already has fuel, so a failed write
     * must not block the screen), so a completed sale can legitimately have no row.
     */
    suspend fun getTransaction(id: String): Transaction?

    /** Live stream of recent transactions ordered newest-first. */
    fun getRecentTransactions(limit: Int = 50): Flow<List<Transaction>>

    /** Transactions not yet synced to the Balanceè backend, oldest-first (for the future sync job). */
    suspend fun getPendingSync(): List<Transaction>
}
