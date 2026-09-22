// Phase 11d — the relay controller's side of docs/serial-protocol.md: every open is a request the
// adapter must acknowledge, a lost acknowledgement is retried with the SAME frame (never a new
// allowance), and the automatic resume only ever sends RES for a sale that is still active.
package app.balancee.smartpump.display.data.hardware

import app.balancee.smartpump.display.data.hardware.serial.SerialFrame
import app.balancee.smartpump.display.domain.hardware.AdapterSession
import app.balancee.smartpump.display.domain.hardware.SessionReply
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The controller's listeners run in backgroundScope, which advanceUntilIdle() deliberately does
// not wait for — the automatic-resume tests use runCurrent(), which does run them.
@OptIn(ExperimentalCoroutinesApi::class)
class UsbSerialRelayControllerTest {

    private val link = FakeSerialLink()

    private val arm500 = "RLY:1:500:7*4E"   // golden, spec §3.1
    private val res7 = "RES:7*49"
    private val relayOff = "RLY:0*4D"

    /** A board that acknowledges arms and resumes for tag 7 with start 1000. */
    private fun ackingBoard() {
        link.responder = { line ->
            when {
                line.startsWith("RLY:1:") || line.startsWith("RES:") -> listOf(SerialFrame.Arm(7, 1000))
                line.startsWith("SES?") -> listOf(SerialFrame.Arm(7, 1000))
                else -> emptyList()
            }
        }
    }

    private fun TestScope.controller(): UsbSerialRelayController =
        UsbSerialRelayController(link, backgroundScope, StandardTestDispatcher(testScheduler)).also { runCurrent() }

    private suspend fun TestScope.armedController(): UsbSerialRelayController {
        ackingBoard()
        val c = controller()
        c.startFuelFlow(limitPulses = 500, tag = 7)
        link.written.clear()
        return c
    }

    // ---- arming -------------------------------------------------------------------

    @Test fun `an acknowledged arm opens the sale with the adapter's start`() = runTest {
        ackingBoard()
        val c = controller()

        val reply = c.startFuelFlow(limitPulses = 500, tag = 7)

        assertEquals(SessionReply.Armed(AdapterSession(7, 1000)), reply)
        assertEquals(listOf(arm500), link.written)
        assertTrue(c.isDispensing.value)
        assertEquals(AdapterSession(7, 1000), c.session.value)
    }

    @Test fun `a lost ARM is retried with the same frame, never a new allowance`() = runTest {
        var calls = 0
        link.responder = { line -> if (line.startsWith("RLY:1:") && ++calls >= 2) listOf(SerialFrame.Arm(7, 1000)) else emptyList() }
        val c = controller()

        val reply = c.startFuelFlow(limitPulses = 500, tag = 7)

        assertTrue(reply is SessionReply.Armed)
        assertEquals(listOf(arm500, arm500), link.written)
    }

    @Test fun `no ARM after three sends refuses the sale and makes sure the relay is off`() = runTest {
        val c = controller() // silent board

        val reply = c.startFuelFlow(limitPulses = 500, tag = 7)

        assertEquals(SessionReply.NoReply, reply)
        assertEquals(listOf(arm500, arm500, arm500, relayOff), link.written)
        assertFalse(c.isDispensing.value)
    }

    @Test fun `firmware that refuses the frame means no fuel and no retry`() = runTest {
        link.responder = { listOf(SerialFrame.Error("CMD")) } // pre-Phase-11 firmware
        val c = controller()

        val reply = c.startFuelFlow(limitPulses = 500, tag = 7)

        assertEquals(SessionReply.Refused("CMD"), reply)
        assertEquals(listOf(arm500), link.written)
        assertFalse(c.isDispensing.value)
    }

    @Test fun `a limit or tag the adapter would refuse is refused here, with nothing sent`() = runTest {
        val c = controller()
        assertTrue(c.startFuelFlow(limitPulses = 0, tag = 7) is SessionReply.Refused)
        assertTrue(c.startFuelFlow(limitPulses = 1_000_001, tag = 7) is SessionReply.Refused)
        assertTrue(c.startFuelFlow(limitPulses = 500, tag = 0) is SessionReply.Refused)
        assertTrue(link.written.isEmpty())
    }

    @Test fun `a stop during the arm's retries ends them and leaves the relay off`() = runTest {
        val c = controller() // silent board
        val arming = async { c.startFuelFlow(limitPulses = 500, tag = 7) }
        runCurrent()
        advanceTimeBy(600) // first attempt timed out, second sent
        runCurrent()
        c.stopFuelFlow()
        advanceUntilIdle()

        assertEquals(SessionReply.NoReply, arming.await())
        assertEquals(2, link.written.count { it == arm500 }) // no third attempt
        assertEquals(relayOff, link.written.last())
        assertFalse(c.isDispensing.value)
    }

    // ---- automatic resume ---------------------------------------------------------

    @Test fun `the link coming back mid-sale sends RES, never RLY 1`() = runTest {
        val c = armedController()

        link.connected.value = false
        runCurrent()
        link.connected.value = true
        runCurrent()

        assertEquals(listOf(res7), link.written)
        assertTrue(c.isDispensing.value)
    }

    @Test fun `a watchdog trip with the link up resumes the sale`() = runTest {
        armedController()

        link.emit(SerialFrame.Error("WDOG"))
        runCurrent()

        assertEquals(listOf(res7), link.written)
    }

    @Test fun `a board reboot that lost the session stops the sale`() = runTest {
        val c = armedController()
        link.responder = { line -> if (line.startsWith("RES:")) listOf(SerialFrame.Error("NOSESSION")) else emptyList() }

        link.emit(SerialFrame.Boot(900))
        runCurrent()

        assertEquals(listOf(res7), link.written)
        assertFalse(c.isDispensing.value)

        // The sale is no longer active, so nothing resumes it again.
        link.emit(SerialFrame.Error("WDOG"))
        runCurrent()
        assertEquals(listOf(res7), link.written)
    }

    @Test fun `the adapter's STOP ends the sale and nothing resumes it`() = runTest {
        val c = armedController()

        link.emit(SerialFrame.Stop(7, 1500))
        runCurrent()
        assertFalse(c.isDispensing.value)

        link.emit(SerialFrame.Error("WDOG"))
        link.connected.value = false
        runCurrent()
        link.connected.value = true
        runCurrent()
        assertTrue(link.written.isEmpty())
    }

    @Test fun `a STOP for another sale is ignored`() = runTest {
        val c = armedController()

        link.emit(SerialFrame.Stop(8, 1500))
        runCurrent()

        assertTrue(c.isDispensing.value)
    }

    @Test fun `nothing resumes a sale the app stopped`() = runTest {
        val c = armedController()

        c.stopFuelFlow()
        link.emit(SerialFrame.Error("WDOG"))
        link.connected.value = false
        runCurrent()
        link.connected.value = true
        runCurrent()

        assertEquals(listOf(relayOff), link.written)
        assertFalse(c.isDispensing.value)
    }

    // ---- explicit resume / query --------------------------------------------------

    @Test fun `an explicit resume claims the sale and re-opens it`() = runTest {
        ackingBoard()
        val c = controller()

        val reply = c.resumeFuelFlow(tag = 7)

        assertEquals(SessionReply.Armed(AdapterSession(7, 1000)), reply)
        assertEquals(listOf(res7), link.written)
        assertTrue(c.isDispensing.value)
    }

    @Test fun `resuming a finished session reports it and does not open`() = runTest {
        link.responder = { listOf(SerialFrame.Stop(7, 1500)) }
        val c = controller()

        assertEquals(SessionReply.Stopped(7, 1500), c.resumeFuelFlow(tag = 7))
        assertFalse(c.isDispensing.value)
    }

    @Test fun `a query reports the session and changes nothing`() = runTest {
        ackingBoard()
        val c = controller()

        assertEquals(SessionReply.Armed(AdapterSession(7, 1000)), c.querySession())
        assertEquals(listOf("SES?*7A"), link.written)
        assertFalse(c.isDispensing.value)
    }

    @Test fun `a query to a board with no session says so`() = runTest {
        link.responder = { listOf(SerialFrame.Error("NOSESSION")) }
        assertEquals(SessionReply.NoSession, controller().querySession())
    }
}
