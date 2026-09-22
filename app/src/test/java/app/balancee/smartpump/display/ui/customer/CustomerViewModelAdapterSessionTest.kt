// Phase 11e — the view model acts on the adapter's session (docs/serial-protocol.md): the adapter's
// STOP ends a sale, a lost session is re-armed for what is left, a sale the adapter will not start
// leaves the dispensing screen (or, if paid, stays for the attendant), and a restart resumes the
// session the adapter still holds instead of arming a new one.
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.hardware.AdapterSession
import app.balancee.smartpump.display.domain.hardware.FILLUP_CEILING_LITRES
import app.balancee.smartpump.display.domain.hardware.SaleSession
import app.balancee.smartpump.display.domain.hardware.SessionReply
import app.balancee.smartpump.display.domain.hardware.litresToLimitPulses
import app.balancee.smartpump.display.domain.model.EventType
import app.balancee.smartpump.display.domain.model.FailureCopy
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CustomerViewModelAdapterSessionTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val harness = VmHarness()
    private fun state(vm: CustomerViewModel) = vm.ui.value.state
    private fun eventsOf(type: EventType) = harness.events.recorded.filter { it.type == type }

    private fun prepayDispensing(): CustomerViewModel {
        val vm = harness.build()
        vm.onStartTransaction()
        vm.onModeTileTap(TransactionMode.PRE_PAY)
        vm.onAmountTileTap(amountNaira = 5000) // 5.0 L @ ₦1000/L
        vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
        vm.onModeConfirm()
        harness.payment.succeed()
        return vm
    }

    private fun cashFixedDispensing(amountKobo: Long = 300_000): CustomerViewModel {
        val vm = harness.build()
        vm.onAttendantCashFixed()
        vm.onCashFixedAuthorise(cashAmountKobo = amountKobo)
        return vm
    }

    // ---- the adapter's STOP ends the sale -----------------------------------------

    @Test
    fun `the adapter's STOP completes a pre-pay at what was paid for`() {
        val vm = prepayDispensing()
        harness.pulseSource.emitPulse(count = 480)

        harness.pulseSource.emitStopped(count = 500)

        val done = state(vm) as TransactionState.Complete
        assertEquals(5.0, done.litres, 0.0)
        assertFalse(harness.relay.isDispensing.value)
    }

    @Test
    fun `the adapter's STOP completes a cash-fixed sale at its cutoff`() {
        val vm = cashFixedDispensing()
        harness.pulseSource.emitPulse(count = 250)

        harness.pulseSource.emitStopped(count = 300)

        assertEquals(3.0, (state(vm) as TransactionState.Complete).litres, 0.0)
    }

    @Test
    fun `a fill-up the adapter stops at the ceiling ends on what flowed, and is logged`() {
        val vm = harness.build()
        vm.onAttendantFillUpAuthorise()
        val ceiling = litresToLimitPulses(FILLUP_CEILING_LITRES).toInt()
        harness.pulseSource.emitPulse(count = ceiling - 10)

        harness.pulseSource.emitStopped(count = ceiling)

        val full = state(vm) as TransactionState.FillupTankFull
        assertEquals(FILLUP_CEILING_LITRES, full.verifiedLitres, 1e-9)
        assertEquals(1, eventsOf(EventType.FILLUP_CEILING_REACHED).size)
    }

    // ---- the backstop compares pulses, not litres ----------------------------------

    /**
     * 3.355 L authorised arms 335 pulses. A litre comparison would wait for 3.36 L — a pulse past
     * what was paid for, and one the adapter will never pour.
     */
    @Test
    fun `a sale authorised to a fraction of a pulse completes on the adapter's limit`() {
        harness.pulseRepo.stateToRestore = TransactionState.FixedDispensing(
            flow = TransactionFlow.FIXED_PREPAY_DIGITAL, txnId = "BLC-3355",
            priceKoboPerLitre = TEST_KOBO_PER_LITRE, amountKobo = 335_500,
            litresAuthorised = 3.355, litresSoFar = 0.0, method = PaymentMethod.BALANCEE_APP,
        )
        val vm = harness.build()
        assertEquals(335L, harness.relay.lastLimit)

        harness.pulseSource.emitPulse(count = 335)

        val done = state(vm) as TransactionState.Complete
        assertEquals("billed as authorised", 3.355, done.litres, 0.0)
    }

    // ---- the adapter will not start the sale ---------------------------------------

    @Test
    fun `a cash-fixed sale the adapter refuses goes to an error that says to return the cash`() {
        harness.relay.armReply = { _, _ -> SessionReply.Refused("CMD") }

        val vm = cashFixedDispensing()

        val error = state(vm) as TransactionState.Error
        assertEquals(FailureCopy.SEE_ATTENDANT, error.message)
        assertTrue(error.attendantDetail!!.contains("Return the customer's cash"))
        assertFalse(harness.relay.isDispensing.value)
        assertEquals(1, eventsOf(EventType.ADAPTER_DID_NOT_ARM).size)
    }

    @Test
    fun `a fill-up the adapter does not answer goes to an error`() {
        harness.relay.armReply = { _, _ -> SessionReply.NoReply }
        val vm = harness.build()

        vm.onAttendantFillUpAuthorise()

        val error = state(vm) as TransactionState.Error
        assertFalse("nothing was taken", error.attendantDetail!!.contains("cash"))
        assertEquals(1, eventsOf(EventType.ADAPTER_DID_NOT_ARM).size)
    }

    @Test
    fun `a paid sale the adapter will not start stays for the attendant to end`() {
        harness.relay.armReply = { _, _ -> SessionReply.NoReply }
        val vm = prepayDispensing()

        assertTrue("the money keeps its screen", state(vm) is TransactionState.FixedDispensing)
        assertEquals(1, eventsOf(EventType.ADAPTER_DID_NOT_ARM).size)

        vm.onAttendantEndSaleEarly()
        val done = state(vm) as TransactionState.Complete
        assertEquals(0.0, done.litres, 0.0)
        assertEquals(500_000, done.amountKobo)
    }

    // ---- a session lost mid-sale ---------------------------------------------------

    @Test
    fun `a lost session is re-armed for what is left, under a new tag, and the sale carries on`() {
        val vm = cashFixedDispensing() // 300 pulses
        val firstTag = harness.relay.arms.single().second
        harness.pulseSource.emitPulse(count = 120)

        harness.pulseSource.emitSessionLost()

        val (limit, tag) = harness.relay.arms.last()
        assertEquals(180L, limit)
        assertNotEquals(firstTag, tag)
        assertEquals(SaleSession((state(vm) as TransactionState.CashFixedDispensing).txnId, tag, 120),
            harness.pulseRepo.saleSession)
        assertEquals(120, eventsOf(EventType.ADAPTER_SESSION_LOST).single().pulses)

        harness.pulseSource.emitPulse(count = 90) // the new session's own count
        assertEquals(2.1, (state(vm) as TransactionState.CashFixedDispensing).litresSoFar, 1e-9)
        harness.pulseSource.emitPulse(count = 180)
        assertEquals(3.0, (state(vm) as TransactionState.Complete).litres, 0.0)
    }

    // ---- the tag is persisted before the arm is sent -------------------------------

    @Test
    fun `the session is saved before the adapter is armed`() {
        var savedWhenArmed: SaleSession? = null
        harness.relay.onArm = { _, _ -> savedWhenArmed = harness.pulseRepo.saleSession }

        val vm = cashFixedDispensing()

        val txnId = (state(vm) as TransactionState.CashFixedDispensing).txnId
        assertEquals(SaleSession(txnId, harness.relay.arms.single().second, 0), savedWhenArmed)
    }

    @Test
    fun `ending a sale clears its session`() {
        val vm = cashFixedDispensing()
        harness.pulseSource.emitPulse(count = 300)

        vm.onDismissComplete()

        assertNull("the last session write was a clear", harness.pulseRepo.savedSessions.last())
        assertNull(harness.pulseRepo.saleSession)
    }

    // ---- boot resume ----------------------------------------------------------------

    private fun seedRestartedPrepay() {
        harness.pulseRepo.stateToRestore = TransactionState.FixedDispensing(
            flow = TransactionFlow.FIXED_PREPAY_DIGITAL, txnId = "BLC-R1",
            priceKoboPerLitre = TEST_KOBO_PER_LITRE, amountKobo = 500_000,
            litresAuthorised = 5.0, litresSoFar = 2.0, method = PaymentMethod.BALANCEE_APP,
        )
        harness.pulseRepo.pulsesToRestore = 200
        harness.pulseRepo.anchorToRestore = 1_200L
        harness.pulseSource.setAdapterCount(1_350L)
    }

    @Test
    fun `a restart resumes the session the adapter still holds, with its exact count`() {
        seedRestartedPrepay()
        harness.pulseRepo.saleSession = SaleSession("BLC-R1", tag = 42, basePulses = 0)
        harness.relay.queryReply = SessionReply.Armed(AdapterSession(42, 1_000))
        harness.relay.armReply = { _, tag -> SessionReply.Armed(AdapterSession(tag, 1_000)) }

        val vm = harness.build()

        assertEquals("the same tag: a resume, never a second allowance", 42L, harness.relay.arms.single().second)
        assertTrue("the 7h estimate is not needed", harness.pulseSource.awaitCalls.isEmpty())
        assertTrue(eventsOf(EventType.PULSE_GAP_RECOVERED).isEmpty())
        assertTrue(eventsOf(EventType.PULSE_GAP_UNEXPLAINED).isEmpty())

        // The adapter counted 350 for this sale, 150 of them while the app was down.
        harness.pulseSource.emitPulse(count = 350)
        assertEquals(3.5, (state(vm) as TransactionState.FixedDispensing).litresSoFar, 1e-9)
        harness.pulseSource.emitPulse(count = 500)
        assertEquals(5.0, (state(vm) as TransactionState.Complete).litres, 0.0)
    }

    @Test
    fun `a restart finds the adapter already finished the sale and completes it`() {
        seedRestartedPrepay()
        harness.pulseRepo.saleSession = SaleSession("BLC-R1", tag = 42, basePulses = 0)
        harness.relay.queryReply = SessionReply.Stopped(42, cut = 1_500)
        harness.relay.armReply = { _, tag -> SessionReply.Stopped(tag, cut = 1_500) }

        val vm = harness.build()

        assertEquals(5.0, (state(vm) as TransactionState.Complete).litres, 0.0)
        assertFalse(harness.relay.isDispensing.value)
    }

    @Test
    fun `a saved session from another sale is never offered to the adapter`() {
        seedRestartedPrepay()
        harness.pulseRepo.saleSession = SaleSession("BLC-OTHER", tag = 42, basePulses = 0)
        // The adapter really does hold tag 42 — for the other sale. Resuming it here would pour
        // what is left of someone else's allowance into this one.
        harness.relay.queryReply = SessionReply.Armed(AdapterSession(42, 900))

        harness.build()

        assertEquals("nothing to ask about", 0, harness.relay.queryCount)
        val (limit, tag) = harness.relay.arms.single()
        assertNotEquals(42L, tag)
        assertTrue("the 7h path ran", harness.pulseSource.awaitCalls.isNotEmpty())
        assertEquals(500L - 350, limit) // 200 persisted + 150 recovered by 7h
    }

    @Test
    fun `a restart after the adapter lost the session arms a new one for what is left`() {
        seedRestartedPrepay()
        harness.pulseRepo.saleSession = SaleSession("BLC-R1", tag = 42, basePulses = 0)
        harness.relay.queryReply = SessionReply.NoSession

        harness.build()

        val (limit, tag) = harness.relay.arms.single()
        assertNotEquals(42L, tag)
        assertEquals(500L - 350, limit)
        assertEquals(SaleSession("BLC-R1", tag, 350), harness.pulseRepo.saleSession)
    }
}
