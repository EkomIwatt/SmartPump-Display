// DAO for the single-row pulse_state table.
//
// **Every write names the columns it owns and touches no others** (TODO #R12). Until 2026-09-21
// this was a replace-on-write DAO: each repository method read the row, rebuilt the whole entity
// with its own fields changed, and REPLACEd it. Two writers own this row — the state writer and the
// pulse writer — and they run on separate coroutines, so a read by one could land before the other's
// write and its REPLACE would then put back a stale copy of the other's fields.
//
// Seen on the SM-T220: cancelling a digital fill-up ran `setState(Idle)` and the pulse clear
// together, the pulse clear read first and wrote last, and the row kept the cancelled sale's
// `fillup_digital_awaiting_payment` while the screen showed Idle. Boot resume trusts this row, so a
// restart would have resurrected a sale the attendant had cancelled.
//
// A column-scoped UPDATE carries nothing it did not come to write, so the order the two land in no
// longer matters. There is deliberately no full-row write left here to reach for.
package app.balancee.smartpump.display.data.db

import androidx.room.Dao
import androidx.room.Query
import app.balancee.smartpump.display.data.db.entities.PulseStateEntity

@Dao
interface PulseStateDao {

    @Query("SELECT * FROM pulse_state WHERE id = 1")
    suspend fun get(): PulseStateEntity?

    /**
     * Create the row if there is none, and never touch one that exists.
     *
     * Every writer calls this first, because an UPDATE against a missing row silently does
     * nothing. [idleJson] is what the row says before any state has been written — the same `Idle`
     * the previous full-row writes defaulted to.
     */
    @Query(
        "INSERT OR IGNORE INTO pulse_state " +
            "(id, transactionStateJson, currentTransactionRef, pulseCount, lastPulseTimeMs, adapterCount, updatedAt) " +
            "VALUES (1, :idleJson, NULL, 0, 0, NULL, :now)",
    )
    suspend fun ensureRow(idleJson: String, now: Long)

    /**
     * The state writer's columns. A null [transactionRef] keeps the stored one, which is what the
     * stateless states (Idle, ModeSelect) have always done.
     */
    @Query(
        "UPDATE pulse_state SET transactionStateJson = :stateJson, " +
            "currentTransactionRef = COALESCE(:transactionRef, currentTransactionRef), " +
            "updatedAt = :now WHERE id = 1",
    )
    suspend fun updateState(stateJson: String, transactionRef: String?, now: Long)

    /** The pulse writer's columns, including the adapter anchor it is written against. */
    @Query(
        "UPDATE pulse_state SET pulseCount = :count, lastPulseTimeMs = :lastPulseTimeMs, " +
            "adapterCount = :adapterCount, updatedAt = :now WHERE id = 1",
    )
    suspend fun updatePulses(count: Int, lastPulseTimeMs: Long, adapterCount: Long?, now: Long)

    /**
     * The reconciler's columns. `lastPulseTimeMs` is left alone on purpose — see
     * `PulseRepositoryImpl.saveReconciledCount`.
     */
    @Query(
        "UPDATE pulse_state SET pulseCount = :count, adapterCount = :adapterCount, " +
            "updatedAt = :now WHERE id = 1",
    )
    suspend fun updateReconciled(count: Int, adapterCount: Long, now: Long)

    /** The session writer's columns (Phase 11e). All null / 0 clears it. */
    @Query(
        "UPDATE pulse_state SET sessionTransactionRef = :transactionRef, sessionTag = :tag, " +
            "sessionBasePulses = :basePulses, updatedAt = :now WHERE id = 1",
    )
    suspend fun updateSession(transactionRef: String?, tag: Long?, basePulses: Int, now: Long)
}
