// Real RelayController — drives the Arduino relay pin over the shared USB-serial link (Phase 7a).
// Replaces MockRelayController behind the RelayController interface; selected by HardwareModule
// when MOCK_HARDWARE == false.
//
// Outbound framing mirrors the inbound protocol: RLY:1*<cs> (fuel on) / RLY:0*<cs> (fuel off),
// <cs> = XOR-8 of the bytes before '*' (same as SerialFrameParser). RLY:1/RLY:0 are one-shot edge
// commands — liveness is carried separately by the link-layer PING heartbeat (UsbSerialConnection),
// which the Arduino's comms-loss watchdog keys on. isDispensing is set optimistically on a
// successful write; stopFuelFlow() always clears it so a failed write can never leave the app
// believing fuel is still authorised. The firmware also defaults its relay pin OFF on boot.
//
// 7a-hardening — reconnect re-assert: the Arduino's heartbeat watchdog fails the relay OPEN during
// a comms outage and, being fail-safe, never re-energises on its own. So when the link returns we
// re-command RLY:1 if we still intend to be dispensing — that is what resumes a prepaid fill after a
// brief USB drop. We watch UsbSerialConnection.connected for the down→up edge.
package app.balancee.smartpump.display.data.hardware

import android.util.Log
import app.balancee.smartpump.display.data.hardware.serial.SerialFrameParser
import app.balancee.smartpump.display.domain.hardware.RelayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbSerialRelayController @Inject constructor(
    private val connection: UsbSerialConnection,
) : RelayController {

    private val _isDispensing = MutableStateFlow(false)
    override val isDispensing: StateFlow<Boolean> = _isDispensing.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Re-assert the relay when the link comes back: the adapter watchdog failed it OPEN during
        // the outage and won't re-energise on its own (fail-safe). If we still intend to dispense,
        // re-command RLY:1 so the prepaid fill resumes.
        scope.launch {
            var wasUp = connection.connected.value
            connection.connected.collect { up ->
                if (up && !wasUp && _isDispensing.value) {
                    val ok = writeRelay(on = true)
                    Log.i(TAG, if (ok) "Link back — re-asserted RELAY ON" else "Link back — RELAY ON re-assert failed")
                }
                wasUp = up
            }
        }
    }

    override suspend fun startFuelFlow() {
        if (_isDispensing.value) return
        connection.ensureStarted()
        val ok = writeRelay(on = true)
        if (ok) {
            _isDispensing.value = true
            Log.i(TAG, "RELAY ON — fuel flowing")
        } else {
            Log.e(TAG, "RELAY ON write failed — link down; fuel NOT authorised")
        }
    }

    override suspend fun stopFuelFlow() {
        // Always clear local state even if the write fails — never strand the app thinking fuel
        // is still flowing. A retry can't make things less safe than "believed stopped".
        val wasDispensing = _isDispensing.value
        _isDispensing.value = false
        val ok = writeRelay(on = false)
        if (wasDispensing) {
            Log.i(TAG, if (ok) "RELAY OFF — fuel stopped" else "RELAY OFF write failed (link down)")
        }
    }

    private suspend fun writeRelay(on: Boolean): Boolean =
        withContext(Dispatchers.IO) { connection.writeLine(relayFrame(on)) }

    private fun relayFrame(on: Boolean): String {
        val body = "RLY:${if (on) 1 else 0}"
        return "%s*%02X".format(body, SerialFrameParser.xor8(body))
    }

    private companion object {
        const val TAG = "UsbSerialRelay"
    }
}
