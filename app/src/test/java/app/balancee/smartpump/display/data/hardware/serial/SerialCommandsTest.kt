package app.balancee.smartpump.display.data.hardware.serial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SerialCommandsTest {

    // --- Golden vectors from docs/serial-protocol.md §3.1, computed independently of xor8().
    // The same lines drove the firmware's host test (hardware/host_test), so a match here is a
    // match against what the adapter actually accepts.

    @Test fun ping() = assertEquals("PING*10", SerialCommands.PING)

    @Test fun relay_off() = assertEquals("RLY:0*4D", SerialCommands.RELAY_OFF)

    @Test fun query_session() = assertEquals("SES?*7A", SerialCommands.QUERY_SESSION)

    @Test fun arm_golden_vectors() {
        assertEquals("RLY:1:500:7*4E", SerialCommands.arm(limitPulses = 500, tag = 7))
        assertEquals("RLY:1:30000:7*48", SerialCommands.arm(limitPulses = 30_000, tag = 7))
        assertEquals("RLY:1:10:8*75", SerialCommands.arm(limitPulses = 10, tag = 8))
    }

    @Test fun resume_golden_vectors() {
        assertEquals("RES:7*49", SerialCommands.resume(7))
        assertEquals("RES:8*46", SerialCommands.resume(8))
    }

    @Test fun arm_carries_the_full_unsigned_32_bit_tag() {
        val line = SerialCommands.arm(limitPulses = SerialCommands.MAX_LIMIT, tag = 4_294_967_295)
        assertTrue(line.startsWith("RLY:1:1000000:4294967295*"))
    }

    @Test fun never_builds_a_bare_relay_open() {
        // A bare RLY:1 is refused by the adapter (no fuel); nothing here may produce one.
        val lines = listOf(SerialCommands.PING, SerialCommands.RELAY_OFF, SerialCommands.QUERY_SESSION,
            SerialCommands.arm(1, 1), SerialCommands.resume(1))
        assertTrue(lines.none { it.startsWith("RLY:1*") })
    }

    @Test fun arm_refuses_a_limit_the_adapter_would_refuse() {
        // Fail here, loudly, rather than send a frame whose only outcome is ERR:CMD.
        assertThrows(IllegalArgumentException::class.java) { SerialCommands.arm(0, 7) }
        assertThrows(IllegalArgumentException::class.java) { SerialCommands.arm(-1, 7) }
        assertThrows(IllegalArgumentException::class.java) { SerialCommands.arm(SerialCommands.MAX_LIMIT + 1, 7) }
    }

    @Test fun tag_must_be_non_zero_unsigned_32_bit() {
        assertThrows(IllegalArgumentException::class.java) { SerialCommands.arm(10, 0) }
        assertThrows(IllegalArgumentException::class.java) { SerialCommands.arm(10, 4_294_967_296) }
        assertThrows(IllegalArgumentException::class.java) { SerialCommands.resume(0) }
        assertThrows(IllegalArgumentException::class.java) { SerialCommands.resume(-7) }
    }

    @Test fun new_tag_is_never_zero() {
        // A source whose first draw is 0 must be drawn again, not returned.
        val zeroThenSeven = object : Random() {
            private val draws = ArrayDeque(listOf(0, 7))
            override fun nextBits(bitCount: Int): Int = draws.removeFirst()
        }
        assertEquals(7L, SerialCommands.newTag(zeroThenSeven))
    }

    @Test fun new_tag_maps_negative_ints_into_the_unsigned_range() {
        val allOnes = object : Random() {
            override fun nextBits(bitCount: Int): Int = -1
        }
        assertEquals(4_294_967_295L, SerialCommands.newTag(allOnes))
    }

    @Test fun new_tags_are_valid_for_arm() {
        val random = Random(42)
        repeat(1_000) {
            val tag = SerialCommands.newTag(random)
            assertTrue(tag in 1..4_294_967_295L)
            SerialCommands.arm(1, tag) // must not throw
        }
    }
}
