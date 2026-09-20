// Review finding #5 — a second tap on a pay button while the first `/authorise` is still out.
//
// The two digital flows deliberately hold their state for the whole round trip: pre-pay stays on
// `ModeSelect`, fill-up stays on `FillupTankFull`, so the customer keeps reading the total instead
// of a QR-shaped hole. The cost is that the `as?` state check those handlers open with — mutual
// exclusion everywhere else in this class — guards nothing, because the state a second tap is
// checked against is the one the first tap left in place.
//
// What that bought on a real backend: a cancelled `process` mid-request (the server does not
// un-create a transaction because we stopped listening) and a second `/authorise` behind it. Two
// PENDING_PAYMENT for one tank, the first orphaned with a live checkout URL a customer may scan.
//
// Every test here **must** hold the authorise open via `holdAuthorise()`. Without it the fake emits
// Pending on the same tick, the state moves, and the test passes on the ordinary state check while
// asserting nothing about the window the defect lives in.
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CustomerViewModelDoubleTapTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val harness = VmHarness()
    private fun state(vm: CustomerViewModel) = vm.ui.value.state

    /** Up to the CONFIRM tap, exclusive — the caller decides how many times to press it. */
    private fun prepayUpToConfirm(vm: CustomerViewModel) {
        vm.onStartTransaction()
        vm.onModeTileTap(TransactionMode.PRE_PAY)
        vm.onAmountTileTap(amountNaira = 5000)
        vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
    }

    /** A fill-up dispensed and shut off, sitting on the total with both pay buttons live. */
    private fun fillUpToTankFull(vm: CustomerViewModel) {
        vm.onAttendantFillUpAuthorise()
        harness.pulseSource.emitPulse(count = 380) // 3.80 L
        vm.onSimulateNozzleShutoff()
    }

    // ---- flow 3: fill-up, where the review found it --------------------------------

    @Test
    fun `two taps on fill-up pay digital send one authorise`() {
        val vm = harness.build()
        fillUpToTankFull(vm)
        harness.payment.holdAuthorise()

        vm.onFillupPayDigital()
        vm.onFillupPayDigital()

        assertEquals(1, harness.payment.processCount)
    }

    /** Five, because a customer who thinks a screen is stuck does not tap it exactly twice. */
    @Test
    fun `a flurry of taps on fill-up pay digital still sends one authorise`() {
        val vm = harness.build()
        fillUpToTankFull(vm)
        harness.payment.holdAuthorise()

        repeat(5) { vm.onFillupPayDigital() }

        assertEquals(1, harness.payment.processCount)
    }

    /**
     * The ignored tap must not damage the sale it was ignored for. The first authorise is still
     * the live one, so when it answers the screen moves on exactly as it would have.
     */
    @Test
    fun `the held authorise still completes after an ignored second tap`() {
        harness.payment.pendingCheckoutUrl = "https://checkout.paystack.com/jn0ej3u6def5150"
        val vm = harness.build()
        fillUpToTankFull(vm)
        harness.payment.holdAuthorise()

        vm.onFillupPayDigital()
        vm.onFillupPayDigital()
        harness.payment.releaseAuthorise()

        val awaiting = state(vm) as TransactionState.FillupDigitalAwaitingPayment
        assertEquals("https://checkout.paystack.com/jn0ej3u6def5150", awaiting.qrContent)
        assertEquals(1, harness.payment.processCount)
    }

    /** Cash is still reachable from the same screen — the guard is about one button, not the state. */
    @Test
    fun `an in-flight authorise does not block the cash button`() {
        val vm = harness.build()
        fillUpToTankFull(vm)
        harness.payment.holdAuthorise()

        vm.onFillupPayDigital()
        vm.onFillupPayCash()

        assertTrue(state(vm) is TransactionState.FillupAwaitingCashConfirm)
    }

    // ---- flow 1: pre-pay, the sibling the review did not name ----------------------

    /**
     * The same defect, found by reading `onFillupPayDigital`'s own comment: it says it mirrors
     * Flow 1, and Flow 1 holds `ModeSelect` open for the round trip for the same reason. The last
     * review's lesson was a defect fixed in one flow and left standing in its siblings.
     */
    @Test
    fun `two taps on prepay confirm send one authorise`() {
        val vm = harness.build()
        prepayUpToConfirm(vm)
        harness.payment.holdAuthorise()

        vm.onModeConfirm()
        vm.onModeConfirm()

        assertEquals(1, harness.payment.processCount)
    }

    @Test
    fun `the held prepay authorise still completes after an ignored second tap`() {
        val vm = harness.build()
        prepayUpToConfirm(vm)
        harness.payment.holdAuthorise()

        vm.onModeConfirm()
        vm.onModeConfirm()
        harness.payment.releaseAuthorise()

        assertTrue(state(vm) is TransactionState.PrepayAwaitingPayment)
        assertEquals(1, harness.payment.processCount)
    }

    // ---- the gate has to reopen ----------------------------------------------------

    /**
     * The failure mode of a guard like this is a pump that will not sell. Cancelling mid-authorise
     * must leave the next customer able to start a sale — it works because the guard reads the
     * job's own liveness rather than a flag somebody has to remember to reset.
     */
    @Test
    fun `cancelling mid-authorise lets the next sale start`() {
        val vm = harness.build()
        prepayUpToConfirm(vm)
        harness.payment.holdAuthorise()
        vm.onModeConfirm()

        vm.onCancel()
        harness.payment.releaseAuthorise()

        prepayUpToConfirm(vm)
        vm.onModeConfirm()

        assertEquals(2, harness.payment.processCount)
    }

    /** A completed sale must not leave the gate shut behind it. */
    @Test
    fun `a finished authorise lets a later sale start`() {
        val vm = harness.build()
        prepayUpToConfirm(vm)
        vm.onModeConfirm()          // not held: runs to Pending
        vm.onCancel()

        prepayUpToConfirm(vm)
        vm.onModeConfirm()

        assertEquals(2, harness.payment.processCount)
    }
}
