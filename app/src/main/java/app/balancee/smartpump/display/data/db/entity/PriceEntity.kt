// Single-row table (id = 1) caching the fuel price pushed from the operator backend.
package app.balancee.smartpump.display.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "fuel_price")
data class PriceEntity(
    @PrimaryKey val id: Int = 1,
    /** Price in kobo (100 kobo = ₦1). e.g. ₦870/L stored as 87_000. */
    val koboPerLitre: Long,
    val updatedAt: Long,
)
