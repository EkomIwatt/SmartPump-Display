// Gateway for reading and writing device configuration (price, station, virtual account).
package app.balancee.smartpump.display.domain.repository

import app.balancee.smartpump.display.domain.model.DeviceConfig
import kotlinx.coroutines.flow.Flow

interface DeviceConfigRepository {

    /**
     * Returns the stored [DeviceConfig], or null if the operator hasn't pushed one yet.
     * A null config must block all transactions with "Price not set — contact operator".
     */
    suspend fun getConfig(): DeviceConfig?

    /** Persist config pushed from the Balanceè operator app. */
    suspend fun saveConfig(config: DeviceConfig)

    /**
     * Live stream of the device config.
     * Emits a new value whenever the operator pushes an update.
     */
    fun observeConfig(): Flow<DeviceConfig?>
}
