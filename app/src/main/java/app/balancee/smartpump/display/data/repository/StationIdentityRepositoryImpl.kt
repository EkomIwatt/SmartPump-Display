// Room-backed implementation of StationIdentityRepository.
// PIN hashing lives in [PinHasher]; this class is the data-layer wrapper that decides
// when to mint a fresh salt vs. reuse the existing one (PIN change keeps the salt; full
// re-provision generates a new one).
package app.balancee.smartpump.display.data.repository

import app.balancee.smartpump.display.data.db.StationIdentityDao
import app.balancee.smartpump.display.data.db.entities.StationIdentityEntity
import app.balancee.smartpump.display.domain.model.StationIdentity
import app.balancee.smartpump.display.domain.repository.StationIdentityRepository
import app.balancee.smartpump.display.domain.security.PinHasher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StationIdentityRepositoryImpl @Inject constructor(
    private val dao: StationIdentityDao,
) : StationIdentityRepository {

    override fun observeIdentity(): Flow<StationIdentity?> =
        dao.observe().map { it?.toDomain() }

    override suspend fun isProvisioned(): Boolean = dao.exists()

    override suspend fun getIdentity(): StationIdentity? = dao.get()?.toDomain()

    override suspend fun provision(
        stationId: String,
        displayName: String,
        logoBytes: ByteArray?,
        rawPin: String,
    ) {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash(rawPin, salt)
        dao.save(
            StationIdentityEntity(
                stationId = stationId,
                displayName = displayName,
                logoBytes = logoBytes,
                pinHash = hash,
                pinSalt = salt,
                setupAtMs = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun verifyPin(rawPin: String): Boolean {
        val row = dao.get() ?: return false
        return PinHasher.verify(rawPin, row.pinSalt, row.pinHash)
    }

    override suspend fun updatePin(oldRawPin: String, newRawPin: String): Boolean {
        val row = dao.get() ?: return false
        if (!PinHasher.verify(oldRawPin, row.pinSalt, row.pinHash)) return false
        // Keep the same salt — a PIN rotation does not need a new device-scoped salt.
        val newHash = PinHasher.hash(newRawPin, row.pinSalt)
        dao.save(row.copy(pinHash = newHash))
        return true
    }

    override suspend fun reset() = dao.delete()

    private fun StationIdentityEntity.toDomain() = StationIdentity(
        stationId = stationId,
        displayName = displayName,
        logoBytes = logoBytes,
        pinHash = pinHash,
        pinSalt = pinSalt,
        setupAtMs = setupAtMs,
    )
}
