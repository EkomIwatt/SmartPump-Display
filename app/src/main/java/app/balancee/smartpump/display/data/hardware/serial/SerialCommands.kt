// App → adapter frames, built in one place so their framing cannot drift from the parser's.
// Specified in docs/serial-protocol.md §3.1 (revision 2, Phase 11). Each returns a complete line
// without the trailing '\n' — UsbSerialConnection.writeLine adds it.
package app.balancee.smartpump.display.data.hardware.serial

import app.balancee.smartpump.display.domain.hardware.MAX_LIMIT_PULSES
import app.balancee.smartpump.display.domain.hardware.newSessionTag
import kotlin.random.Random

object SerialCommands {

    /** The largest limit the adapter accepts — see [MAX_LIMIT_PULSES]. */
    const val MAX_LIMIT: Long = MAX_LIMIT_PULSES

    /** Liveness, ~1 Hz while the link is up; feeds the adapter's comms-loss watchdog. */
    val PING: String = frame("PING")

    /** Relay off. An open session is HELD, not ended — safe to send on every boot (spec §4). */
    val RELAY_OFF: String = frame("RLY:0")

    /** Ask which session the adapter holds, changing nothing. */
    val QUERY_SESSION: String = frame("SES?")

    /**
     * Arm session [tag] with [limitPulses] and open the relay. Re-sending it with the same tag is
     * safe — the adapter treats it as a resume and never re-latches the start or re-reads the
     * limit (spec §4, rule 1) — so a lost `ARM` is recovered by sending this again, unchanged.
     *
     * The limit is in pulses, never litres: the app stays sole owner of the K-factor.
     */
    fun arm(limitPulses: Long, tag: Long): String {
        require(limitPulses in 1..MAX_LIMIT) { "limit $limitPulses outside 1..$MAX_LIMIT" }
        requireTag(tag)
        return frame("RLY:1:$limitPulses:$tag")
    }

    /** Resume the held session [tag] under the limit the adapter already holds. */
    fun resume(tag: Long): String {
        requireTag(tag)
        return frame("RES:$tag")
    }

    /** A fresh tag for a new sale (spec D2) — see [newSessionTag]. */
    fun newTag(random: Random = Random.Default): Long = newSessionTag(random)

    private fun requireTag(tag: Long) {
        require(tag in 1..SerialFrameParser.U32_MAX) { "tag $tag outside 1..${SerialFrameParser.U32_MAX}" }
    }

    private fun frame(body: String): String = "%s*%02X".format(body, SerialFrameParser.xor8(body))
}
