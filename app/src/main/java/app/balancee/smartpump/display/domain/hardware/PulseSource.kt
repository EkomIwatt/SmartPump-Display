// Hardware abstraction over the Arduino-via-USB pulse stream.
// Real impl (Phase 7+) parses raw "PULSE:XXXX\n" frames; mock impl emits synthetic events.
package app.balancee.smartpump.display.domain.hardware

import app.balancee.smartpump.display.domain.model.PulseMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface PulseSource {

    /**
     * Cold flow of [PulseMessage] events from the pulse adapter.
     * Collection should run for the lifetime of the app — the state machine
     * filters Pulse vs Heartbeat vs Disconnected based on current TransactionState.
     */
    fun observe(): Flow<PulseMessage>

    /**
     * The adapter's own FREE-RUNNING pulse count, as last reported — the lifetime total the board
     * has counted since it powered up, NOT the per-transaction count carried by
     * [PulseMessage.Pulse]. Null means the adapter has not reported since the app last lost
     * contact with it, so its count is genuinely unknown.
     *
     * Null is never zero. Zero is a real reading from a board that just booted, so anything
     * reading this must treat null as "cannot say" and decline to compute a gap from it.
     *
     * This exists because the adapter keeps counting while the app is not watching — an app crash,
     * a low-memory kill, an OS update reboot — and those litres reach the customer. Persisting
     * this value alongside the transaction count is what makes the shortfall measurable on the
     * next start (OPEN_QUESTIONS #25).
     */
    val adapterCount: StateFlow<Long?>

    /**
     * Suspend until [adapterCount] has a value, bringing the link up if it is not already, and
     * give up after [timeoutMs]. Returns null on timeout — which callers must treat as "unknown",
     * never as zero.
     *
     * Needed because boot resume runs before any dispense is collecting: the real adapter
     * volunteers its count in the ~2 s keep-alive frame, so the answer usually arrives quickly,
     * but it must never be waited on indefinitely with a customer watching an empty screen.
     */
    suspend fun awaitAdapterCount(timeoutMs: Long): Long?
}
