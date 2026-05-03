// DAO for the single-row session state table. Replace-on-write pattern.
package app.balancee.smartpump.display.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.balancee.smartpump.display.data.db.entity.SessionStateEntity

@Dao
interface SessionStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(state: SessionStateEntity)

    @Query("SELECT * FROM session_state WHERE id = 1")
    suspend fun get(): SessionStateEntity?
}
