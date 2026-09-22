// Stateless line parser for the Arduino pulse-adapter protocol. One raw serial line in,
// one typed SerialFrame out. No Android / coroutine deps so it is trivially unit-testable —
// the must-be-right core of the Phase 7a hardware driver. Statefulness (cumulative → delta)
// lives in PulseAccumulator; this layer only validates framing + checksum and classifies.
package app.balancee.smartpump.display.data.hardware.serial

object SerialFrameParser {

    /**
     * Parse one line (trailing CR/LF tolerated). Never throws — anything that does not match
     * the framing or fails the checksum comes back as [SerialFrame.Invalid] with a reason.
     */
    fun parse(line: String): SerialFrame {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return SerialFrame.Invalid(line, "empty line")

        // Checksum is everything after the final '*'; the body is everything before it.
        val star = trimmed.lastIndexOf('*')
        if (star <= 0 || star == trimmed.length - 1) {
            return SerialFrame.Invalid(line, "missing checksum delimiter")
        }
        val body = trimmed.substring(0, star)
        val checksumText = trimmed.substring(star + 1)

        val expected = checksumText.toIntOrNull(16)
            ?: return SerialFrame.Invalid(line, "non-hex checksum '$checksumText'")
        val actual = xor8(body)
        if (expected != actual) {
            return SerialFrame.Invalid(line, "checksum mismatch: frame $checksumText, computed ${hex(actual)}")
        }

        val colon = body.indexOf(':')
        if (colon <= 0 || colon == body.length - 1) {
            return SerialFrame.Invalid(line, "malformed body '$body'")
        }
        val type = body.substring(0, colon)
        val payload = body.substring(colon + 1)

        return when (type) {
            "PULSE" -> payload.toLongOrNull()?.let(SerialFrame::Pulse)
                ?: SerialFrame.Invalid(line, "non-numeric pulse count '$payload'")
            "HB" -> payload.toLongOrNull()?.let(SerialFrame::Heartbeat)
                ?: SerialFrame.Invalid(line, "non-numeric heartbeat count '$payload'")
            "BOOT" -> payload.toLongOrNull()?.let(SerialFrame::Boot)
                ?: SerialFrame.Invalid(line, "non-numeric boot count '$payload'")
            "ARM" -> parseSession(payload)?.let { (tag, start) -> SerialFrame.Arm(tag, start) }
                ?: SerialFrame.Invalid(line, "malformed session payload '$payload'")
            "STOP" -> parseSession(payload)?.let { (tag, cut) -> SerialFrame.Stop(tag, cut) }
                ?: SerialFrame.Invalid(line, "malformed session payload '$payload'")
            "ERR" -> SerialFrame.Error(payload)
            else -> SerialFrame.Invalid(line, "unknown frame type '$type'")
        }
    }

    /**
     * `<tag>:<n>` for ARM and STOP — exactly two numbers, strict (spec §1), tag non-zero.
     *
     * Stricter than the single-number frames, which use `toLongOrNull` and so accept a sign. Those
     * are left alone because they are bench-verified; these are new, and a session frame read
     * wrongly is a sale attributed wrongly.
     */
    private fun parseSession(payload: String): Pair<Long, Long>? {
        val parts = payload.split(':')
        if (parts.size != 2) return null
        val tag = parseU32(parts[0]) ?: return null
        val n = parseU32(parts[1]) ?: return null
        if (tag == 0L) return null
        return tag to n
    }

    /** Unsigned decimal, 1–10 digits, no sign, at most 2³² − 1 — the adapter's `unsigned long`. */
    internal fun parseU32(text: String): Long? {
        if (text.isEmpty() || text.length > 10 || !text.all { it in '0'..'9' }) return null
        return text.toLong().takeIf { it <= U32_MAX }
    }

    internal const val U32_MAX = 0xFFFF_FFFFL

    /** XOR-8 of the ASCII bytes of [body] (the chars before the '*'), masked to one byte. */
    fun xor8(body: String): Int {
        var acc = 0
        for (c in body) acc = acc xor (c.code and 0xFF)
        return acc and 0xFF
    }

    private fun hex(value: Int): String = "%02X".format(value and 0xFF)
}
