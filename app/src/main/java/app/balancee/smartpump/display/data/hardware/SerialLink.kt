// The one USB-serial link to the pulse adapter, as the relay controller and pulse source see it.
// UsbSerialConnection is the only production implementation; the seam exists so the request /
// reply logic above it can be unit-tested against a fake link instead of a UsbManager.
package app.balancee.smartpump.display.data.hardware

import app.balancee.smartpump.display.data.hardware.serial.SerialFrame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface SerialLink {

    /** Hot stream of parsed frames. No replay: subscribe BEFORE writing a request. */
    val frames: SharedFlow<SerialFrame>

    /** True while a port is open. */
    val connected: StateFlow<Boolean>

    /** The adapter's lifetime count from the most recent frame that carried one; null = unknown. */
    val adapterCount: StateFlow<Long?>

    /** Open the link if it isn't already. Idempotent. */
    fun ensureStarted()

    /** Write one line ('\n' appended). Blocking; false if the link is down. */
    fun writeLine(line: String): Boolean
}
