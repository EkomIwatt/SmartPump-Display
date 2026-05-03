// DAO for the single-row device_config table. Replace-on-write pattern.
package app.balancee.smartpump.display.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.balancee.smartpump.display.data.db.entities.DeviceConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceConfigDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(config: DeviceConfigEntity)

    @Query("SELECT * FROM device_config WHERE id = 1")
    suspend fun get(): DeviceConfigEntity?

    @Query("SELECT * FROM device_config WHERE id = 1")
    fun observe(): Flow<DeviceConfigEntity?>
}
