// Phase 8 — job lifecycle & safety invariants of CustomerViewModel.
// Covers the relay-open-on-boot invariant, cancel teardown (jobs cancelled, relay closed, pulses
// cleared), and the pre-pay expiry timeout that auto-cancels an unpaid transaction.
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
