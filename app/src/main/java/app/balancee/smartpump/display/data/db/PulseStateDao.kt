// DAO for the single-row pulse_state table. Replace-on-write pattern.
package app.balancee.smartpump.display.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.balancee.smartpump.display.data.db.entities.PulseStateEntity

@Dao
interface PulseStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(state: PulseStateEntity)

    @Query("SELECT * FROM pulse_state WHERE id = 1")
    suspend fun get(): PulseStateEntity?
}
