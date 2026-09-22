// Gateway for persisting transaction state and pulse count across power cuts.
// Written on every state transition and pulse milestone so recovery is always possible.
package app.balancee.smartpump.display.domain.repository

import app.balancee.smartpump.display.domain.hardware.SaleSession
import app.balancee.smartpump.display.domain.model.TransactionState

interface PulseRepository {

    /**
     * Persist [state] to survive a power cut.
     * [transactionRef] is stored alongside (e.g. "BLC-00847") for UI display after recovery.
     */
    suspend fun saveTransactionState(state: TransactionState, transactionRef: String? = null)

    /**
     * Restore the last persisted state on app start.
     * Returns [TransactionState.Idle] if no state has been saved or the stored JSON is corrupt.
     */
    suspend fun restoreTransactionState(): TransactionState

    /**
     * Persist the running pulse count and the timestamp of the last received pulse.
     * Used to detect nozzle shutoff (3 s with no new pulse) after a power-cut recovery.
     *
     * [adapterCount] is the adapter's own free-running count at this instant, stored as the
     * anchor the Phase 7h reconciler subtracts from after a restart. It is a REQUIRED parameter
     * rather than a defaulted one so that no call site can quietly stop maintaining the anchor;
     * pass null only when the adapter's count is genuinely unknown, which stores null and makes
     * the reconciler decline to attribute rather than guess.
     */
    suspend fun savePulseCount(count: Int, lastPulseTimeMs: Long, adapterCount: Long?)

    /**
     * Commit a gap that boot resume has just reconciled: the corrected count, and the adapter
     * reading it was measured against as the new anchor.
     *
     * Separate from [savePulseCount] because the two differ in what they know. That one is called
     * from the dispensing loop and carries a fresh pulse timestamp; this one runs before any pulse
     * has arrived in the new process, so it must leave the stored timestamp alone rather than
     * overwrite it with an invented value.
     *
     * Why it exists at all: without it the recovered pulses live only in memory until the
     * dispensing loop's next checkpoint, 25 pulses later. An app that dies inside that window
     * reconciles a second time against the *same* anchor, and re-reports fuel the previous resume
     * already reported — two fuel-log rows that overlap instead of two that add up. The sale's
     * arithmetic stays right either way, because count and anchor are always read as a pair; it is
     * the operator-facing record of what went missing that comes apart. Found on the bench,
     * 2026-09-12, by restarting twice inside one dispense.
     *
     * [adapterCount] must be the exact reading the gap was computed from, never a fresh read —
     * re-reading would silently drop any pulses that landed in between.
     */
    suspend fun saveReconciledCount(count: Int, adapterCount: Long)

    /**
     * Restore the pulse count from before the power cut.
     * Returns 0 if nothing was saved.
     */
    suspend fun restorePulseCount(): Int

    /**
     * The adapter's free-running count as of the last persisted write, or null if none was
     * recorded (a pump updated from before Phase 7h, or a row written while the adapter was
     * silent). Null is not zero — see PulseStateEntity.adapterCount.
     */
    suspend fun restoreAdapterAnchor(): Long?

    /**
     * Persist the adapter session a sale is armed under, or clear it with null (Phase 11e).
     *
     * Must be written — and awaited — **before** the arm is sent: a crash between the two then
     * still leaves a tag to ask the adapter about, and the adapter's answer is what stops a
     * restart handing the sale a second allowance.
     */
    suspend fun saveSaleSession(session: SaleSession?)

    /** The last persisted sale session, or null. */
    suspend fun restoreSaleSession(): SaleSession?

    /** The transaction reference stored alongside the last saved state, or null. */
    suspend fun getActiveTransactionRef(): String?
}
