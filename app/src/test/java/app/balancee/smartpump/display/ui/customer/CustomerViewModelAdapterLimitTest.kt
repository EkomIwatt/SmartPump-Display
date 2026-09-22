// Phase 11d — the limit each flow arms the adapter with. The adapter stops the fuel at this number
// by itself (docs/serial-protocol.md), so it has to be what was paid for: never more, and not a
// pulse less either, or the sale waits on a completion that never comes.
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.hardware.FILLUP_CEILING_LITRES
import app.balancee.smartpump.display.domain.hardware.litresToLimitPulses
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CustomerViewModelAdapterLimitTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val harness = VmHarness()

    @Test
    fun `pre-pay arms the adapter with the litres paid for`() {
        val vm = harness.build()
        vm.onStartTransaction()
        vm.onModeTileTap(TransactionMode.PRE_PAY)
        vm.onAmountTileTap(amountNaira = 5000) // ₦5000 → 5.0 L @ ₦1000/L
        vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
        vm.onModeConfirm()
        harness.payment.succeed()

        assertTrue(vm.ui.value.state is TransactionState.FixedDispensing)
        assertEquals(litresToLimitPulses(5.0), harness.relay.lastLimit)
    }

    @Test
    fun `cash-fixed arms the adapter with its cutoff`() {
        val vm = harness.build()
        vm.onAttendantCashFixed()
        vm.onCashFixedAuthorise(cashAmountKobo = 300_000)

        // Whatever cutoff the sale holds, the adapter is armed with exactly that many litres.
        // (Asserted against the state rather than a literal: DeviceConfig.litresCutoff has its own
        // floating-point floor defect, boarded separately — at ₦1,000/L, ₦1,150 floors to 1.14 L.)
        val sale = vm.ui.value.state as TransactionState.CashFixedDispensing
        assertEquals(litresToLimitPulses(sale.litresCutoff), harness.relay.lastLimit)
    }

    @Test
    fun `fill-up arms the adapter with the runaway ceiling`() {
        val vm = harness.build()
        vm.onAttendantFillUpAuthorise()

        assertTrue(vm.ui.value.state is TransactionState.FillupDispensing)
        assertEquals(litresToLimitPulses(FILLUP_CEILING_LITRES), harness.relay.lastLimit)
    }

    @Test
    fun `a resumed sale is armed only for what is left, never its full allowance again`() {
        harness.pulseRepo.stateToRestore = TransactionState.FixedDispensing(
            flow = TransactionFlow.FIXED_PREPAY_DIGITAL, txnId = "BLC-R1",
            priceKoboPerLitre = TEST_KOBO_PER_LITRE, amountKobo = 500_000,
            litresAuthorised = 5.0, litresSoFar = 2.0, method = PaymentMethod.BALANCEE_APP,
        )
        harness.pulseRepo.pulsesToRestore = 200 // already delivered before the restart
        harness.build()

        assertEquals(litresToLimitPulses(5.0) - 200, harness.relay.lastLimit)
    }

    @Test
    fun `a resumed cash-fixed sale is armed only for what is left`() {
        harness.pulseRepo.stateToRestore = TransactionState.CashFixedDispensing(
            txnId = "BLC-R2", priceKoboPerLitre = TEST_KOBO_PER_LITRE,
            cashAmountKobo = 300_000, litresCutoff = 3.0, litresSoFar = 1.0,
        )
        harness.pulseRepo.pulsesToRestore = 100
        harness.build()

        assertEquals(litresToLimitPulses(3.0) - 100, harness.relay.lastLimit)
    }

    @Test
    fun `every sale gets its own non-zero tag`() {
        val vm = harness.build()
        vm.onAttendantCashFixed()
        vm.onCashFixedAuthorise(cashAmountKobo = 100_000)
        harness.pulseSource.emitPulse(count = litresToLimitPulses(1.0).toInt()) // completes
        vm.onDismissComplete()
        vm.onAttendantCashFixed()
        vm.onCashFixedAuthorise(cashAmountKobo = 100_000)

        val tags = harness.relay.arms.map { it.second }
        assertEquals(2, tags.size)
        assertTrue(tags.all { it in 1..0xFFFF_FFFFL })
        assertNotEquals(tags[0], tags[1])
    }
}
