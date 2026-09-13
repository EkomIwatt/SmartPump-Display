// Real PulseSource backed by the Arduino-over-USB link (Phase 7a). Replaces MockPulseSource
// behind the PulseSource interface; selected by HardwareModule when MOCK_HARDWARE == false.
//
// observe() is a COLD flow per the interface contract — each collection (the VM starts one per
// dispense) gets its own session counter that resets to 0 on every relay-open, exactly like the
// mock. The VM then computes litres = pulseBaseline + msg.count. The single physical read loop
// lives in UsbSerialConnection; this layer just slices the shared frame stream into a
// session-relative pulse count via PulseAccumulator (cumulative → delta).
package app.balancee.smartpump.display.data.hardware

import app.balancee.smartpump.display.data.hardware.serial.PulseAccumulator
import app.balancee.smartpump.display.data.hardware.serial.SerialFrame
import app.balancee.smartpump.display.domain.hardware.PulseSource
import app.balancee.smartpump.display.domain.hardware.RelayController
import app.balancee.smartpump.display.domain.model.PulseMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbSerialPulseSource @Inject constructor(
    private val connection: UsbSerialConnection,
    private val relay: RelayController,
) : PulseSource {

    /**
     * Delegated straight to the connection, which tracks it in the always-running read loop.
     * Deliberately NOT tracked in [observe]: that flow is cold and only collected during a
     * dispense, whereas the count is most needed when nothing is dispensing at all.
     */
    override val adapterCount: StateFlow<Long?> = connection.adapterCount

    override suspend fun awaitAdapterCount(timeoutMs: Long): Long? {
        connection.ensureStarted()
        // The adapter volunteers its count in the ~2 s keep-alive, so this normally returns well
        // inside the timeout. It can still time out for honest reasons — no board attached, USB
        // permission not yet granted, a dead cable — and null then means "unknown", which the
        // caller must not round down to zero.
        return withTimeoutOrNull(timeoutMs) { adapterCount.filterNotNull().first() }
    }

    override fun observe(): Flow<PulseMessage> = channelFlow {
        connection.ensureStarted()

        val accumulator = PulseAccumulator()
        var sessionPulses = 0
        var wasDispensing = relay.isDispensing.value

        // Surface a dropped cable as PulseMessage.Disconnected (only on a real down-transition).
        launch {
            var wasUp = connection.connected.value
            connection.connected.collect { up ->
                if (wasUp && !up) send(PulseMessage.Disconnected)
                wasUp = up
            }
        }

        connection.frames.collect { frame ->
            // Reset the session count on each relay-open, mirroring the mock's count=0 reset.
            val dispensing = relay.isDispensing.value
            if (dispensing && !wasDispensing) {
                sessionPulses = 0
                accumulator.reset()
            }
            wasDispensing = dispensing

            when (frame) {
                is SerialFrame.Pulse -> {
                    sessionPulses += accumulator.onPulse(frame.cumulative)
                    send(PulseMessage.Pulse(sessionPulses))
                }
                is SerialFrame.Heartbeat -> send(PulseMessage.Heartbeat())
                is SerialFrame.Boot -> accumulator.onBoot(frame.cumulative) // re-baseline; not fuel
                is SerialFrame.Error -> send(PulseMessage.ParseError("ERR:${frame.code}"))
                is SerialFrame.Invalid -> send(PulseMessage.ParseError(frame.raw))
            }
        }
        // frames is a never-completing SharedFlow, so the collect above suspends until this
        // collection is cancelled (the VM cancels its dispenseJob). The connection stays open
        // for the next dispense.
    }
}
