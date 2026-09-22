// Real PulseSource backed by the Arduino-over-USB link. Replaces MockPulseSource behind the
// PulseSource interface; selected by HardwareModule when MOCK_HARDWARE == false.
//
// Phase 11: the sale's pulse count is `count − start`, where `count` is the adapter's lifetime
// count on each PULSE frame and `start` is the count the adapter latched when it opened the relay
// for this sale (RelayController.session, from the ARM acknowledgement). Both numbers are the
// adapter's own, so nothing falls between them. That retires #36: before, each collection built a
// fresh cumulative→delta accumulator whose first frame counted as zero — and that frame's count
// already included every pulse since the relay opened, so a few were lost per sale and ~22 per
// restart. The single physical read loop lives in UsbSerialConnection; this layer only maps its
// frames onto the sale.
package app.balancee.smartpump.display.data.hardware

import app.balancee.smartpump.display.data.hardware.serial.SerialFrame
import app.balancee.smartpump.display.domain.hardware.PulseSource
import app.balancee.smartpump.display.domain.hardware.RelayController
import app.balancee.smartpump.display.domain.model.PulseMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbSerialPulseSource @Inject constructor(
    private val link: SerialLink,
    private val relay: RelayController,
) : PulseSource {

    /**
     * Delegated straight to the link, which tracks it in the always-running read loop.
     * Deliberately NOT tracked in [observe]: that flow is cold and only collected during a
     * dispense, whereas the count is most needed when nothing is dispensing at all.
     */
    override val adapterCount: StateFlow<Long?> = link.adapterCount

    override suspend fun awaitAdapterCount(timeoutMs: Long): Long? {
        link.ensureStarted()
        // The adapter volunteers its count in the ~2 s keep-alive, so this normally returns well
        // inside the timeout. It can still time out for honest reasons — no board attached, USB
        // permission not yet granted, a dead cable — and null then means "unknown", which the
        // caller must not round down to zero.
        return withTimeoutOrNull(timeoutMs) { adapterCount.filterNotNull().first() }
    }

    override fun observe(): Flow<PulseMessage> = channelFlow {
        link.ensureStarted()

        // Surface a dropped cable as PulseMessage.Disconnected (only on a real down-transition).
        launch {
            var wasUp = link.connected.value
            link.connected.collect { up ->
                if (wasUp && !up) send(PulseMessage.Disconnected)
                wasUp = up
            }
        }

        link.frames.collect { frame ->
            when (frame) {
                is SerialFrame.Pulse -> salePulses(frame.cumulative)?.let { send(PulseMessage.Pulse(it)) }
                is SerialFrame.Heartbeat -> send(PulseMessage.Heartbeat())
                // The adapter's count and session come back through the relay controller (which
                // resumes on BOOT) — neither is fuel in itself.
                is SerialFrame.Boot, is SerialFrame.Arm -> Unit
                is SerialFrame.Stop -> {
                    val session = relay.session.value
                    if (session != null && frame.tag == session.tag) {
                        send(PulseMessage.Stopped(toSaleCount(frame.cut - session.start)))
                    }
                }
                is SerialFrame.Error ->
                    if (frame.code == UsbSerialRelayController.NOSESSION) send(PulseMessage.SessionLost)
                    else send(PulseMessage.ParseError("ERR:${frame.code}"))
                is SerialFrame.Invalid -> send(PulseMessage.ParseError(frame.raw))
            }
        }
        // frames is a never-completing SharedFlow, so the collect above suspends until this
        // collection is cancelled (the VM cancels its dispenseJob). The link stays open for the
        // next dispense.
    }

    /**
     * The sale's pulses at lifetime count [cumulative], or null when there is no session to count
     * against, or the count is below the session's start (the adapter rebooted and restarted from
     * an older total — the relay controller is already resuming or reporting the session lost).
     */
    private fun salePulses(cumulative: Long): Int? {
        val session = relay.session.value ?: return null
        val pulses = cumulative - session.start
        return if (pulses < 0) null else toSaleCount(pulses)
    }

    private fun toSaleCount(pulses: Long): Int = pulses.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
