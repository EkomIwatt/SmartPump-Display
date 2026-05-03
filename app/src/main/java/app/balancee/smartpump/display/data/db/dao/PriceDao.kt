// DAO for the single-row fuel price table. Replace-on-write pattern.
package app.balancee.smartpump.display.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.balancee.smartpump.display.data.db.entity.PriceEntity

@Dao
interface PriceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(price: PriceEntity)

    @Query("SELECT * FROM fuel_price WHERE id = 1")
    suspend fun get(): PriceEntity?
}
