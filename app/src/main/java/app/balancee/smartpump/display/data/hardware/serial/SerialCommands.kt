// App → adapter frames, built in one place so their framing cannot drift from the parser's.
// Specified in docs/serial-protocol.md §3.1 (revision 2, Phase 11). Each returns a complete line
// without the trailing '\n' — UsbSerialConnection.writeLine adds it.
package app.balancee.smartpump.display.data.hardware.serial

import kotlin.random.Random

object SerialCommands {

    /**
     * The largest limit the adapter accepts (`MAX_LIMIT` in the firmware, spec §7). A sanity bound
     * on a garbled frame, not a business rule: 10 000 L at 100 pulses/L.
     */
    const val MAX_LIMIT: Long = 1_000_000L

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

    /**
     * A fresh tag for a new sale: random, non-zero, unsigned 32-bit (spec D2). Random rather than a
     * counter because a counter restarts on reinstall and could match a stale session still held
     * on the adapter; a random tag collides about once in four billion sales.
     */
    fun newTag(random: Random = Random.Default): Long {
        while (true) {
            val tag = random.nextInt().toLong() and SerialFrameParser.U32_MAX
            if (tag != 0L) return tag
        }
    }

    private fun requireTag(tag: Long) {
        require(tag in 1..SerialFrameParser.U32_MAX) { "tag $tag outside 1..${SerialFrameParser.U32_MAX}" }
    }

    private fun frame(body: String): String = "%s*%02X".format(body, SerialFrameParser.xor8(body))
}
