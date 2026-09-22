// TODO #54 — a finished sale must stop collecting pulses.
//
// Found on hardware at the 11f bench gate (2026-09-22): a cash sale that completed at 19:20 was
// still subscribed to the pulse stream at 19:35, and when a LATER fill-up hit its ceiling the stale
// collector processed that sale's STOP as its own:
//
//     VM STOP cash    stopped=20322 lastPulse=998 limit=1000   <- the ghost
//     VM STOP fill-up CEILING stopped=20000 limit=20000        <- the real one
//
// It was visible at all because a mid-sale USB replug had built a second activity (and so a second
// view model) beside the first — the manifest's launchMode now prevents that. These tests cover the
// other half: the collector ends itself when its sale does, so one leaked view model cannot leave a
// listener on the adapter for the next customer's fuel.
//
// The end is not immediate by design: `takeWhile` closes the flow on the next frame, after the
// completion has run to the end. Self-cancelling from inside the collector would truncate the
// completion at its next suspension point (the #R9 trap). In production the adapter's ~2 s
// heartbeat provides that frame whether or not fuel is moving; here the tests emit it.
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.hardware.PULSES_PER_LITRE
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CustomerViewModelCollectorLifetimeTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val harness = VmHarness()
    private fun state(vm: CustomerViewModel) = vm.ui.value.state
    private fun pulsesFor(litres: Double) = Math.round(litres * PULSES_PER_LITRE).toInt()

    @Test
    fun `a completed pre-pay sale stops collecting pulses`() {
        val vm = harness.build()
        vm.onStartTransaction()
        vm.onModeTileTap(TransactionMode.PRE_PAY)
        vm.onAmountTileTap(amountNaira = 5000) // ₦5000 → 5.0 L @ ₦1000/L
        vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
        vm.onModeConfirm()
        harness.payment.succeed()
        assertEquals(1, harness.pulseSource.collectors)

        harness.pulseSource.emitPulse(count = pulsesFor(5.0)) // the target
        assertTrue(state(vm) is TransactionState.Complete)

        harness.pulseSource.emitHeartbeat() // the adapter keeps talking with the relay shut
        assertEquals(0, harness.pulseSource.collectors)
    }

    @Test
    fun `a completed cash-fixed sale stops collecting pulses`() {
        val vm = harness.build()
        vm.onAttendantCashFixed()
        vm.onCashFixedAuthorise(cashAmountKobo = 300_000) // 3.0 L
        assertEquals(1, harness.pulseSource.collectors)

        harness.pulseSource.emitPulse(count = pulsesFor(3.0)) // the cutoff
        assertTrue(state(vm) is TransactionState.Complete)

        harness.pulseSource.emitHeartbeat()
        assertEquals(0, harness.pulseSource.collectors)
    }

    /** The adapter's own cut (`STOP`), which is how a fixed sale ends on real hardware. */
    @Test
    fun `an adapter-stopped sale stops collecting pulses`() {
        val vm = harness.build()
        vm.onAttendantCashFixed()
        vm.onCashFixedAuthorise(cashAmountKobo = 300_000) // 3.0 L

        harness.pulseSource.emitStopped(count = pulsesFor(3.0))
        assertTrue(state(vm) is TransactionState.Complete)

        harness.pulseSource.emitHeartbeat()
        assertEquals(0, harness.pulseSource.collectors)
    }

    /**
     * A regression guard, and honest about what it does not prove: **this one passes without the
     * fix too** (verified by re-running it against the pre-fix view model, where the other three
     * fail). Starting a sale already calls `dispenseJob?.cancel()`, so within a single view model
     * the old collector was never the problem — the leak needed a SECOND view model, which is why
     * it took a USB replug on real hardware to expose it and why the manifest change is the other
     * half of #54. Kept so that a future change cannot quietly start a sale with two collectors
     * running.
     */
    @Test
    fun `a second sale runs with only its own collector`() {
        val vm = harness.build()
        vm.onAttendantCashFixed()
        vm.onCashFixedAuthorise(cashAmountKobo = 300_000) // 3.0 L
        harness.pulseSource.emitPulse(count = pulsesFor(3.0))
        harness.pulseSource.emitHeartbeat()
        vm.onDismissComplete()

        vm.onAttendantCashFixed()
        vm.onCashFixedAuthorise(cashAmountKobo = 200_000) // 2.0 L
        assertTrue(state(vm) is TransactionState.CashFixedDispensing)
        assertEquals(1, harness.pulseSource.collectors)
    }
}
