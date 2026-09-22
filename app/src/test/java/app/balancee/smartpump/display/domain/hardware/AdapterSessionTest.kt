package app.balancee.smartpump.display.domain.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

class AdapterSessionTest {

    @Test fun `limit for a litre figure that is not exact in binary is not a pulse short`() {
        // The case the epsilon exists for: a bare floor gets this wrong.
        assertEquals(114L, floor(1.15 * 100.0).toLong())
        assertEquals(115L, litresToLimitPulses(1.15, pulsesPerLitre = 100.0))
    }

    @Test fun `every 0_01 L figure up to 20 L maps to its exact pulse count at 100 pulses per litre`() {
        for (centilitres in 1..2_000) {
            val litres = centilitres / 100.0
            assertEquals("at $litres L", centilitres.toLong(), litresToLimitPulses(litres, pulsesPerLitre = 100.0))
        }
    }

    @Test fun `a non-integer K-factor rounds down, never up`() {
        // 3.35 L at 98.7 pulses/L is 330.645 pulses: the adapter must stop at 330, not 331.
        assertEquals(330L, litresToLimitPulses(3.35, pulsesPerLitre = 98.7))
        assertEquals(98L, litresToLimitPulses(1.0, pulsesPerLitre = 98.7))
    }

    @Test fun `the fill-up ceiling fits the adapter's limit at any plausible K-factor`() {
        // 450 pulses/L is a high-resolution small-bore meter; 1 000 is far beyond any on record.
        assertTrue(litresToLimitPulses(FILLUP_CEILING_LITRES, pulsesPerLitre = 1_000.0) <= MAX_LIMIT_PULSES)
    }
}
