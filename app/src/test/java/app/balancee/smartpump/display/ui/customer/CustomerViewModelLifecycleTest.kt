// Phase 8 — job lifecycle & safety invariants of CustomerViewModel.
// Covers the relay-open-on-boot invariant, cancel teardown (jobs cancelled, relay closed, pulses
// cleared), and the pre-pay expiry timeout that auto-cancels an unpaid transaction.
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.model.EventType
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CustomerViewModelLifecycleTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val harness = VmHarness()
    private fun state(vm: CustomerViewModel) = vm.ui.value.state

    @Test
    fun `boot asserts the relay-open invariant before deriving state`() {
        val vm = harness.build() // clean Idle boot
        assertTrue(harness.relay.stopCount >= 1)       // stopFuelFlow() called on boot
        assertFalse(harness.relay.isDispensing.value)  // relay open, no fuel
        assertTrue(state(vm) is TransactionState.Idle)
    }

    @Test
    fun `cancel mid-dispense closes the relay, clears pulses, and returns to Idle`() {
        val vm = harness.build()
        vm.onAttendantFillUpAuthorise()
        harness.pulseSource.emitPulse(count = 200)
        assertTrue(harness.relay.isDispensing.value)

        vm.onCancel()

        assertTrue(state(vm) is TransactionState.Idle)
        assertFalse(harness.relay.isDispensing.value)
        assertEquals(0 to 0L, harness.pulseRepo.lastSavedPulseCount) // pulses reset
    }

    @Test
    fun `dismiss on Complete returns to Idle`() {
        val vm = harness.build()
        vm.onAttendantCashFixed()
        vm.onCashFixedAuthorise(cashAmountKobo = 100_000) // 1.0 L
        harness.pulseSource.emitPulse(count = 100)        // → Complete
        assertTrue(state(vm) is TransactionState.Complete)

        vm.onDismissComplete()
        assertTrue(state(vm) is TransactionState.Idle)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `prepay expiry auto-cancels an unpaid transaction back to Idle`() =
        runTest(mainRule.dispatcher) {
            val vm = harness.build()
            vm.onStartTransaction()
            vm.onModeTileTap(TransactionMode.PRE_PAY)
            vm.onAmountTileTap(amountNaira = 5000)
            vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
            vm.onModeConfirm() // → PrepayAwaitingPayment (no payment.succeed())
            assertTrue(state(vm) is TransactionState.PrepayAwaitingPayment)

            advanceTimeBy(301_000) // PREPAY_EXPIRY_SECONDS (300s) + 1s
            runCurrent()

            assertTrue(state(vm) is TransactionState.Idle)
        }

    /**
     * Going back to Idle must leave a trace (10g).
     *
     * Until `PAYMENT_ABANDONED` existed, an abandoned pre-pay left **nothing at all** — and the
     * backend does not close the transaction on its own, so the customer can still pay after this
     * moment. A customer who returns saying they paid and got no fuel could not be answered,
     * because nothing on the pump knew the sale had ever existed. The transaction id is the part
     * that makes it answerable, so it is what this asserts.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `an abandoned prepay is recorded with the transaction id it abandoned`() =
        runTest(mainRule.dispatcher) {
            val vm = harness.build()
            vm.onStartTransaction()
            vm.onModeTileTap(TransactionMode.PRE_PAY)
            vm.onAmountTileTap(amountNaira = 5000)
            vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
            vm.onModeConfirm()
            val txnId = (state(vm) as TransactionState.PrepayAwaitingPayment).txnId

            advanceTimeBy(301_000)
            runCurrent()

            val abandoned = harness.events.recorded.filter { it.type == EventType.PAYMENT_ABANDONED }
            assertEquals("expected exactly one abandonment row", 1, abandoned.size)
            assertEquals(txnId, abandoned.single().transactionRef)
        }

    /** A sale that completes must not also be logged as abandoned — the expiry job is cancelled. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a paid prepay is never recorded as abandoned`() =
        runTest(mainRule.dispatcher) {
            val vm = harness.build()
            vm.onStartTransaction()
            vm.onModeTileTap(TransactionMode.PRE_PAY)
            vm.onAmountTileTap(amountNaira = 5000)
            vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
            vm.onModeConfirm()
            harness.payment.succeed()
            runCurrent()

            advanceTimeBy(301_000)
            runCurrent()

            assertTrue(harness.events.recorded.none { it.type == EventType.PAYMENT_ABANDONED })
        }

    // ---- the fill-up twin (re-review finding #R4) -----------------------------------------------

    /** Deliberately unlike the `BLC-…` shape this pump mints for itself. */
    private val SERVER_TXN_ID = "740e2af7-3573-45b1-a92b-813f2730ac93"

    /** Fill up 0.10 L, take the QR, and stop short of paying. Returns the local `BLC-…` ref. */
    private fun fillupToUnpaidQr(vm: CustomerViewModel): String {
        vm.onAttendantFillUpAuthorise()
        harness.pulseSource.emitPulse(count = 10) // 0.10 L, as the 10g gate ran it
        vm.onSimulateNozzleShutoff()
        val localRef = (state(vm) as TransactionState.FillupTankFull).txnId
        vm.onFillupPayDigital()
        assertTrue(state(vm) is TransactionState.FillupDigitalAwaitingPayment)
        return localRef
    }

    /**
     * **The same defect as `ed77e00`, one path over.** That commit fixed `Complete.txnId` for this
     * flow after the 10g gate caught it on a real ₦149 sale; the expiry path kept passing the
     * `FillupTankFull` it started from, whose `txnId` is the local `BLC-…` minted at
     * attendant-authorise — an id no `/authorise` ever issued.
     *
     * It matters here for the reason the pre-pay assertion above gives: the backend does not close
     * the transaction when `expiresAt` passes (observed on production, 3m16s past it), so the
     * checkout page stays payable after the pump has stopped watching. The row exists to answer a
     * customer who paid into that window, and an invented id answers nothing.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `an abandoned fill-up is recorded with the server's id, not the one this pump minted`() =
        runTest(mainRule.dispatcher) {
            harness.payment.pendingRef = SERVER_TXN_ID
            val vm = harness.build()
            val localRef = fillupToUnpaidQr(vm)

            advanceTimeBy(301_000) // FILLUP_DIGITAL_EXPIRY_SECONDS (300s) + 1s
            runCurrent()

            val abandoned = harness.events.recorded.filter { it.type == EventType.PAYMENT_ABANDONED }
            assertEquals("expected exactly one abandonment row", 1, abandoned.size)
            assertEquals(SERVER_TXN_ID, abandoned.single().transactionRef)
            assertNotEquals(
                "the pump's own reference reached the abandonment row",
                localRef,
                abandoned.single().transactionRef,
            )
        }

    /**
     * The figure logged has to be the one the still-live checkout page will charge, not the quote
     * struck at the nozzle from the device's own price. They diverge on a mid-sale re-price and at
     * any payable litre step coarser than the metered figure — and it is the server's number a
     * customer would be holding a receipt for.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `an abandoned fill-up logs what the checkout page charges, not the shutoff quote`() =
        runTest(mainRule.dispatcher) {
            harness.payment.pendingAmountKobo = 12_345L // ≠ 0.10 L × ₦1,000/L = ₦100.00
            val vm = harness.build()
            fillupToUnpaidQr(vm)

            advanceTimeBy(301_000)
            runCurrent()

            val detail = harness.events.recorded.single {
                it.type == EventType.PAYMENT_ABANDONED
            }.detail.orEmpty()
            assertTrue("logged the shutoff quote instead: $detail", detail.contains("123.45"))
        }

    /**
     * The other half of the same edit, pinned because it is an asymmetry and not an oversight: the
     * cash fall-back keeps the **local** id and the **device's** figure. What is owed in cash is
     * the tank's litres at the pump's own price — the same figure Flow 2 collects for the same
     * tank — and the row it settles into is a cash sale, which nothing authorised and nothing
     * uploads. The server's id belongs to a transaction that was never paid.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the cash fall-back after expiry keeps the local ref and the pump's own amount`() =
        runTest(mainRule.dispatcher) {
            harness.payment.pendingRef = SERVER_TXN_ID
            harness.payment.pendingAmountKobo = 12_345L
            val vm = harness.build()
            val localRef = fillupToUnpaidQr(vm)

            advanceTimeBy(301_000)
            runCurrent()

            val cash = state(vm) as TransactionState.FillupAwaitingCashConfirm
            assertEquals(localRef, cash.txnId)
            assertEquals(10_000L, cash.amountDueKobo) // 0.10 L × ₦1,000/L
        }

    /** A fill-up that is paid must not also be logged as abandoned. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a paid fill-up is never recorded as abandoned`() =
        runTest(mainRule.dispatcher) {
            val vm = harness.build()
            fillupToUnpaidQr(vm)
            harness.payment.succeed()
            runCurrent()

            advanceTimeBy(301_000)
            runCurrent()

            assertTrue(harness.events.recorded.none { it.type == EventType.PAYMENT_ABANDONED })
        }

    // ---- when the audit write itself fails (re-review finding #R5) ------------------------------

    /**
     * **Losing the row must not cost the pump.** `recordAbandonedPayment` is called from inside
     * the expiry coroutine and the `setState` that ends the sale comes after it, so a Room failure
     * took the transition with it: the countdown sits at zero, the relay is shut, and there is no
     * way back to Idle but restarting the app — with the exception escaping `viewModelScope` on
     * the way out.
     *
     * The same shape as the processor's `recordPriceRaceIfAny` (#R5 proper), and boarded together
     * because the two share one rule: an audit line is worth less than the transition it precedes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a prepay still returns to Idle when the abandonment row cannot be written`() =
        runTest(mainRule.dispatcher) {
            harness.events.failOn += EventType.PAYMENT_ABANDONED
            val vm = harness.build()
            vm.onStartTransaction()
            vm.onModeTileTap(TransactionMode.PRE_PAY)
            vm.onAmountTileTap(amountNaira = 5000)
            vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
            vm.onModeConfirm()

            advanceTimeBy(301_000)
            runCurrent()

            assertTrue("the pump was left on a dead QR screen", state(vm) is TransactionState.Idle)
        }

    /** The fill-up twin: the attendant must still be asked for cash on a tank that is already full. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a fill-up still falls back to cash when the abandonment row cannot be written`() =
        runTest(mainRule.dispatcher) {
            harness.events.failOn += EventType.PAYMENT_ABANDONED
            val vm = harness.build()
            fillupToUnpaidQr(vm)

            advanceTimeBy(301_000)
            runCurrent()

            assertTrue(
                "fuel was in the tank and the screen never asked for it",
                state(vm) is TransactionState.FillupAwaitingCashConfirm,
            )
        }
}
