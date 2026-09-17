// Two properties matter here, and only one of them is about telling the time.
//
// The clock has to actually shift, or the #15 probe asks the server nothing. And the shift has to
// be put back — including when the call it wrapped threw — because a signing clock left in the past
// would make every subsequent request fail in a way that looks like a server problem.
package app.balancee.smartpump.display.data.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ProbeClockTest {

    private val fixed = Instant.parse("2026-09-16T12:00:00Z")
    private val base = Clock.fixed(fixed, ZoneOffset.UTC)

    @Test
    fun `with no offset it is the clock it wraps`() {
        val clock = ProbeClock(base, ProbeClockOffset())

        assertEquals(fixed, clock.instant())
    }

    @Test
    fun `the offset is read per call, not captured at construction`() {
        val offset = ProbeClockOffset()
        val clock = ProbeClock(base, offset)

        offset.set(Duration.ofMinutes(-10))

        assertEquals(fixed.minusSeconds(600), clock.instant())
    }

    @Test
    fun `shiftedBy restores the clock afterwards`() = runBlocking {
        val offset = ProbeClockOffset()
        val clock = ProbeClock(base, offset)

        val seen = offset.shiftedBy(Duration.ofMinutes(-10)) { clock.instant() }

        assertEquals(fixed.minusSeconds(600), seen)
        assertEquals(fixed, clock.instant())
    }

    @Test
    fun `shiftedBy restores the clock even when the call fails`() {
        val offset = ProbeClockOffset()
        val clock = ProbeClock(base, offset)

        try {
            runBlocking {
                offset.shiftedBy(Duration.ofMinutes(-10)) { throw IllegalStateException("boom") }
            }
        } catch (expected: IllegalStateException) {
            // The point is what the clock reads now, not the exception.
        }

        assertEquals(fixed, clock.instant())
    }

    @Test
    fun `withZone keeps the offset rather than quietly dropping it`() {
        val offset = ProbeClockOffset().apply { set(Duration.ofMinutes(-10)) }
        val clock = ProbeClock(base, offset).withZone(ZoneOffset.UTC)

        assertEquals(fixed.minusSeconds(600), clock.instant())
    }

    @Test
    fun `the offset only applies in debug builds`() {
        // Unit tests run against the debug variant, so set() works here. The guard inside set() is
        // what makes a release build unable to sign with a false time; this test states the
        // expectation so a change to that guard is a visible decision.
        val offset = ProbeClockOffset()
        offset.set(Duration.ofMinutes(-10))

        assertTrue(
            "BuildConfig.DEBUG is expected to be true for unit tests",
            offset.current == Duration.ofMinutes(-10),
        )
    }
}
