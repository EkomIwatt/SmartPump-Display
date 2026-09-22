// Debug-build relay AND simulated adapter board. Phase 11 moved the cutoff onto the board, so a mock
// that only held a dispensing flag would let debug builds skip the path the rig takes. This one
// holds what the real firmware holds — a lifetime pulse count and one tagged session with a limit
// — and applies the same rules (docs/serial-protocol.md §4): a same-tag arm is a resume, a held
// session resumes under its original limit, a finished one never re-opens, and the pulse that
// reaches the limit cuts the relay. MockPulseSource drives each synthetic pulse through
// [countPulse]. No GPIO touched.
package app.balancee.smartpump.display.data.hardware

import android.util.Log
import app.balancee.smartpump.display.domain.hardware.AdapterSession
import app.balancee.smartpump.display.domain.hardware.MAX_LIMIT_PULSES
import app.balancee.smartpump.display.domain.hardware.RelayController
import app.balancee.smartpump.display.domain.hardware.SessionReply
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockRelayController @Inject constructor() : RelayController {

    private val _isDispensing = MutableStateFlow(false)
    override val isDispensing: StateFlow<Boolean> = _isDispensing.asStateFlow()

    private val _session = MutableStateFlow<AdapterSession?>(null)
    override val session: StateFlow<AdapterSession?> = _session.asStateFlow()

    private val _lifetimeCount = MutableStateFlow<Long?>(0L)
    /**
     * The simulated board's free-running lifetime count. Starts at 0 rather than null because the
     * simulated adapter is always "attached". Rebuilt on every app start, so it returns to 0 —
     * which a reader correctly interprets as "the adapter restarted too".
     */
    val lifetimeCount: StateFlow<Long?> = _lifetimeCount.asStateFlow()

    private enum class State { OPEN, HELD, DONE }

    // The board's session. Guarded by `this`.
    private var boardTag = 0L
    private var boardStart = 0L
    private var boardLimit = 0L
    private var boardState: State? = null

    /** Outcome of one synthetic pulse: the sale's pulses after it, and whether it hit the limit. */
    data class PulseOutcome(val salePulses: Int, val cut: Boolean)

    override suspend fun startFuelFlow(limitPulses: Long, tag: Long): SessionReply = synchronized(this) {
        if (limitPulses !in 1..MAX_LIMIT_PULSES || tag !in 1..U32_MAX) return SessionReply.Refused("CMD")
        if (boardState != null && boardTag == tag) return resumeLocked(tag)   // rule 1
        boardTag = tag
        boardStart = count()
        boardLimit = limitPulses
        boardState = State.OPEN
        open()
        SessionReply.Armed(AdapterSession(tag, boardStart)).also { Log.i(TAG, "ARMED $tag for $limitPulses pulses") }
    }

    override suspend fun resumeFuelFlow(tag: Long): SessionReply = synchronized(this) { resumeLocked(tag) }

    override suspend fun querySession(): SessionReply = synchronized(this) {
        when (boardState) {
            null -> SessionReply.NoSession
            State.DONE -> SessionReply.Stopped(boardTag, boardStart + boardLimit)
            else -> SessionReply.Armed(AdapterSession(boardTag, boardStart))
        }
    }

    override suspend fun stopFuelFlow() {
        synchronized(this) {
            if (boardState == State.OPEN) boardState = State.HELD
            if (_isDispensing.compareAndSet(expect = true, update = false)) Log.i(TAG, "RELAY DE-ENERGISED — fuel stopped")
        }
    }

    /**
     * One synthetic pulse through the board: count it and, if a session is open, apply its limit.
     * Returns null when no session is open — pulses with the relay off belong to no sale.
     */
    fun countPulse(): PulseOutcome? = synchronized(this) {
        val now = count() + 1
        _lifetimeCount.value = now
        if (boardState != State.OPEN) return null
        val sale = now - boardStart
        val cut = sale >= boardLimit
        if (cut) {
            boardState = State.DONE
            _isDispensing.value = false
            Log.i(TAG, "LIMIT REACHED — board cut sale $boardTag at $sale pulses")
        }
        PulseOutcome(sale.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), cut)
    }

    /** The open sale's pulses so far, or 0 with no open session. */
    fun salePulses(): Int = synchronized(this) {
        if (boardState != State.OPEN) 0 else (count() - boardStart).toInt()
    }

    /**
     * Debug-only: advance the lifetime count WITHOUT a sale seeing it — fuel that flowed while the
     * app was not watching. How the pulse-gap recovery path gets exercised without an Arduino.
     */
    fun addUnwatchedPulses(pulses: Int) {
        synchronized(this) { _lifetimeCount.value = count() + pulses.coerceAtLeast(0) }
    }

    private fun resumeLocked(tag: Long): SessionReply = when {
        boardState == null || boardTag != tag -> SessionReply.NoSession
        boardState == State.DONE -> SessionReply.Stopped(tag, boardStart + boardLimit)
        boardState == State.HELD && count() - boardStart >= boardLimit -> {
            boardState = State.DONE
            SessionReply.Stopped(tag, boardStart + boardLimit)
        }
        else -> {
            boardState = State.OPEN
            open()
            SessionReply.Armed(AdapterSession(tag, boardStart))
        }
    }

    private fun open() {
        _session.value = AdapterSession(boardTag, boardStart)
        if (_isDispensing.compareAndSet(expect = false, update = true)) Log.i(TAG, "RELAY ENERGISED — fuel flowing")
    }

    private fun count(): Long = _lifetimeCount.value ?: 0L

    private companion object {
        const val TAG = "MockRelay"
        const val U32_MAX = 0xFFFF_FFFFL
    }
}
