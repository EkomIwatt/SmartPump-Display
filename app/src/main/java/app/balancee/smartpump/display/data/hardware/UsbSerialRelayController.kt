// Real RelayController — drives the Arduino relay pin over the shared USB-serial link (Phase 7a).
// Replaces MockRelayController behind the RelayController interface; selected by HardwareModule
// when MOCK_HARDWARE == false.
//
// Outbound framing mirrors the inbound protocol: RLY:1*<cs> (fuel on) / RLY:0*<cs> (fuel off),
// <cs> = XOR-8 of the bytes before '*' (same as SerialFrameParser). isDispensing is set
// optimistically on a successful write; stopFuelFlow() always clears it so a failed write can
// never leave the app believing fuel is still authorised. The firmware also defaults its relay
// pin OFF on boot, upholding the spec's relay-open-on-boot invariant from the hardware side.
//
// 7a-hardening — relay keepalive: the firmware runs a dead-man watchdog (RELAY_DEADMAN_MS) and
// closes the relay unless it keeps hearing RLY:1. So while dispensing we re-assert RLY:1 every
// KEEPALIVE_MS. Two payoffs: (1) a frozen app / yanked cable makes the keepalive stop, the board
// fails the relay closed — fuel can't run on uncontrolled; (2) after a brief unplug the same
// keepalive re-energises the relay the moment the port reopens, so a reconnect self-heals without
// the VM having to re-issue startFuelFlow(). All relay writes funnel through one Mutex so the
// keepalive can never race a start/stop write on the single physical port.
package app.balancee.smartpump.display.data.hardware

import android.util.Log
import app.balancee.smartpump.display.data.hardware.serial.SerialFrameParser
import app.balancee.smartpump.display.domain.hardware.RelayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbSerialRelayController @Inject constructor(
    private val connection: UsbSerialConnection,
) : RelayController {

    private val _isDispensing = MutableStateFlow(false)
    override val isDispensing: StateFlow<Boolean> = _isDispensing.asStateFlow()

    // App-lifetime scope owning the keepalive loop. Singleton, so this lives as long as the process.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private var keepaliveJob: Job? = null

    override suspend fun startFuelFlow() {
        if (_isDispensing.value) return
        connection.ensureStarted()
        val ok = writeRelay(on = true)
        if (ok) {
            _isDispensing.value = true
            Log.i(TAG, "RELAY ON — fuel flowing")
            startKeepalive()
        } else {
            Log.e(TAG, "RELAY ON write failed — link down; fuel NOT authorised")
        }
    }

    override suspend fun stopFuelFlow() {
        // Always clear local state even if the write fails — never strand the app thinking fuel
        // is still flowing. A retry can't make things less safe than "believed stopped".
        val wasDispensing = _isDispensing.value
        _isDispensing.value = false   // gate the keepalive off before we cancel it
        keepaliveJob?.cancel()
        keepaliveJob = null
        val ok = writeRelay(on = false)
        if (wasDispensing) {
            Log.i(TAG, if (ok) "RELAY OFF — fuel stopped" else "RELAY OFF write failed (link down)")
        }
    }

    /**
     * Re-assert RLY:1 every [KEEPALIVE_MS] while dispensing. This is what actually keeps the
     * firmware relay open past its dead-man window, and what re-energises it after a reconnect.
     * Write failures are expected while the cable is out — we keep looping and never touch
     * isDispensing here (only stopFuelFlow() clears it), so an out-and-back cable self-heals.
     */
    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (isActive && _isDispensing.value) {
                delay(KEEPALIVE_MS)
                if (_isDispensing.value) writeRelay(on = true)
            }
        }
    }

    private suspend fun writeRelay(on: Boolean): Boolean = writeMutex.withLock {
        withContext(Dispatchers.IO) { connection.writeLine(relayFrame(on)) }
    }

    private fun relayFrame(on: Boolean): String {
        val body = "RLY:${if (on) 1 else 0}"
        return "%s*%02X".format(body, SerialFrameParser.xor8(body))
    }

    private companion object {
        const val TAG = "UsbSerialRelay"

        /**
         * Keepalive cadence — ≈3 fit inside the firmware's RELAY_DEADMAN_MS (2 s), so losing two
         * in a row still doesn't false-trip the watchdog, but a real silence trips it well under
         * the 3 s fill-up shutoff window.
         */
        const val KEEPALIVE_MS = 700L
    }
}
