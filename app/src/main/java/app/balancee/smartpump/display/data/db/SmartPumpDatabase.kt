// Room database — single instance provided by Hilt.
// Three tables: transactions (audit log), device_config (operator settings), pulse_state (recovery).
package app.balancee.smartpump.display.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import app.balancee.smartpump.display.data.db.entities.DeviceConfigEntity
import app.balancee.smartpump.display.data.db.entities.PulseStateEntity
import app.balancee.smartpump.display.data.db.entities.TransactionEntity

@Database(
    entities = [
        TransactionEntity::class,
        DeviceConfigEntity::class,
        PulseStateEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class SmartPumpDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun deviceConfigDao(): DeviceConfigDao
    abstract fun pulseStateDao(): PulseStateDao
}
