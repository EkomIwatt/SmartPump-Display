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
    /**
     * Which fuel this pump dispenses, stored as the `FuelType` enum name (the same string
     * /authorise expects). Null until set — added in schema v3, so every pre-existing row
     * migrates to null and is blocked by the transaction guard until someone configures it.
     */
    val fuelType: String?,
    /** NIP bank-transfer account for post-fill-up QR generation. Null until operator sets it. */
    val virtualAccountNumber: String?,
    val updatedAt: Long,
)
