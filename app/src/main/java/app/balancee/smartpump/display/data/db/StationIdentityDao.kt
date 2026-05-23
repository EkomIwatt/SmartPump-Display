// DAO for the single-row station_identity table.
package app.balancee.smartpump.display.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.balancee.smartpump.display.data.db.entities.StationIdentityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StationIdentityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(identity: StationIdentityEntity)

    @Query("SELECT * FROM station_identity WHERE id = 1")
    suspend fun get(): StationIdentityEntity?

    @Query("SELECT * FROM station_identity WHERE id = 1")
    fun observe(): Flow<StationIdentityEntity?>

    @Query("SELECT EXISTS(SELECT 1 FROM station_identity WHERE id = 1)")
    suspend fun exists(): Boolean

    @Query("DELETE FROM station_identity WHERE id = 1")
    suspend fun delete()
}
