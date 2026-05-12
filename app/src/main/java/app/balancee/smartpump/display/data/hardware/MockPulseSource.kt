// Debug-build pulse source. Emits synthetic Pulse events at a configurable rate while
// the relay is open, plus a periodic Heartbeat regardless. Pulse count resets on each
// open→close→open cycle so each transaction starts at zero, matching real Arduino behavior.
//
// Defaults are tuned to a typical fuel meter (~50 pulses/sec ≈ 30 L/min at 100 ppl).
// The debug screen (Phase 5) tweaks `pulsesPerSecond` and may inject failures via
// `injectDisconnect()` / `injectParseError()`.
package app.balancee.smartpump.display.data.hardware

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

    fun setPulsesPerSecond(value: Int) {
        _pulsesPerSecond.value = value.coerceIn(MIN_PPS, MAX_PPS)
    }

    fun setTankCapacityLitres(value: Double) {
        _tankCapacityLitres.value = value.coerceIn(0.5, MAX_TANK_CAPACITY_LITRES)
    }

    /** Debug-only: inject a synthetic disconnect event into the next observe() collection. */
    fun injectDisconnect() {
        injections.trySend(PulseMessage.Disconnected)
    }

    /** Debug-only: inject a parse error so the state machine can exercise its error path. */
    fun injectParseError(raw: String = "GARBAGE") {
        injections.trySend(PulseMessage.ParseError(raw))
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
        const val PULSES_PER_LITRE = 100      // mirrors the VM constant for hardware contract
        const val DEFAULT_TANK_CAPACITY_LITRES = 60.0
        const val MAX_TANK_CAPACITY_LITRES = 500.0
    }
}
