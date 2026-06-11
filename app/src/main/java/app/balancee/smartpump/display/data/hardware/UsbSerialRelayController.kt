// Real RelayController — drives the Arduino relay pin over the shared USB-serial link (Phase 7a).
// Replaces MockRelayController behind the RelayController interface; selected by HardwareModule
// when MOCK_HARDWARE == false.
//
// Outbound framing mirrors the inbound protocol: RLY:1*<cs> (fuel on) / RLY:0*<cs> (fuel off),
// <cs> = XOR-8 of the bytes before '*' (same as SerialFrameParser). isDispensing is set
// optimistically on a successful write; stopFuelFlow() always clears it so a failed write can
// never leave the app believing fuel is still authorised. The firmware also defaults its relay
// pin OFF on boot, upholding the spec's relay-open-on-boot invariant from the hardware side.
package app.balancee.smartpump.display.data.hardware

import android.util.Log
import app.balancee.smartpump.display.data.hardware.serial.SerialFrameParser
import app.balancee.smartpump.display.domain.hardware.RelayController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbSerialRelayController @Inject constructor(
    private val connection: UsbSerialConnection,
) : RelayController {

    private val _isDispensing = MutableStateFlow(false)
    override val isDispensing: StateFlow<Boolean> = _isDispensing.asStateFlow()

    override suspend fun startFuelFlow() {
        if (_isDispensing.value) return
        connection.ensureStarted()
        val ok = withContext(Dispatchers.IO) { connection.writeLine(relayFrame(on = true)) }
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
        val ok = withContext(Dispatchers.IO) { connection.writeLine(relayFrame(on = false)) }
        if (wasDispensing) {
            Log.i(TAG, if (ok) "RELAY OFF — fuel stopped" else "RELAY OFF write failed (link down)")
        }
    }

    private fun relayFrame(on: Boolean): String {
        val body = "RLY:${if (on) 1 else 0}"
        return "%s*%02X".format(body, SerialFrameParser.xor8(body))
    }

    private companion object {
        const val TAG = "UsbSerialRelay"
    }
}
