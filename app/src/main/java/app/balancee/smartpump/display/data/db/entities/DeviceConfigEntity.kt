// Single-row table (id = 1) caching the device config pushed from the operator backend.
package app.balancee.smartpump.display.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_config")
data class DeviceConfigEntity(
    @PrimaryKey val id: Int = 1,
    /**
     * Screen caption for this nozzle, e.g. "PUMP 1" — display only, unrelated to the API's
     * `pumpId` UUID on [app.balancee.smartpump.display.domain.network.PumpCredentials] (TODO #13).
     * The column keeps its original name so the rename needs no Room migration.
     */
    @ColumnInfo(name = "pumpId") val pumpLabel: String,
    val stationName: String,
    /** Fuel price in kobo (100 kobo = ₦1). e.g. 87_000 = ₦870/L. */
    val koboPerLitre: Long,
    /** NIP bank-transfer account for post-fill-up QR generation. Null until operator sets it. */
    val virtualAccountNumber: String?,
    val updatedAt: Long,
)
