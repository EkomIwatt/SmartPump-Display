// DAO for the events log. Insert-only — rows are never deleted locally, like the audit log.
package app.balancee.smartpump.display.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.balancee.smartpump.display.data.db.entities.EventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    @Insert
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events ORDER BY createdAtMs DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE type = :type ORDER BY createdAtMs DESC LIMIT :limit")
    fun getRecentOfType(type: String, limit: Int): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE syncedAt IS NULL ORDER BY createdAtMs ASC")
    suspend fun getPendingSync(): List<EventEntity>
}
