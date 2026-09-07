// Phase 8 — dispensing completion paths of CustomerViewModel.
// Verifies each fixed-target flow stops at its litre target, closes the relay, and that the
// live litre count tracks pulses; plus the fill-up nozzle-shutoff → tank-full transition.
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.hardware.PULSES_PER_LITRE
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CustomerViewModelDispensingTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val harness = VmHarness()
    private fun state(vm: CustomerViewModel) = vm.ui.value.state

    /**
     * Pulses that represent [litres] at the CURRENT meter K-factor.
     *
     * These tests used to hardcode the pulse counts (380 for 3.8 L), which silently pinned
     * PULSES_PER_LITRE = 100 into assertions that are not about the K-factor at all. The
     * constant is a placeholder awaiting T-01 calibration and is expected to change, so the
     * tests derive from it instead of restating it.
     */
    private fun pulsesFor(litres: Double) = Math.round(litres * PULSES_PER_LITRE).toInt()

    /**
     * Litres that [pulses] actually represent. Needed because not every litre figure is
     * representable at every K-factor - 3.8 L needs 0.2 L granularity, which a low
     * PULSES_PER_LITRE does not have. Asserting against this keeps the test about the real
     * invariant (litres = pulses / K) rather than about one convenient constant.
     */
    private fun litresOf(pulses: Int) = pulses.toDouble() / PULSES_PER_LITRE

    /** Drive a pre-pay dispense up to (not through) the litre target. */
    private fun startPrepayDispense(vm: CustomerViewModel) {
        vm.onStartTransaction()
        vm.onModeTileTap(TransactionMode.PRE_PAY)
        vm.onAmountTileTap(amountNaira = 5000) // ₦5000 → 5.0 L @ ₦1000/L
        vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
        vm.onModeConfirm()
        harness.payment.succeed()
    }

    // ---- fixed / pre-pay ----------------------------------------------------------

    @Test
    fun `fixed dispense tracks litres then stops exactly at the authorised target`() {
        val vm = harness.build()
        startPrepayDispense(vm)

        assertTrue(state(vm) is TransactionState.FixedDispensing)
        assertTrue(harness.relay.isDispensing.value)

        harness.pulseSource.emitPulse(count = pulsesFor(2.5)) // mid-dispense
        assertEquals(2.5, (state(vm) as TransactionState.FixedDispensing).litresSoFar, 0.0)

        harness.pulseSource.emitPulse(count = pulsesFor(5.0)) // target
        val done = state(vm) as TransactionState.Complete
        assertEquals(5.0, done.litres, 0.0)
        assertEquals(TransactionFlow.FIXED_PREPAY_DIGITAL, done.flow)
        assertFalse(harness.relay.isDispensing.value) // relay closed on completion
    }

    @Test
    fun `fixed dispense does not overrun past the target on a large pulse jump`() {
        val vm = harness.build()
        startPrepayDispense(vm)

        harness.pulseSource.emitPulse(count = pulsesFor(9.0)) // >> 5.0 target
        val done = state(vm) as TransactionState.Complete
        assertEquals(5.0, done.litres, 0.0) // billed at the target, never the overrun
    }

    // ---- cash fixed ---------------------------------------------------------------

    @Test
    fun `cash-fixed dispense stops at the computed cutoff`() {
        val vm = harness.build()
        vm.onAttendantCashFixed()
        vm.onCashFixedAuthorise(cashAmountKobo = 300_000) // 3.0 L

        assertTrue(state(vm) is TransactionState.CashFixedDispensing)
        assertTrue(harness.relay.isDispensing.value)

        harness.pulseSource.emitPulse(count = pulsesFor(3.0)) // cutoff
        val done = state(vm) as TransactionState.Complete
        assertEquals(3.0, done.litres, 0.0)
        assertEquals(TransactionFlow.CASH_FIXED, done.flow)
        assertFalse(harness.relay.isDispensing.value)
    }

    // ---- fill-up ------------------------------------------------------------------

    @Test
    fun `fill-up authorise opens the relay and counts open-ended`() {
        val vm = harness.build()
        vm.onAttendantFillUpAuthorise()

        assertTrue(state(vm) is TransactionState.FillupDispensing)
        assertTrue(harness.relay.isDispensing.value)

        val pulses = pulsesFor(3.8) // no target
        harness.pulseSource.emitPulse(count = pulses)
        assertEquals(litresOf(pulses), (state(vm) as TransactionState.FillupDispensing).litresSoFar, 1e-9)
    }

    @Test
    fun `fill-up nozzle shutoff locks verified litres and amount due, closing the relay`() {
        val vm = harness.build()
        vm.onAttendantFillUpAuthorise()
        val pulses = pulsesFor(3.8)
        harness.pulseSource.emitPulse(count = pulses)
        vm.onSimulateNozzleShutoff()

        val litres = litresOf(pulses)
        val full = state(vm) as TransactionState.FillupTankFull
        assertEquals(litres, full.verifiedLitres, 1e-9)
        assertEquals(Math.round(litres * TEST_KOBO_PER_LITRE), full.amountDueKobo)
        assertFalse(harness.relay.isDispensing.value)
    }
}
