// Messages emitted by PulseSource. The Arduino sends "PULSE:XXXX\n" over USB serial;
// the source layer parses raw bytes into these typed events.
package app.balancee.smartpump.display.domain.model

sealed class PulseMessage {

    /** A fuel-flow pulse received from the Arduino. [count] is cumulative for the current session. */
    data class Pulse(val count: Int, val timestampMs: Long = System.currentTimeMillis()) : PulseMessage()

    /** Periodic keep-alive from the adapter confirming it is still connected. */
    data class Heartbeat(val timestampMs: Long = System.currentTimeMillis()) : PulseMessage()

    /** A raw serial line arrived that could not be parsed — logged for debugging. */
    data class ParseError(val raw: String) : PulseMessage()

    /** USB serial connection was lost or the cable was unplugged. */
    object Disconnected : PulseMessage()
}
