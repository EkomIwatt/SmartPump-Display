package app.balancee.smartpump.display.data.hardware

import app.balancee.smartpump.display.data.hardware.serial.SerialFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * A USB link with a board on the end of it that answers whatever [responder] says. Records every
 * line written. Replies are emitted synchronously from [writeLine], after the controller has
 * subscribed — the same order the real read loop produces, only faster.
 */
class FakeSerialLink : SerialLink {
    private val _frames = MutableSharedFlow<SerialFrame>(extraBufferCapacity = 64)
    override val frames: SharedFlow<SerialFrame> = _frames

    override val connected = MutableStateFlow(true)
    override val adapterCount = MutableStateFlow<Long?>(null)

    val written = mutableListOf<String>()

    /** False makes every write fail, as a detached cable does. */
    var writable = true

    /** The board: frames to send back for a line written. Silent by default. */
    var responder: (String) -> List<SerialFrame> = { emptyList() }

    override fun ensureStarted() = Unit

    override fun writeLine(line: String): Boolean {
        if (!writable) return false
        written += line
        responder(line).forEach { check(_frames.tryEmit(it)) }
        return true
    }

    /** A frame the board sends unprompted (STOP, ERR:WDOG, BOOT, PULSE…). */
    fun emit(frame: SerialFrame) = check(_frames.tryEmit(frame))
}
