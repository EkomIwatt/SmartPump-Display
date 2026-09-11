// Phase 7h step 4 — boot resume applying the pulse-gap reconciliation.
//
// The bug these close: before this phase, an app that died mid-dispense came back, adopted the
// adapter's reading as a fresh baseline, and contributed ZERO for the fuel that flowed in between.
// Those litres reached a customer and were billed to nobody (OPEN_QUESTIONS #25).
//
// The reconciler's own decision table is covered in ReconcilePulseGapUseCaseTest. These tests are
// about what the ViewModel DOES with each answer: whether the litres land on the sale, whether an
// entry reaches the operator log, and — the one that matters most — whether the relay reopens.
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.hardware.PULSES_PER_LITRE
import app.balancee.smartpump.display.domain.model.EventType
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.model.TransactionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CustomerViewModelPulseGapResumeTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val harness = VmHarness()
    private fun state(vm: CustomerViewModel) = vm.ui.value.state

    /** A pre-pay dispense interrupted at [litresSeen], authorised for 10 L. */
    private fun seedInterruptedPrepay(litresSeen: Double, litresAuthorised: Double = 10.0) {
        harness.pulseRepo.stateToRestore = TransactionState.FixedDispensing(
            flow = TransactionFlow.FIXED_PREPAY_DIGITAL,
            txnId = "BLC-00847",
            priceKoboPerLitre = TEST_KOBO_PER_LITRE,
            amountKobo = (litresAuthorised * TEST_KOBO_PER_LITRE).toLong(),
            litresAuthorised = litresAuthorised,
            litresSoFar = litresSeen,
            method = PaymentMethod.BALANCEE_APP,
        )
        harness.pulseRepo.pulsesToRestore = (litresSeen * PULSES_PER_LITRE).toInt()
        harness.pulseRepo.activeRef = "BLC-00847"
    }

    // ---- the recovered case -------------------------------------------------------------

    /**
     * The headline: 1.5 L flowed during the watchdog window and it lands on the customer's sale
     * rather than evaporating. The resumed screen must already show the corrected figure — the
     * whole point of reconciling before dispatching the restored state.
     */
    @Test
    fun `fuel counted during the outage is added to the resumed sale`() {
        seedInterruptedPrepay(litresSeen = 4.0)
        harness.pulseRepo.anchorToRestore = 41_000L
        harness.pulseSource.setAdapterCount(41_150L) // 150 pulses = 1.5 L at the placeholder K

        val vm = harness.build()

        val resumed = state(vm) as TransactionState.FixedDispensing
        assertEquals(5.5, resumed.litresSoFar, 0.0001)
        assertTrue("a sale with headroom left keeps dispensing", harness.relay.isDispensing.value)
    }

    @Test
    fun `a recovered gap is written to the operator log against its transaction`() {
        seedInterruptedPrepay(litresSeen = 4.0)
        harness.pulseRepo.anchorToRestore = 41_000L
        harness.pulseSource.setAdapterCount(41_150L)

        harness.build()

        val logged = harness.events.last!!
        assertEquals(EventType.PULSE_GAP_RECOVERED, logged.type)
        assertEquals(150, logged.pulses)
        assertEquals("BLC-00847", logged.transactionRef)
    }

    /** The sale itself must say how its litre count came apart from what was observed. */
    @Test
    fun `the finished sale records how many litres were recovered`() {
        seedInterruptedPrepay(litresSeen = 4.0)
        harness.pulseRepo.anchorToRestore = 41_000L
        harness.pulseSource.setAdapterCount(41_150L)
        val vm = harness.build()

        harness.pulseSource.emitPulse(count = (4.5 * PULSES_PER_LITRE).toInt()) // reaches 10.0 L

        val saved = harness.transactions.last!!
        assertEquals(1.5, saved.recoveredLitres, 0.0001)
    }

    // ---- the case that must not reopen the relay -----------------------------------------

    /**
     * The safety case. Recovery reveals the customer has ALREADY had more than they paid for, so
     * reopening would pour a second helping on top. Invariant #4 does not stop applying because
     * the extra fuel left the pump while the app was blind.
     */
    @Test
    fun `a sale already past its paid target completes without reopening the relay`() {
        seedInterruptedPrepay(litresSeen = 9.8, litresAuthorised = 10.0)
        harness.pulseRepo.anchorToRestore = 41_000L
        harness.pulseSource.setAdapterCount(41_060L) // +0.6 L → 10.4 L delivered

        val vm = harness.build()

        assertFalse("fuel must not flow again", harness.relay.isDispensing.value)
        val done = state(vm) as TransactionState.Complete
        assertEquals("BLC-00847", done.txnId)
    }

    /**
     * And it records what ACTUALLY flowed, not the tidier paid-for figure. The audit row carries
     * litres and amount independently; overstating neither is what lets the overshoot be found
     * later against the dispenser's own totaliser.
     */
    @Test
    fun `an over-target completion records the litres delivered, not the litres paid for`() {
        seedInterruptedPrepay(litresSeen = 9.8, litresAuthorised = 10.0)
        harness.pulseRepo.anchorToRestore = 41_000L
        harness.pulseSource.setAdapterCount(41_060L)

        harness.build()

        val saved = harness.transactions.last!!
        assertEquals(10.4, saved.litresDispensed, 0.0001)
        assertEquals("charged for what was paid, not what spilled", 10 * TEST_KOBO_PER_LITRE, saved.amountKobo)
        assertEquals(0.6, saved.recoveredLitres, 0.0001)
    }

    // ---- the refusals ---------------------------------------------------------------------

    /**
     * The adapter lost power too. Nothing may be attributed, the sale resumes from what was
     * observed, and the loss goes to a human instead of onto a customer's bill.
     */
    @Test
    fun `a restarted adapter adds nothing and is logged as unexplained`() {
        seedInterruptedPrepay(litresSeen = 4.0)
        harness.pulseRepo.anchorToRestore = 41_000L
        harness.pulseSource.setAdapterCount(3L) // counter restarted

        val vm = harness.build()

        assertEquals(4.0, (state(vm) as TransactionState.FixedDispensing).litresSoFar, 0.0001)
        val logged = harness.events.last!!
        assertEquals(EventType.PULSE_GAP_UNEXPLAINED, logged.type)
        assertNull("the size was never knowable", logged.pulses)
    }

    @Test
    fun `a silent adapter adds nothing and is logged`() {
        seedInterruptedPrepay(litresSeen = 4.0)
        harness.pulseRepo.anchorToRestore = 41_000L
        harness.pulseSource.setAdapterCount(null)

        val vm = harness.build()

        assertEquals(4.0, (state(vm) as TransactionState.FixedDispensing).litresSoFar, 0.0001)
        assertEquals(EventType.PULSE_GAP_UNEXPLAINED, harness.events.last!!.type)
    }

    /** A pump updated from before schema v4 has no anchor, and must not subtract from nothing. */
    @Test
    fun `a missing anchor adds nothing even with a sale in flight`() {
        seedInterruptedPrepay(litresSeen = 4.0)
        harness.pulseRepo.anchorToRestore = null
        harness.pulseSource.setAdapterCount(41_150L)

        val vm = harness.build()

        assertEquals(4.0, (state(vm) as TransactionState.FixedDispensing).litresSoFar, 0.0001)
        assertEquals(EventType.PULSE_GAP_UNEXPLAINED, harness.events.last!!.type)
    }

    // ---- the quiet path -------------------------------------------------------------------

    /**
     * An ordinary cold start must stay silent. No sale was in flight and no anchor was left behind,
     * so there is nothing that could have been missed — and an event on every single launch would
     * bury the real ones.
     */
    @Test
    fun `an idle boot logs nothing and never waits on the adapter`() {
        harness.pulseRepo.stateToRestore = TransactionState.Idle
        harness.pulseRepo.anchorToRestore = null
        harness.pulseSource.setAdapterCount(41_150L)

        harness.build()

        assertTrue("no events on a clean start", harness.events.recorded.isEmpty())
        assertTrue("the adapter is not even asked", harness.pulseSource.awaitCalls.isEmpty())
    }

    /** A resumed sale where the adapter moved not at all is not an event either. */
    @Test
    fun `a resume with no gap logs nothing`() {
        seedInterruptedPrepay(litresSeen = 4.0)
        harness.pulseRepo.anchorToRestore = 41_000L
        harness.pulseSource.setAdapterCount(41_000L)

        val vm = harness.build()

        assertEquals(4.0, (state(vm) as TransactionState.FixedDispensing).litresSoFar, 0.0001)
        assertTrue(harness.events.recorded.isEmpty())
    }

    /**
     * Fuel counted with no sale open. Nothing to attribute it to, but it is real and measurable,
     * so the size is kept in the log rather than discarded with it.
     */
    @Test
    fun `a gap with no transaction in flight is logged with its size`() {
        harness.pulseRepo.stateToRestore = TransactionState.Idle
        harness.pulseRepo.anchorToRestore = 41_000L
        harness.pulseSource.setAdapterCount(41_150L)

        harness.build()

        val logged = harness.events.last!!
        assertEquals(EventType.PULSE_GAP_UNEXPLAINED, logged.type)
        assertEquals(150, logged.pulses)
    }
}
