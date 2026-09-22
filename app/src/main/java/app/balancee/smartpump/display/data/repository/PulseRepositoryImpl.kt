// Room-backed implementation of PulseRepository.
// Serialises TransactionState to JSON so the state machine survives power cuts.
//
// Two writers share the one row — state transitions and pulse counts, on separate coroutines — so
// each writes only its own columns (#R12). See PulseStateDao for what that replaced and why.
package app.balancee.smartpump.display.data.repository

import app.balancee.smartpump.display.data.db.PulseStateDao
import app.balancee.smartpump.display.domain.hardware.SaleSession
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

    /** What a freshly created row says before any state has been written to it. */
    private val idleJson: String by lazy { json.encodeToString<TransactionState>(TransactionState.Idle) }

    /**
     * Writes the state columns and nothing else (#R12). The pulse count and the adapter anchor
     * belong to the pulse writer; carrying a copy of them here is how a state write used to put
     * back a count the pulse writer had already moved past.
     */
    override suspend fun saveTransactionState(state: TransactionState, transactionRef: String?) {
        val now = System.currentTimeMillis()
        dao.ensureRow(idleJson, now)
        dao.updateState(json.encodeToString(state), transactionRef, now)
    }

    override suspend fun restoreTransactionState(): TransactionState {
        val entity = dao.get() ?: return TransactionState.Idle
        return runCatching {
            json.decodeFromString<TransactionState>(entity.transactionStateJson)
        }.getOrDefault(TransactionState.Idle)
    }

    /**
     * Writes the pulse columns and nothing else (#R12). It used to rebuild the whole row from a
     * read, so the pulse clear in `resetToIdle` — launched alongside `setState(Idle)` — could read
     * the old state and write it back after the Idle had landed. The cancelled sale then survived in
     * the one row boot resume trusts.
     */
    override suspend fun savePulseCount(count: Int, lastPulseTimeMs: Long, adapterCount: Long?) {
        val now = System.currentTimeMillis()
        dao.ensureRow(idleJson, now)
        dao.updatePulses(count, lastPulseTimeMs, adapterCount, now)
    }

    override suspend fun saveReconciledCount(count: Int, adapterCount: Long) {
        val now = System.currentTimeMillis()
        dao.ensureRow(idleJson, now)
        // `lastPulseTimeMs` is not written. No pulse has arrived in this process yet, so the last
        // one we genuinely saw is still the one the previous process recorded. The nozzle-shutoff
        // timer reads it, and moving it forward here would tell that timer fuel was flowing during
        // the outage, at a moment when the relay was shut.
        dao.updateReconciled(count, adapterCount, now)
    }

    override suspend fun saveSaleSession(session: SaleSession?) {
        val now = System.currentTimeMillis()
        dao.ensureRow(idleJson, now)
        dao.updateSession(session?.transactionRef, session?.tag, session?.basePulses ?: 0, now)
    }

    override suspend fun restoreSaleSession(): SaleSession? {
        val row = dao.get() ?: return null
        val ref = row.sessionTransactionRef ?: return null
        val tag = row.sessionTag ?: return null
        return SaleSession(ref, tag, row.sessionBasePulses)
    }

    override suspend fun restorePulseCount(): Int = dao.get()?.pulseCount ?: 0

    override suspend fun restoreAdapterAnchor(): Long? = dao.get()?.adapterCount

    override suspend fun getActiveTransactionRef(): String? = dao.get()?.currentTransactionRef
}
