// Phase 10c — what reaches the screen once a real processor answers.
//
// Two things the app got wrong for a year, both asserted here rather than described:
//  - the QR encoded a payload this app invented, which no scanner resolves and no bank honours;
//  - the expiry countdown ran on a five-minute constant while production gives twenty (#43).
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class CustomerViewModelCheckoutTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val harness = VmHarness()
    private fun state(vm: CustomerViewModel) = vm.ui.value.state

    private val checkout = "https://checkout.paystack.com/jn0ej3u6def5150"

    private fun startPrepay(vm: CustomerViewModel) {
        vm.onStartTransaction()
        vm.onModeTileTap(TransactionMode.PRE_PAY)
        vm.onAmountTileTap(amountNaira = 5000)
        vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
        vm.onModeConfirm()
    }

    // ---- the QR ------------------------------------------------------------------

    /**
     * The checkout URL has to survive onto the state, because the state is what the screen renders
     * and what a restart restores. Before 10c nothing carried it and the screen made a payload up.
     */
    @Test
    fun `the checkout url reaches the awaiting-payment state`() {
        harness.payment.pendingCheckoutUrl = checkout
        val vm = harness.build()

        startPrepay(vm)

        val s = state(vm) as TransactionState.PrepayAwaitingPayment
        assertEquals(checkout, s.checkoutUrl)
    }

    /**
     * A processor that returns no URL must leave it null rather than substituting something. The
     * screen shows the reference instead — a QR-shaped hole a customer stands in front of is worse
     * than a visible failure.
     */
    @Test
    fun `no checkout url leaves the state null rather than fabricating one`() {
        harness.payment.pendingCheckoutUrl = null
        val vm = harness.build()

        startPrepay(vm)

        val s = state(vm) as TransactionState.PrepayAwaitingPayment
        assertNull(s.checkoutUrl)
    }

    // ---- the expiry (#43) --------------------------------------------------------

    /**
     * The countdown runs on the server's window. Twenty minutes is what production issues; the app
     * said five in three places, and a screen that gave up at five abandoned a sale the server would
     * have honoured for another fifteen, with the customer standing at the pump.
     */
    @Test
    fun `the countdown runs on the server's expiry, not the five-minute constant`() {
        harness.payment.pendingExpiresAt = Instant.now().plusSeconds(20 * 60)
        val vm = harness.build()

        startPrepay(vm)

        // Within a second or two of twenty minutes, allowing for the clock read between the two.
        val seconds = vm.ui.value.prepayExpiresInSeconds
        assertTrue("expected ~1200s, got $seconds", seconds in 1195..1200)
    }

    /** No server expiry — a mock, or a response that omitted it — falls back to the old constant. */
    @Test
    fun `an absent server expiry falls back to the built-in window`() {
        harness.payment.pendingExpiresAt = null
        val vm = harness.build()

        startPrepay(vm)

        assertEquals(5 * 60, vm.ui.value.prepayExpiresInSeconds)
    }

    /**
     * A deadline already past must not run the countdown negative. It ends the sale, which is what
     * an expired authorisation means.
     */
    @Test
    fun `an expiry already in the past ends the sale rather than counting backwards`() = runTest(mainRule.dispatcher) {
        harness.payment.pendingExpiresAt = Instant.now().minusSeconds(60)
        val vm = harness.build()

        startPrepay(vm)
        advanceTimeBy(2_000L)

        assertTrue(state(vm) is TransactionState.Idle)
    }

    /**
     * The server's window keeps running through a restart, so a resumed sale resumes against the
     * persisted deadline. Granting a fresh one here would keep a QR on screen after the server had
     * stopped honouring it.
     */
    @Test
    fun `a restored sale resumes the server's window rather than starting a new one`() {
        val deadline = Instant.now().plusSeconds(120)
        harness.pulseRepo.stateToRestore = TransactionState.PrepayAwaitingPayment(
            flow = TransactionFlow.FIXED_PREPAY_DIGITAL,
            amountKobo = 500_000,
            method = PaymentMethod.BALANCEE_APP,
            txnId = "BLC-RESUME",
            priceKoboPerLitre = TEST_KOBO_PER_LITRE,
            checkoutUrl = checkout,
            expiresAtEpochMs = deadline.toEpochMilli(),
        )

        val vm = harness.build()

        val seconds = vm.ui.value.prepayExpiresInSeconds
        assertTrue("expected ~120s remaining, got $seconds", seconds in 115..120)
        assertEquals(checkout, (state(vm) as TransactionState.PrepayAwaitingPayment).checkoutUrl)
    }
}
