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

    /**
     * Records the upload job should still try, oldest-first (10f).
     *
     * Narrower than "not yet synced": a cash sale has no `paymentReference` and can never be
     * uploaded, and a record the server has already refused for good carries an `uploadError`.
     * Both are excluded, because a queue that keeps offering work that cannot succeed is a queue
     * nobody can read.
     */
    suspend fun getPendingSync(): List<Transaction>

    /**
     * Mark a record as accepted by the backend.
     *
     * The one write that closes a record. **#48**: the backend's first write is the only one that
     * counts — a later upload carrying a corrected figure returns `200 Transaction recorded` and
     * changes nothing — so nothing in this app may re-send after this.
     */
    suspend fun markSynced(id: String, syncedAt: Long)

    /**
     * Record why a dispense will never be reported. [reason] is attendant-facing.
     *
     * Leaves `syncedAt` null, because it did not sync. The row stays in the audit log carrying its
     * reason rather than being retried forever or quietly marked done.
     */
    suspend fun markUploadFailed(id: String, reason: String)
}
