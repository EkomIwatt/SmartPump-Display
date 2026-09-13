// Phase 7h — how an event row converts its raw pulse count into the litres an operator reads.
//
// The decision under test: each row stores the K-factor that was in force when it was written, and
// converts with THAT, not with today's constant. PULSES_PER_LITRE is an unmeasured placeholder
// (OPEN_QUESTIONS #1) and will change at calibration; without the stamp, every historic entry in
// the fuel log would silently take on a new value the day it does.
package app.balancee.smartpump.display.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OperationalEventTest {

    private fun event(pulses: Int?, k: Double?) = OperationalEvent(
        id = 1,
        type = EventType.PULSE_GAP_UNEXPLAINED,
        createdAtMs = 0L,
        transactionRef = null,
        pulses = pulses,
        pulsesPerLitre = k,
        detail = null,
    )

    @Test
    fun `litres convert with the K-factor stamped on the row`() {
        assertEquals(1.5, event(pulses = 150, k = 100.0).litres!!, 0.0001)
    }

    /**
     * The same raw count under a different stamped K must read differently. This is the whole point
     * of storing it: a row written before calibration keeps reporting what the operator was told.
     */
    @Test
    fun `the same pulse count reads differently under a different stamped K`() {
        assertEquals(3.0, event(pulses = 150, k = 50.0).litres!!, 0.0001)
    }

    /** A measured K-factor is not a whole number, and the conversion must not round it away. */
    @Test
    fun `a fractional K-factor is honoured rather than rounded`() {
        assertEquals(150 / 98.7, event(pulses = 150, k = 98.7).litres!!, 0.000001)
    }

    /**
     * An unknowable amount — a restarted or silent adapter — stays null. Reporting 0.00 L would
     * read as "no fuel lost", which is the opposite of what the row means.
     */
    @Test
    fun `an unknown pulse count has no litre figure, and is never zero`() {
        assertNull(event(pulses = null, k = 100.0).litres)
    }

    /** A row with no stamped K cannot be converted at all; guessing today's would be a fabrication. */
    @Test
    fun `a missing K-factor yields no litre figure`() {
        assertNull(event(pulses = 150, k = null).litres)
    }

    /** Defensive: a corrupt zero or negative K must not divide into an infinity on the screen. */
    @Test
    fun `a nonsensical K-factor yields no litre figure rather than infinity`() {
        assertNull(event(pulses = 150, k = 0.0).litres)
        assertNull(event(pulses = 150, k = -100.0).litres)
    }
}
