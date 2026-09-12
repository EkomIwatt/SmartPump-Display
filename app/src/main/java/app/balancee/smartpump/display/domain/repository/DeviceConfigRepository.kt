// Gateway for reading and writing device configuration (price, station, virtual account).
package app.balancee.smartpump.display.domain.repository

import app.balancee.smartpump.display.domain.model.DeviceConfig
import kotlinx.coroutines.flow.Flow

interface DeviceConfigRepository {

    /**
     * Returns the stored [DeviceConfig], or null if the operator hasn't pushed one yet.
     * A null config must block all transactions — the customer sees
     * [app.balancee.smartpump.display.domain.usecase.CanStartTransactionUseCase.CUSTOMER_MESSAGE],
     * which is the single wording for this condition (OQ #17).
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
