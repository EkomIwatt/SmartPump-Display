// Phase 11d — the simulated board follows the firmware's session rules (spec §4), so debug builds
// exercise the same path as the rig.
package app.balancee.smartpump.display.data.hardware

import app.balancee.smartpump.display.domain.hardware.AdapterSession
import app.balancee.smartpump.display.domain.hardware.SessionReply
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockRelayControllerTest {

    private val board = MockRelayController()

    private fun pour(n: Int) = List(n) { board.countPulse() }

    @Test fun `the board cuts on exactly the pulse that reaches the limit`() = runTest {
        board.addUnwatchedPulses(1000)
        assertEquals(SessionReply.Armed(AdapterSession(7, 1000)), board.startFuelFlow(5, 7))

        val outcomes = pour(6)

        assertEquals(listOf(1, 2, 3, 4, 5), outcomes.take(5).map { it!!.salePulses })
        assertTrue(outcomes[4]!!.cut)
        assertNull(outcomes[5]) // relay off: that pulse belongs to no sale
        assertFalse(board.isDispensing.value)
    }

    @Test fun `a held sale resumes under its original limit`() = runTest {
        board.startFuelFlow(10, 7)
        pour(3)
        board.stopFuelFlow()
        pour(2) // coast while held
        assertEquals(SessionReply.Armed(AdapterSession(7, 0)), board.resumeFuelFlow(7))

        val outcomes = pour(10)

        assertTrue(outcomes[4]!!.cut) // 3 + 2 + 5 = 10: no fresh allowance
        assertEquals(10, outcomes[4]!!.salePulses)
    }

    @Test fun `a same-tag arm is a resume and never re-reads the limit`() = runTest {
        board.startFuelFlow(5, 7)
        pour(2)
        board.stopFuelFlow()

        board.startFuelFlow(9_999, 7)
        val outcomes = pour(3)

        assertTrue(outcomes[2]!!.cut)
    }

    @Test fun `a finished session never re-opens`() = runTest {
        board.startFuelFlow(2, 7)
        pour(2)

        assertEquals(SessionReply.Stopped(7, 2), board.resumeFuelFlow(7))
        assertEquals(SessionReply.Stopped(7, 2), board.startFuelFlow(2, 7))
        assertFalse(board.isDispensing.value)
    }

    @Test fun `a new tag starts a new session from the current count`() = runTest {
        board.startFuelFlow(2, 7)
        pour(2)

        assertEquals(SessionReply.Armed(AdapterSession(8, 2)), board.startFuelFlow(4, 8))
        assertEquals(SessionReply.NoSession, board.resumeFuelFlow(7))
    }
}
