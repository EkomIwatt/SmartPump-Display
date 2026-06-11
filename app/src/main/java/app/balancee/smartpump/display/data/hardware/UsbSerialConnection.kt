// Single owner of the physical USB-serial link to the Arduino pulse adapter (Phase 7a).
//
// One port, one read loop. It buffers incoming bytes into '\n'-delimited lines, runs each
// through SerialFrameParser, and republishes the typed frames on a hot SharedFlow that any
// number of collectors (the cold UsbSerialPulseSource flows) can observe. Outbound relay
// commands are written through writeLine(). Connection liveness is exposed as a StateFlow so
// the pulse source can surface PulseMessage.Disconnected.
//
// Permission: a kiosk normally grants access persistently via the USB_DEVICE_ATTACHED
// intent-filter (see AndroidManifest + res/xml/usb_device_filter.xml). requestPermission() is
// the runtime fallback when the device is already attached without a standing grant.
package app.balancee.smartpump.display.data.hardware

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import app.balancee.smartpump.display.data.hardware.serial.SerialFrame
import app.balancee.smartpump.display.data.hardware.serial.SerialFrameParser
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbSerialConnection @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _frames = MutableSharedFlow<SerialFrame>(extraBufferCapacity = 256)
    /** Hot stream of parsed frames. No replay — pulses only matter while a collector is active. */
    val frames: SharedFlow<SerialFrame> = _frames.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    /** True while a port is open. Pulse source maps true→false to PulseMessage.Disconnected. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var ioManager: SerialInputOutputManager? = null
    @Volatile private var running = false
    private var receiversRegistered = false

    // Read-thread-confined line buffer (only touched in onNewData).
    private val lineBuffer = StringBuilder()

    /**
     * Open the link if it isn't already. Idempotent and safe to call from any coroutine — the
     * pulse source calls it on each observe(), the relay on each startFuelFlow(). No-ops when no
     * supported device is attached (a later attach broadcast retries automatically).
     */
    @Synchronized
    fun ensureStarted() {
        if (running) return
        registerReceivers()
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
            ?: return Unit.also { Log.i(TAG, "No USB serial device attached yet") }
        if (!usbManager.hasPermission(driver.device)) {
            requestPermission(driver)
            return
        }
        open(driver)
    }

    @Synchronized
    private fun open(driver: UsbSerialDriver) {
        if (running) return
        try {
            val usbConnection = usbManager.openDevice(driver.device)
                ?: return Unit.also { Log.w(TAG, "openDevice returned null (permission revoked?)") }
            val p = driver.ports.firstOrNull()
                ?: return Unit.also { Log.w(TAG, "Driver exposes no ports") }
            p.open(usbConnection)
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            lineBuffer.setLength(0)
            val manager = SerialInputOutputManager(p, listener).apply {
                // Non-zero read timeout so reads release the USB request queue and writes
                // (relay commands) aren't starved on single-endpoint adapters.
                readTimeout = READ_TIMEOUT_MS
                start()
            }
            port = p
            ioManager = manager
            running = true
            _connected.value = true
            Log.i(TAG, "USB serial open @ $BAUD 8N1 (${driver.device.deviceName})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open USB serial port", e)
            handleDetach()
        }
    }

    /** Write one line (a trailing '\n' is appended). Returns false if the link is down. */
    fun writeLine(line: String): Boolean {
        val p = port ?: return false
        return try {
            p.write("$line\n".toByteArray(Charsets.US_ASCII), WRITE_TIMEOUT_MS)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Write failed; treating as disconnect", e)
            handleDetach()
            false
        }
    }

    @Synchronized
    private fun handleDetach() {
        if (!running && port == null) {
            _connected.value = false
            return
        }
        running = false
        _connected.value = false
        runCatching { ioManager?.stop() }
        runCatching { port?.close() }
        ioManager = null
        port = null
        lineBuffer.setLength(0)
        Log.i(TAG, "USB serial closed")
    }

    private val listener = object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) {
            lineBuffer.append(String(data, Charsets.US_ASCII))
            while (true) {
                val nl = lineBuffer.indexOf("\n")
                if (nl < 0) break
                val line = lineBuffer.substring(0, nl)
                lineBuffer.delete(0, nl + 1)
                if (line.isNotBlank()) _frames.tryEmit(SerialFrameParser.parse(line))
            }
            // Guard against an endless line (garbage stream with no '\n').
            if (lineBuffer.length > MAX_LINE) lineBuffer.setLength(0)
        }

        override fun onRunError(e: Exception) {
            Log.w(TAG, "Serial read error (cable unplugged?)", e)
            handleDetach()
        }
    }

    // --- Permission / attach handling -----------------------------------------------------

    @Synchronized
    private fun registerReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiversRegistered = true
    }

    private fun requestPermission(driver: UsbSerialDriver) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(driver.device, pending)
        Log.i(TAG, "Requested USB permission for ${driver.device.deviceName}")
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "USB permission ${if (granted) "granted" else "denied"}")
                    if (granted) ensureStarted()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> ensureStarted()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> handleDetach()
            }
        }
    }

    private companion object {
        const val TAG = "UsbSerialConn"
        const val BAUD = 115_200
        const val READ_TIMEOUT_MS = 200
        const val WRITE_TIMEOUT_MS = 200
        const val MAX_LINE = 512
        val ACTION_USB_PERMISSION: String = "app.balancee.smartpump.display.USB_PERMISSION"
    }
}
