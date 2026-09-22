// Messages emitted by PulseSource. The adapter speaks the serial protocol in
// docs/serial-protocol.md; the source layer maps its frames onto the sale as these typed events.
package app.balancee.smartpump.display.domain.model

sealed class PulseMessage {

    /** A fuel-flow pulse. [count] is the sale's pulses so far: the adapter's count − the session's start. */
    data class Pulse(val count: Int, val timestampMs: Long = System.currentTimeMillis()) : PulseMessage()

    /** Periodic keep-alive from the adapter confirming it is still connected. */
    data class Heartbeat(val timestampMs: Long = System.currentTimeMillis()) : PulseMessage()

    /**
     * The adapter cut the relay itself at the sale's limit (`STOP`). [count] is the sale's pulses
     * at the cut — exactly the limit it was armed with. Phase 11.
     */
    data class Stopped(val count: Int) : PulseMessage()

    /**
     * The adapter no longer holds this sale's session (`ERR:NOSESSION`) — it rebooted. Fuel is off
     * and will not resume by itself; the sale has to be re-armed for what is left. Phase 11.
     */
    data object SessionLost : PulseMessage()

    /** A raw serial line arrived that could not be parsed — logged for debugging. */
    data class ParseError(val raw: String) : PulseMessage()

    /** USB serial connection was lost or the cable was unplugged. */
    object Disconnected : PulseMessage()
}
