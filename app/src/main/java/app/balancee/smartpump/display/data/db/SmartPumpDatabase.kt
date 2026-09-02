// Room database — single instance provided by Hilt.
// Four tables: transactions (audit log), device_config (operator settings),
// pulse_state (recovery), station_identity (install-time identity + PIN, Phase 5c).
package app.balancee.smartpump.display.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import app.balancee.smartpump.display.data.db.entities.DeviceConfigEntity
import app.balancee.smartpump.display.data.db.entities.PulseStateEntity
import app.balancee.smartpump.display.data.db.entities.StationIdentityEntity
import app.balancee.smartpump.display.data.db.entities.TransactionEntity

@Database(
    entities = [
        TransactionEntity::class,
        DeviceConfigEntity::class,
        PulseStateEntity::class,
        StationIdentityEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class SmartPumpDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun deviceConfigDao(): DeviceConfigDao
    abstract fun pulseStateDao(): PulseStateDao
    abstract fun stationIdentityDao(): StationIdentityDao
}
