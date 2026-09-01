// Room-backed implementation of DeviceConfigRepository.
package app.balancee.smartpump.display.data.repository

import app.balancee.smartpump.display.data.db.DeviceConfigDao
import app.balancee.smartpump.display.data.db.entities.DeviceConfigEntity
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.repository.DeviceConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceConfigRepositoryImpl @Inject constructor(
    private val dao: DeviceConfigDao,
) : DeviceConfigRepository {

    override suspend fun getConfig(): DeviceConfig? = dao.get()?.toDomain()

    override suspend fun saveConfig(config: DeviceConfig) = dao.save(config.toEntity())

    override fun observeConfig(): Flow<DeviceConfig?> = dao.observe().map { it?.toDomain() }

    private fun DeviceConfigEntity.toDomain() = DeviceConfig(
        pumpLabel = pumpLabel,
        stationName = stationName,
        koboPerLitre = koboPerLitre,
        virtualAccountNumber = virtualAccountNumber,
        updatedAt = updatedAt,
    )

    private fun DeviceConfig.toEntity() = DeviceConfigEntity(
        pumpLabel = pumpLabel,
        stationName = stationName,
        koboPerLitre = koboPerLitre,
        virtualAccountNumber = virtualAccountNumber,
        updatedAt = updatedAt,
    )
}
