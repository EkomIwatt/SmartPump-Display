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

    // ---- the other clock (#R10) -----------------------------------------------------------------

    /**
     * **The countdown is not normally the clock that ends a sale.** Two of them run off the same
     * `expiresAt`: `expiryJob`, which counts one-second `delay`s, and the processor's poll loop,
     * which compares against the wall clock every ten seconds. A tablet that dozes defers both, and
     * on waking the poller fires at once while the countdown still owes its remaining ticks — so
     * `onPaymentFailed` ends the sale, cancels the countdown, and until #R10 wrote nothing.
     *
     * Found on the SM-T220 on 2026-09-20, not in a test: a pre-pay QR left to expire produced the
     * "stopped waiting" screen and **zero** `PAYMENT_ABANDONED` rows in the database — across the
     * whole life of the app, both digital flows, including the 10g gate.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a prepay whose window elapses in the poller is still recorded as abandoned`() =
        runTest(mainRule.dispatcher) {
            val vm = harness.build()
            vm.onStartTransaction()
            vm.onModeTileTap(TransactionMode.PRE_PAY)
            vm.onAmountTileTap(amountNaira = 5000)
            vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
            vm.onModeConfirm()
            val txnId = (state(vm) as TransactionState.PrepayAwaitingPayment).txnId

            harness.payment.fail(reason = "the payment window elapsed", windowElapsed = true)
            runCurrent()

            val abandoned = harness.events.recorded.filter { it.type == EventType.PAYMENT_ABANDONED }
            assertEquals("expected exactly one abandonment row", 1, abandoned.size)
            assertEquals(txnId, abandoned.single().transactionRef)
            assertTrue(
                "the tendered amount never reached the row",
                abandoned.single().detail.orEmpty().contains("5,000.00"),
            )
            // The screen the 10g copy was written for. Deliberately not Idle: it tells a customer
            // who may have paid to see the attendant, which a blank idle screen does not.
            assertTrue(state(vm) is TransactionState.Error)
        }

    /**
     * The fill-up twin, with #R4's rule intact: the row carries the server's id and the cash
     * fall-back keeps the pump's own.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a fill-up whose window elapses in the poller is recorded with the server's id`() =
        runTest(mainRule.dispatcher) {
            harness.payment.pendingRef = SERVER_TXN_ID
            val vm = harness.build()
            val localRef = fillupToUnpaidQr(vm)

            harness.payment.fail(reason = "the payment window elapsed", windowElapsed = true)
            runCurrent()

            val abandoned = harness.events.recorded.single { it.type == EventType.PAYMENT_ABANDONED }
            assertEquals(SERVER_TXN_ID, abandoned.transactionRef)
            val cash = state(vm) as TransactionState.FillupAwaitingCashConfirm
            assertEquals(localRef, cash.txnId)
        }

    /**
     * A refusal is not an abandonment, and the copy cannot tell them apart — both arrive as a
     * recoverable `Failed`. A declined card leaves nothing a customer could have paid into.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a declined payment is not recorded as abandoned`() =
        runTest(mainRule.dispatcher) {
            val vm = harness.build()
            vm.onStartTransaction()
            vm.onModeTileTap(TransactionMode.PRE_PAY)
            vm.onAmountTileTap(amountNaira = 5000)
            vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
            vm.onModeConfirm()

            harness.payment.fail(reason = "the card was declined")
            runCurrent()

            assertTrue(harness.events.recorded.none { it.type == EventType.PAYMENT_ABANDONED })
            assertTrue(state(vm) is TransactionState.Error)
        }

    // ---- a fill-up cannot be cancelled, only paid another way (#R13) ------------------------------

    /**
     * **The fuel is already in the tank.** "Cancel · collect cash instead" used to drop to Idle and
     * record nothing, so the litres left the pump with no transaction and no event — seen on the
     * SM-T220 twice on 2026-09-21. The design has no route to Idle from here and neither does
     * `state-machine.md`; the only way on is payment, or cash.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling a digital fill-up asks for cash for the tank instead of dropping the sale`() =
        runTest(mainRule.dispatcher) {
            harness.payment.pendingRef = SERVER_TXN_ID
            harness.payment.pendingAmountKobo = 12_345L
            val vm = harness.build()
            val localRef = fillupToUnpaidQr(vm)

            vm.onFillupDigitalCancel()
            runCurrent()

            val cash = state(vm) as TransactionState.FillupAwaitingCashConfirm
            // The same settlement the expiry fall-back makes: this tank, at this pump's price,
            // under the pump's own reference.
            assertEquals(localRef, cash.txnId)
            assertEquals(0.10, cash.verifiedLitres, 1e-9)
            assertEquals(10_000L, cash.amountDueKobo)
        }

    /**
     * The checkout page stays payable after the cancel, so the row is what answers a customer who
     * pays by card later *and* has handed over cash. Worded as a cancel, not a timeout, because the
     * two send whoever reads it to different people.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a cancelled digital fill-up is recorded as cancelled, against the server's id`() =
        runTest(mainRule.dispatcher) {
            harness.payment.pendingRef = SERVER_TXN_ID
            harness.payment.pendingAmountKobo = 12_345L
            val vm = harness.build()
            fillupToUnpaidQr(vm)

            vm.onFillupDigitalCancel()
            runCurrent()

            val row = harness.events.recorded.single { it.type == EventType.PAYMENT_ABANDONED }
            assertEquals(SERVER_TXN_ID, row.transactionRef)
            val detail = row.detail.orEmpty()
            assertTrue("not worded as a cancel: $detail", detail.contains("cancelled"))
            assertTrue("not the checkout page's figure: $detail", detail.contains("123.45"))
        }

    /** What the question was about: after the cancel, CASH RECEIVED closes it as a cash sale. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cash received after a cancelled digital fill-up records the tank as a cash sale`() =
        runTest(mainRule.dispatcher) {
            val vm = harness.build()
            val localRef = fillupToUnpaidQr(vm)
            vm.onFillupDigitalCancel()
            runCurrent()

            vm.onAttendantCashReceived()
            runCurrent()

            assertTrue(state(vm) is TransactionState.Complete)
            val sale = harness.transactions.saved.single()
            assertEquals(localRef, sale.id)
            assertEquals(10_000L, sale.amountKobo)
        }

    /**
     * Both clocks stop at the cancel. Without that, the countdown would later write a second,
     * "window closed" row for a sale the attendant had already moved to cash — and the poller could
     * still deliver a late Success into a screen that is collecting cash for the same tank.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `after the cancel neither the countdown nor the poller ends the sale again`() =
        runTest(mainRule.dispatcher) {
            val vm = harness.build()
            fillupToUnpaidQr(vm)
            vm.onFillupDigitalCancel()
            runCurrent()

            harness.payment.succeed()
            advanceTimeBy(301_000)
            runCurrent()

            assertTrue(state(vm) is TransactionState.FillupAwaitingCashConfirm)
            assertEquals(1, harness.events.recorded.count { it.type == EventType.PAYMENT_ABANDONED })
        }

    /**
     * The rule lives in the view model, not in which buttons a screen happens to show: the generic
     * cancel cannot drop a fill-up past shutoff, from any of its three states.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the generic cancel cannot drop a fill-up with fuel in the tank`() =
        runTest(mainRule.dispatcher) {
            val vm = harness.build()
            vm.onAttendantFillUpAuthorise()
            harness.pulseSource.emitPulse(count = 10)
            vm.onSimulateNozzleShutoff()
            runCurrent()

            vm.onCancel()
            assertTrue("tank full dropped", state(vm) is TransactionState.FillupTankFull)

            vm.onFillupPayCash()
            runCurrent()
            vm.onCancel()
            assertTrue(
                "cash confirm dropped",
                state(vm) is TransactionState.FillupAwaitingCashConfirm,
            )
            assertTrue(harness.transactions.saved.isEmpty())
        }

    /** And from the QR, the generic cancel takes the same route the button does. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the generic cancel on a fill-up QR goes to cash collection`() =
        runTest(mainRule.dispatcher) {
            val vm = harness.build()
            fillupToUnpaidQr(vm)

            vm.onCancel()
            runCurrent()

            assertTrue(state(vm) is TransactionState.FillupAwaitingCashConfirm)
        }

    // ---- the timed-out card clears itself (#R11, the boss's decision 2026-09-22) ----------------

    private fun prepayToQr(vm: CustomerViewModel) {
        vm.onStartTransaction()
        vm.onModeTileTap(TransactionMode.PRE_PAY)
        vm.onAmountTileTap(amountNaira = 5000)
        vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
        vm.onModeConfirm()
    }

    /**
     * Two minutes, then Idle. A customer standing at the pump reads "if you have already paid,
     * please see the attendant"; an empty forecourt resets itself instead of greeting the next
     * customer with an error.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a timed-out prepay card returns to Idle by itself after two minutes`() =
        runTest(mainRule.dispatcher) {
            val vm = harness.build()
            prepayToQr(vm)
            harness.payment.fail(reason = "the payment window elapsed", windowElapsed = true)
            runCurrent()
            assertTrue(state(vm) is TransactionState.Error)

            advanceTimeBy(119_000)
            runCurrent()
            assertTrue("cleared before its two minutes", state(vm) is TransactionState.Error)

            advanceTimeBy(2_000)
            runCurrent()
            assertTrue("still on the card after two minutes", state(vm) is TransactionState.Idle)
        }

    /**
     * Only the timed-out card. A refusal may be something a person has to read and act on, and the
     * boss's decision was about the customer who walked away, not about every error.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a declined payment's card still waits for a tap`() =
        runTest(mainRule.dispatcher) {
            val vm = harness.build()
            prepayToQr(vm)
            harness.payment.fail(reason = "the card was declined")
            runCurrent()

            advanceTimeBy(10 * 60_000)
            runCurrent()

            assertTrue(state(vm) is TransactionState.Error)
        }

    /**
     * A timer left over from a card someone already tapped away must not act on whatever came next.
     * It clears only the card it was started for.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a stale timer never resets a sale started after Start over`() =
        runTest(mainRule.dispatcher) {
            val vm = harness.build()
            prepayToQr(vm)
            harness.payment.fail(reason = "the payment window elapsed", windowElapsed = true)
            runCurrent()

            vm.onCancel() // "Start over"
            vm.onStartTransaction()
            assertTrue(state(vm) is TransactionState.ModeSelect)

            advanceTimeBy(3 * 60_000)
            runCurrent()

            assertTrue("the old card's timer reset a new sale", state(vm) is TransactionState.ModeSelect)
        }

    /** A restart resumes the card's deadline; it does not grant a fresh two minutes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a restored timed-out card clears at its original deadline`() =
        runTest(mainRule.dispatcher) {
            harness.pulseRepo.stateToRestore = TransactionState.Error(
                message = "This pump stopped waiting for the payment.",
                recoverable = true,
                autoDismissAtEpochMs = System.currentTimeMillis() + 30_000,
            )
            val vm = harness.build()
            runCurrent()
            assertTrue(state(vm) is TransactionState.Error)

            advanceTimeBy(31_000)
            runCurrent()

            assertTrue("a restart granted a fresh two minutes", state(vm) is TransactionState.Idle)
        }

    /** And one whose time ran out while the tablet was off clears as soon as the app is back. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a timed-out card whose deadline passed during the outage clears at once`() =
        runTest(mainRule.dispatcher) {
            harness.pulseRepo.stateToRestore = TransactionState.Error(
                message = "This pump stopped waiting for the payment.",
                recoverable = true,
                autoDismissAtEpochMs = System.currentTimeMillis() - 1_000,
            )
            val vm = harness.build()
            runCurrent()

            assertTrue(state(vm) is TransactionState.Idle)
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

    /**
     * **The boot sync runs on a bare `viewModelScope.launch`, so anything it throws is uncaught
     * on every boot** (re-review #R6). A pump whose database had gone bad could not open the app
     * — and a forecourt tablet that cannot open the app cannot take cash either, over a field
     * only digital sales consult.
     *
     * The relay-open invariant is asserted here too, because it is what makes soldiering on the
     * right answer rather than a hopeful one: the pump is safe before anything that can fail runs.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a database that cannot be read does not stop the app booting`() =
        runTest(mainRule.dispatcher) {
            harness.deviceConfig.failReads = true

            val vm = harness.build()
            runCurrent()

            assertTrue(state(vm) is TransactionState.Idle)
            assertFalse("fuel could flow with no state restored", harness.relay.isDispensing.value)
        }
}
