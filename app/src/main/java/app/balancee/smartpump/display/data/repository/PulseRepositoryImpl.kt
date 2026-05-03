// Room-backed implementation of PulseRepository.
// Serialises TransactionState to JSON so the state machine survives power cuts.
package app.balancee.smartpump.display.data.repository

import app.balancee.smartpump.display.data.db.PulseStateDao
import app.balancee.smartpump.display.data.db.entities.PulseStateEntity
import app.balancee.smartpump.display.domain.model.TransactionState
import app.balancee.smartpump.display.domain.repository.PulseRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PulseRepositoryImpl @Inject constructor(
    private val dao: PulseStateDao,
) : PulseRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun saveTransactionState(state: TransactionState, transactionRef: String?) {
        val existing = dao.get()
        dao.save(
            PulseStateEntity(
                transactionStateJson = json.encodeToString(state),
                currentTransactionRef = transactionRef ?: existing?.currentTransactionRef,
                pulseCount = existing?.pulseCount ?: 0,
                lastPulseTimeMs = existing?.lastPulseTimeMs ?: 0L,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun restoreTransactionState(): TransactionState {
        val entity = dao.get() ?: return TransactionState.Idle
        return runCatching {
            json.decodeFromString<TransactionState>(entity.transactionStateJson)
        }.getOrDefault(TransactionState.Idle)
    }

    override suspend fun savePulseCount(count: Int, lastPulseTimeMs: Long) {
        val existing = dao.get()
        dao.save(
            PulseStateEntity(
                transactionStateJson = existing?.transactionStateJson
                    ?: json.encodeToString<TransactionState>(TransactionState.Idle),
                currentTransactionRef = existing?.currentTransactionRef,
                pulseCount = count,
                lastPulseTimeMs = lastPulseTimeMs,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun restorePulseCount(): Int = dao.get()?.pulseCount ?: 0

    override suspend fun getActiveTransactionRef(): String? = dao.get()?.currentTransactionRef
}
