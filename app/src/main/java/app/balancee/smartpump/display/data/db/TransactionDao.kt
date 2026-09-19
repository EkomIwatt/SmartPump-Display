// DAO for the transactions audit log. Insert-only — records are never deleted locally.
package app.balancee.smartpump.display.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.balancee.smartpump.display.data.db.entities.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions ORDER BY createdAt DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<TransactionEntity>>

    /**
     * Rows the upload job should still try (10f), oldest first.
     *
     * Three conditions, and each excludes a different thing that would otherwise sit in the queue
     * forever: `syncedAt IS NULL` drops what has already gone out (**#48** — the first write is the
     * only one that counts, so a re-send is not a correction, it is noise the server acknowledges
     * and discards); `paymentReference IS NOT NULL` drops cash sales, which have no reference to
     * quote and are outside the upload path rather than behind in it; and `uploadError IS NULL`
     * drops the ones the server has already refused for good.
     */
    @Query(
        "SELECT * FROM transactions " +
            "WHERE syncedAt IS NULL AND paymentReference IS NOT NULL AND uploadError IS NULL " +
            "ORDER BY createdAt ASC",
    )
    suspend fun getPendingSync(): List<TransactionEntity>

    /** Marks a row as accepted by the backend. The one write that closes a record (**#48**). */
    @Query("UPDATE transactions SET syncedAt = :syncedAt WHERE id = :id")
    suspend fun markSynced(id: String, syncedAt: Long)

    /** Records why a row will never be uploaded. Leaves `syncedAt` null: it did not sync. */
    @Query("UPDATE transactions SET uploadError = :reason WHERE id = :id")
    suspend fun markUploadFailed(id: String, reason: String)
}
