// Phase 11d — the sale's pulses are `count − start`, both the adapter's own numbers (#36).
package app.balancee.smartpump.display.data.hardware

import app.balancee.smartpump.display.data.hardware.serial.SerialFrame
import app.balancee.smartpump.display.domain.hardware.AdapterSession
import app.balancee.smartpump.display.domain.hardware.RelayController
import app.balancee.smartpump.display.domain.hardware.SessionReply
import app.balancee.smartpump.display.domain.model.PulseMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsbSerialPulseSourceTest {

    private val link = FakeSerialLink()

    private class StubRelay(session: AdapterSession?) : RelayController {
        override val isDispensing: StateFlow<Boolean> = MutableStateFlow(true)
        override val session = MutableStateFlow(session)
        override suspend fun startFuelFlow(limitPulses: Long, tag: Long) = SessionReply.NoReply
        override suspend fun resumeFuelFlow(tag: Long) = SessionReply.NoReply
        override suspend fun querySession() = SessionReply.NoReply
        override suspend fun stopFuelFlow() = Unit
    }

    private fun TestScope.collect(session: AdapterSession?): MutableList<PulseMessage> {
        val out = mutableListOf<PulseMessage>()
        val source = UsbSerialPulseSource(link, StubRelay(session))
        backgroundScope.launch { source.observe().collect { out += it } }
        runCurrent()
        return out
    }

    private fun counts(out: List<PulseMessage>) = out.filterIsInstance<PulseMessage.Pulse>().map { it.count }

    @Test fun `the sale's pulses are the adapter's count minus the session start`() = runTest {
        val out = collect(AdapterSession(tag = 7, start = 1000))

        link.emit(SerialFrame.Pulse(1025))
        link.emit(SerialFrame.Pulse(1300))
        runCurrent()

        assertEquals(listOf(25, 300), counts(out))
    }

    @Test fun `the first frame a collection sees is counted in full (#36)`() = runTest {
        // Before Phase 11 the first frame only set a baseline and contributed zero — and its count
        // already included every pulse since the relay opened. Here 40 pulses flowed before the
        // app's collector attached; all 40 belong to the sale.
        val out = collect(AdapterSession(tag = 7, start = 1000))

        link.emit(SerialFrame.Pulse(1040))
        runCurrent()

        assertEquals(listOf(40), counts(out))
    }

    @Test fun `with no session, pulses belong to no sale`() = runTest {
        val out = collect(session = null)

        link.emit(SerialFrame.Pulse(1040))
        runCurrent()

        assertEquals(emptyList<Int>(), counts(out))
    }

    @Test fun `a count below the start is not fuel`() = runTest {
        val out = collect(AdapterSession(tag = 7, start = 1000))

        link.emit(SerialFrame.Pulse(12)) // the adapter rebooted onto an older total
        runCurrent()

        assertEquals(emptyList<Int>(), counts(out))
    }

    @Test fun `the adapter's STOP for this sale reports the pulses at the cut`() = runTest {
        val out = collect(AdapterSession(tag = 7, start = 1000))

        link.emit(SerialFrame.Stop(tag = 8, cut = 1500)) // another sale's
        link.emit(SerialFrame.Stop(tag = 7, cut = 1500))
        runCurrent()

        assertEquals(listOf(PulseMessage.Stopped(500)), out.filterIsInstance<PulseMessage.Stopped>())
    }

    @Test fun `a lost session is surfaced, other errors stay parse errors`() = runTest {
        val out = collect(AdapterSession(tag = 7, start = 1000))

        link.emit(SerialFrame.Error("NOSESSION"))
        link.emit(SerialFrame.Error("WDOG"))
        runCurrent()

        assertEquals(listOf(PulseMessage.SessionLost, PulseMessage.ParseError("ERR:WDOG")), out)
    }
}
