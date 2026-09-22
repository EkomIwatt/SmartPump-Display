// Debug-build pulse source. Emits synthetic Pulse events at a configurable rate while
// the relay is open, plus a periodic Heartbeat regardless. Pulse count resets on each
// open→close→open cycle so each transaction starts at zero, matching real Arduino behavior.
//
// Defaults are tuned to a typical fuel meter (~50 pulses/sec ≈ 30 L/min at 100 ppl).
// The debug screen (Phase 5) tweaks `pulsesPerSecond` and may inject failures via
// `injectDisconnect()` / `injectParseError()`.
package app.balancee.smartpump.display.data.hardware

import app.balancee.smartpump.display.domain.hardware.PULSES_PER_LITRE
import app.balancee.smartpump.display.domain.hardware.PulseSource
import app.balancee.smartpump.display.domain.hardware.RelayController
import app.balancee.smartpump.display.domain.model.PulseMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockPulseSource @Inject constructor(
    private val relay: RelayController,
) : PulseSource {

    private val _pulsesPerSecond = MutableStateFlow(DEFAULT_PPS)
    val pulsesPerSecond: StateFlow<Int> = _pulsesPerSecond.asStateFlow()

    /**
     * Simulated tank capacity in litres. Once the mock has emitted enough Pulse messages
     * to cover this, it stops emitting Pulse (Heartbeats still fire). Lets the customer-side
     * 3-second pulse-timeout watchdog actually fire during testing — real fuel hardware
     * has no "tank full" signal either; we infer shutoff from a flow gap.
     */
    private val _tankCapacityLitres = MutableStateFlow(DEFAULT_TANK_CAPACITY_LITRES)
    val tankCapacityLitres: StateFlow<Double> = _tankCapacityLitres.asStateFlow()

    // Out-of-band injection channel for debug-only failure simulation.
    private val injections = Channel<PulseMessage>(capacity = Channel.UNLIMITED)

    private val _adapterCount = MutableStateFlow<Long?>(0L)
    /**
     * Stands in for the real board's free-running lifetime counter: it advances with every
     * synthetic pulse and, unlike the per-transaction count, never resets on relay-open.
     *
     * Starts at 0 rather than null because the simulated adapter is always "attached" — there is
     * no cable to be missing. One faithful consequence worth knowing when testing recovery in the
     * simulator: this object is rebuilt on every app start, so the count returns to 0, which a
     * reader correctly interprets as "the adapter restarted too" and therefore declines to
     * attribute. To exercise the attributable path in a debug build, use [injectAdapterGap].
     */
    override val adapterCount: StateFlow<Long?> = _adapterCount.asStateFlow()

    override suspend fun awaitAdapterCount(timeoutMs: Long): Long? = _adapterCount.value

    fun setPulsesPerSecond(value: Int) {
        _pulsesPerSecond.value = value.coerceIn(MIN_PPS, MAX_PPS)
    }

    fun setTankCapacityLitres(value: Double) {
        _tankCapacityLitres.value = value.coerceIn(MIN_TANK_CAPACITY_LITRES, MAX_TANK_CAPACITY_LITRES)
    }

    /** Debug-only: inject a synthetic disconnect event into the next observe() collection. */
    fun injectDisconnect() {
        injections.trySend(PulseMessage.Disconnected)
    }

    /** Debug-only: inject a parse error so the state machine can exercise its error path. */
    fun injectParseError(raw: String = "GARBAGE") {
        injections.trySend(PulseMessage.ParseError(raw))
    }

    /**
     * Debug-only: advance the simulated adapter's lifetime counter WITHOUT emitting pulses —
     * i.e. fuel that flowed while the app was not watching. This is the one thing the simulator
     * cannot produce on its own (killing the app also resets this fake board), so it is how the
     * pulse-gap recovery path gets exercised without an Arduino on the bench.
     */
    fun injectAdapterGap(pulses: Int) {
        _adapterCount.value = (_adapterCount.value ?: 0L) + pulses.coerceAtLeast(0)
    }

    override fun observe(): Flow<PulseMessage> = flow {
        var count = 0
        var wasDispensing = false
        var lastHeartbeatMs = 0L

        while (currentCoroutineContext().isActive) {
            // Drain any debug-injected events first so they hit the consumer immediately.
            while (true) {
                val injected = injections.tryReceive().getOrNull() ?: break
                emit(injected)
            }

            val now = System.currentTimeMillis()
            val isDispensing = relay.isDispensing.value
            val rate = _pulsesPerSecond.value

            // Fresh transaction → reset count on each start-of-dispense transition.
            if (isDispensing && !wasDispensing) count = 0
            wasDispensing = isDispensing

            if (now - lastHeartbeatMs >= HEARTBEAT_INTERVAL_MS) {
                emit(PulseMessage.Heartbeat(now))
                lastHeartbeatMs = now
            }

            val capacityPulses = (_tankCapacityLitres.value * PULSES_PER_LITRE).toInt()
            val tankFull = capacityPulses > 0 && count >= capacityPulses
            if (isDispensing && rate > 0 && !tankFull) {
                count++
                // The session count resets per transaction; the adapter's does not.
                _adapterCount.value = (_adapterCount.value ?: 0L) + 1
                emit(PulseMessage.Pulse(count, now))
                delay(1_000L / rate)
            } else {
                delay(IDLE_POLL_MS)
            }
        }
    }

    private companion object {
        const val DEFAULT_PPS = 50
        const val MIN_PPS = 0
        const val MAX_PPS = 200
        const val HEARTBEAT_INTERVAL_MS = 5_000L
        const val IDLE_POLL_MS = 100L
        const val DEFAULT_TANK_CAPACITY_LITRES = 60.0
        /**
         * Floor for the simulated tank, lowered from 0.5 L for the 10g gate (2026-09-19).
         *
         * A fill-up fixes its amount *before* the QR appears — there is no amount-entry step to
         * keep it small — so on `debugProd`, which takes real payments, this number **is the
         * bill**. At ₦1,490/L the old floor was ₦745 of a real card, and the 60 L default is
         * ₦89,400. At 0.1 L it is about ₦149, which is what makes proving Flow 3 against
         * production affordable rather than a decision about money.
         *
         * The slider in `DebugScreen` carries the same floor; both have to move together, which
         * is why this one is named rather than a literal — changing only the slider looks like it
         * works and does nothing.
         */
        const val MIN_TANK_CAPACITY_LITRES = 0.1
        const val MAX_TANK_CAPACITY_LITRES = 500.0
    }
}
