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
            "ERR" -> SerialFrame.Error(payload)
            else -> SerialFrame.Invalid(line, "unknown frame type '$type'")
        }
    }

    /** XOR-8 of the ASCII bytes of [body] (the chars before the '*'), masked to one byte. */
    fun xor8(body: String): Int {
        var acc = 0
        for (c in body) acc = acc xor (c.code and 0xFF)
        return acc and 0xFF
    }

    private fun hex(value: Int): String = "%02X".format(value and 0xFF)
}
