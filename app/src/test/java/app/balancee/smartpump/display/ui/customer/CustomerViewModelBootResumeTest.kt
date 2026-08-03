// Phase 8 — boot-resume paths of CustomerViewModel.
// The VM's init runs bootResume() eagerly; each test seeds the persisted state on the fake
// PulseRepository BEFORE constructing the VM, then asserts the resumed state + side effects.
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CustomerViewModelBootResumeTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val harness = VmHarness()
    private fun state(vm: CustomerViewModel) = vm.ui.value.state

    // ---- terminal / pure-UI restores ---------------------------------------------

    @Test
    fun `restoring Complete resets to Idle (customer never tapped Done)`() {
        harness.pulseRepo.stateToRestore = TransactionState.Complete(
            flow = TransactionFlow.CASH_FIXED, txnId = "BLC-OLD", litres = 3.0, amountKobo = 300_000,
        )
        val vm = harness.build()
        assertTrue(state(vm) is TransactionState.Idle)
        assertEquals(0 to 0L, harness.pulseRepo.lastSavedPulseCount) // pulses cleared
    }

    @Test
    fun `restoring a recoverable Error keeps the Error on screen`() {
        harness.pulseRepo.stateToRestore =
            TransactionState.Error(message = "Payment failed — timeout.", recoverable = true)
        val vm = harness.build()
        val s = state(vm) as TransactionState.Error
        assertTrue(s.recoverable)
    }

    @Test
    fun `restoring a non-recoverable Error falls back to Idle`() {
        harness.pulseRepo.stateToRestore =
            TransactionState.Error(message = "fatal", recoverable = false)
        val vm = harness.build()
        assertTrue(state(vm) is TransactionState.Idle)
    }

    @Test
    fun `restoring a pure-UI ModeSelect dispatches it verbatim`() {
        val restored = TransactionState.ModeSelect(
            mode = TransactionMode.PRE_PAY, amountKobo = 500_000, method = PaymentMethod.BALANCEE_APP,
        )
        harness.pulseRepo.stateToRestore = restored
        val vm = harness.build()
        assertEquals(restored, state(vm))
    }

    // ---- dispensing restores restart the relay + collector -----------------------

    @Test
    fun `restoring FixedDispensing restarts the dispense from the persisted baseline`() {
        harness.pulseRepo.stateToRestore = TransactionState.FixedDispensing(
            flow = TransactionFlow.FIXED_PREPAY_DIGITAL, txnId = "BLC-R1",
            priceKoboPerLitre = TEST_KOBO_PER_LITRE, amountKobo = 500_000,
            litresAuthorised = 5.0, litresSoFar = 2.0, method = PaymentMethod.BALANCEE_APP,
        )
        harness.pulseRepo.pulsesToRestore = 200 // 2.0 L already flowed before the power cut
        val vm = harness.build()

        assertTrue(state(vm) is TransactionState.FixedDispensing)
        assertTrue(harness.relay.isDispensing.value) // relay re-opened

        // Session pulses restart at 0; baseline 200 + 300 = 500 pulses = 5.0 L → target.
        harness.pulseSource.emitPulse(count = 300)
        val done = state(vm) as TransactionState.Complete
        assertEquals(5.0, done.litres, 0.0)
        assertEquals("BLC-R1", done.txnId)
        assertFalse(harness.relay.isDispensing.value)
    }

    @Test
    fun `restoring CashFixedDispensing restarts toward its cutoff`() {
        harness.pulseRepo.stateToRestore = TransactionState.CashFixedDispensing(
            txnId = "BLC-R2", priceKoboPerLitre = TEST_KOBO_PER_LITRE,
            cashAmountKobo = 300_000, litresCutoff = 3.0, litresSoFar = 1.0,
        )
        harness.pulseRepo.pulsesToRestore = 100
        val vm = harness.build()

        assertTrue(state(vm) is TransactionState.CashFixedDispensing)
        assertTrue(harness.relay.isDispensing.value)

        harness.pulseSource.emitPulse(count = 200) // 100 + 200 = 300 = 3.0 L
        assertTrue(state(vm) is TransactionState.Complete)
    }

    @Test
    fun `restoring FillupDispensing re-opens the relay for the open-ended fill`() {
        harness.pulseRepo.stateToRestore = TransactionState.FillupDispensing(
            txnId = "BLC-R3", priceKoboPerLitre = TEST_KOBO_PER_LITRE, litresSoFar = 1.5,
        )
        harness.pulseRepo.pulsesToRestore = 150
        val vm = harness.build()

        assertTrue(state(vm) is TransactionState.FillupDispensing)
        assertTrue(harness.relay.isDispensing.value)
    }
}
