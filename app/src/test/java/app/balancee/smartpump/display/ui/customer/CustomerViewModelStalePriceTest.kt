// Phase 10g — the price the customer is shown must be the price the customer is charged.
//
// These cover a defect found on production on 2026-09-19, not a hypothetical. The tablet was
// holding the ₦870 debug seed; `/config` returned ₦1,490; the sync stored ₦1,490 in the database
// and the app went on quoting ₦870 on screen. The sale that resulted carried ₦2,007.03 on the wire
// against a state that said ₦870/L — and that state is what the receipt and the completion screen
// read (#37).
//
// Nothing in the suite broke when this was fixed, because nothing had ever asserted where that
// price came from. That is what these are for.
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CustomerViewModelStalePriceTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val harness = VmHarness()
    private fun state(vm: CustomerViewModel) = vm.ui.value.state

    /** What production was really running on the day. */
    private val serverPrice = 149_000L

    /** A live pre-pay dispense, struck at [TEST_KOBO_PER_LITRE] before the power cut. */
    private val dispensing = TransactionState.FixedDispensing(
        flow = TransactionFlow.FIXED_PREPAY_DIGITAL,
        txnId = "TXN-1",
        amountKobo = 500_000,
        litresAuthorised = 5.0,
        litresSoFar = 1.0,
        priceKoboPerLitre = TEST_KOBO_PER_LITRE,
        method = PaymentMethod.BALANCEE_APP,
        startedAtEpochMs = 1_700_000_000_000,
    )

    // ---- the boot guard ------------------------------------------------------------

    /**
     * The original guard asked whether the state was `Idle`. A tablet that restores to
     * [TransactionState.ModeSelect] — a customer standing at the screen who has chosen nothing —
     * is not `Idle`, so the refreshed price reached the database and never reached the screen.
     *
     * This is the exact restore the tablet performed at the 10g gate.
     */
    @Test
    fun `a synced price reaches the screen when the pump restored to mode select`() {
        harness.pulseRepo.stateToRestore = TransactionState.ModeSelect()
        harness.deviceConfigSync.priceToSync = serverPrice

        val vm = harness.build()

        assertEquals(
            "the server's price stopped at the database and never reached the screen",
            serverPrice,
            vm.ui.value.priceKoboPerLitre,
        )
    }

    /** The case that always worked, kept so a fix to the above cannot quietly break it. */
    @Test
    fun `a synced price reaches the screen when the pump restored to idle`() {
        harness.pulseRepo.stateToRestore = TransactionState.Idle
        harness.deviceConfigSync.priceToSync = serverPrice

        val vm = harness.build()

        assertEquals(serverPrice, vm.ui.value.priceKoboPerLitre)
    }

    /**
     * The half of the guard that must NOT be lost. A sale already quoted has struck its price and
     * its litre target; moving the figure under a customer mid-sale makes the screen disagree with
     * the sale they are watching. Widening the guard to cover pre-sale states must not widen it to
     * this one.
     *
     * **`holdAdapterCount` is what makes this test mean anything** (review #6). Without it the
     * fakes resolve on the same tick, the resume finishes before the sync starts, and the guard is
     * asked its question after the answer exists — which is not the order production runs in. See
     * the test below for the order that matters.
     */
    @Test
    fun `a synced price does not move under a dispense in progress`() {
        harness.pulseRepo.stateToRestore = dispensing
        harness.deviceConfigSync.priceToSync = serverPrice
        harness.pulseSource.holdAdapterCount()

        val vm = harness.build()
        harness.pulseSource.releaseAdapterCount()

        assertEquals(
            "the price moved under a customer already watching a dispense",
            TEST_KOBO_PER_LITRE,
            vm.ui.value.priceKoboPerLitre,
        )
    }

    /**
     * Review #6, stated as the sequence rather than as the symptom.
     *
     * `bootResume` waits on the adapter for up to 3 s before it dispatches anything, so for that
     * whole window `_ui.value.state` is the `Idle` the view model was constructed with. A `/config`
     * answering in a few hundred milliseconds asked the guard its question before the guard could
     * be right, was told the pump was idle, and moved the price under a struck sale.
     *
     * This asserts the price at the moment the sync would have applied it — while the resume is
     * still held — so it fails on the race itself rather than on whatever survives afterwards.
     */
    @Test
    fun `a sync that lands mid-resume does not move the price before the resume has answered`() {
        harness.pulseRepo.stateToRestore = dispensing
        harness.deviceConfigSync.priceToSync = serverPrice
        harness.pulseSource.holdAdapterCount()

        val vm = harness.build()

        assertEquals(
            "the price moved while the app still believed it was idle",
            TEST_KOBO_PER_LITRE,
            vm.ui.value.priceKoboPerLitre,
        )

        harness.pulseSource.releaseAdapterCount()
        assertEquals(TEST_KOBO_PER_LITRE, vm.ui.value.priceKoboPerLitre)
    }

    /**
     * The fix must not be "wait for the resume before asking", which would put a network round trip
     * behind a 3 s adapter wait on every single boot. The fetch goes out concurrently and only the
     * decision waits — so by the time the resume is released, the server has already been asked.
     */
    @Test
    fun `the config fetch still overlaps the resume rather than queueing behind it`() {
        harness.pulseRepo.stateToRestore = dispensing
        harness.deviceConfigSync.priceToSync = serverPrice
        harness.pulseSource.holdAdapterCount()

        harness.build()

        assertEquals(
            "the fetch waited for the resume instead of overlapping it",
            1,
            harness.deviceConfigSync.refreshCount,
        )
    }

    /**
     * The other direction, and the one a fix like this is most likely to break: a pump that
     * restores to a state with nothing struck must still take the price, even though the sync now
     * waits for the resume before applying it.
     */
    @Test
    fun `a sync held behind a slow resume still reaches an idle screen`() {
        harness.pulseRepo.stateToRestore = TransactionState.ModeSelect()
        harness.pulseRepo.anchorToRestore = 4_000L    // forces the adapter wait on a non-dispensing restore
        harness.deviceConfigSync.priceToSync = serverPrice
        harness.pulseSource.holdAdapterCount()

        val vm = harness.build()
        harness.pulseSource.releaseAdapterCount()

        assertEquals(
            "the sync was dropped rather than deferred",
            serverPrice,
            vm.ui.value.priceKoboPerLitre,
        )
    }

    // ---- the struck price ----------------------------------------------------------

    /**
     * The state's price is derived from the quote rather than read from the view model's field.
     *
     * The quote's amount and litres are struck together against the price the processor fetched,
     * so their ratio **is** that price. Reading the field instead is what put ₦870 on a sale
     * charged at ₦1,490 — and the state is what the receipt and completion screen read.
     *
     * The processor here is deliberately quoting at a price the view model does not hold.
     */
    @Test
    fun `the awaiting-payment state carries the price the quote was struck at`() {
        // ₦2,007.03 for 1.347 L — the real quote from the gate, which is ₦1,490/L exactly.
        harness.payment.pendingAmountKobo = 200_703
        harness.payment.pendingLitres = 1.347
        val vm = harness.build() // the view model's own field is TEST_KOBO_PER_LITRE

        vm.onStartTransaction()
        vm.onModeTileTap(TransactionMode.PRE_PAY)
        vm.onAmountTileTap(amountNaira = 2_008)
        vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
        vm.onModeConfirm()

        val s = state(vm) as TransactionState.PrepayAwaitingPayment
        assertEquals(
            "the receipt would have shown a price the customer was not charged",
            serverPrice,
            s.priceKoboPerLitre,
        )
        assertEquals(200_703, s.amountKobo)
    }

    /**
     * A quote that buys no litres cannot have its price derived — dividing by it is the one thing
     * that must not happen. The stored figure stands instead.
     */
    @Test
    fun `a quote with no litres falls back to the stored price rather than dividing by zero`() {
        harness.payment.pendingAmountKobo = 200_703
        harness.payment.pendingLitres = 0.0
        val vm = harness.build()

        vm.onStartTransaction()
        vm.onModeTileTap(TransactionMode.PRE_PAY)
        vm.onAmountTileTap(amountNaira = 2_008)
        vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
        vm.onModeConfirm()

        val s = state(vm) as TransactionState.PrepayAwaitingPayment
        assertEquals(TEST_KOBO_PER_LITRE, s.priceKoboPerLitre)
    }
}
