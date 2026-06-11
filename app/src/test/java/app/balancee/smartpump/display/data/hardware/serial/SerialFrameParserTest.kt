package app.balancee.smartpump.display.data.hardware.serial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialFrameParserTest {

    // --- Golden vectors: checksums hand-computed (XOR-8 of the bytes before '*'), so these
    // anchor the algorithm independently of the parser's own xor8(). E.g. for "PULSE:0042817"
    // the XOR is 0x5D (NOT the boss's illustrative 0x7C). "PULSE:1" -> 0x54, "HB:0" -> 0x00.

    @Test fun valid_pulse_single_digit() {
        assertEquals(SerialFrame.Pulse(1), SerialFrameParser.parse("PULSE:1*54"))
    }

    @Test fun valid_pulse_padded_count() {
        assertEquals(SerialFrame.Pulse(42817), SerialFrameParser.parse("PULSE:0042817*5D"))
    }

    @Test fun valid_heartbeat_zero() {
        assertEquals(SerialFrame.Heartbeat(0), SerialFrameParser.parse("HB:0*00"))
    }

    @Test fun valid_boot() {
        val line = withChecksum("BOOT:0")
        assertEquals(SerialFrame.Boot(0), SerialFrameParser.parse(line))
    }

    @Test fun valid_error_keeps_code_payload() {
        val line = withChecksum("ERR:E07")
        assertEquals(SerialFrame.Error("E07"), SerialFrameParser.parse(line))
    }

    @Test fun checksum_mismatch_is_invalid() {
        // PULSE:1 checksums to 0x54; 0x55 is wrong.
        val frame = SerialFrameParser.parse("PULSE:1*55")
        assertTrue(frame is SerialFrame.Invalid)
    }

    @Test fun missing_checksum_delimiter_is_invalid() {
        assertTrue(SerialFrameParser.parse("PULSE:1") is SerialFrame.Invalid)
    }

    @Test fun trailing_star_with_no_checksum_is_invalid() {
        assertTrue(SerialFrameParser.parse("PULSE:1*") is SerialFrame.Invalid)
    }

    @Test fun non_hex_checksum_is_invalid() {
        assertTrue(SerialFrameParser.parse("PULSE:1*ZZ") is SerialFrame.Invalid)
    }

    @Test fun unknown_type_is_invalid() {
        assertTrue(SerialFrameParser.parse(withChecksum("FOO:1")) is SerialFrame.Invalid)
    }

    @Test fun non_numeric_pulse_count_is_invalid() {
        assertTrue(SerialFrameParser.parse(withChecksum("PULSE:abc")) is SerialFrame.Invalid)
    }

    @Test fun empty_line_is_invalid() {
        assertTrue(SerialFrameParser.parse("") is SerialFrame.Invalid)
        assertTrue(SerialFrameParser.parse("   ") is SerialFrame.Invalid)
    }

    @Test fun trailing_crlf_is_tolerated() {
        assertEquals(SerialFrame.Pulse(1), SerialFrameParser.parse("PULSE:1*54\r\n"))
    }

    @Test fun lowercase_hex_checksum_is_accepted() {
        assertEquals(SerialFrame.Pulse(42817), SerialFrameParser.parse("PULSE:0042817*5d"))
    }

    @Test fun xor8_matches_hand_computed_golden() {
        assertEquals(0x5D, SerialFrameParser.xor8("PULSE:0042817"))
        assertEquals(0x54, SerialFrameParser.xor8("PULSE:1"))
        assertEquals(0x00, SerialFrameParser.xor8("HB:0"))
    }

    /** Build a well-formed line by appending the parser's own checksum — for the cases whose
     *  point is type/payload handling, not checksum correctness (those use golden vectors above). */
    private fun withChecksum(body: String): String =
        "$body*%02X".format(SerialFrameParser.xor8(body))
}
