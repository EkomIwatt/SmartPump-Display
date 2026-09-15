// Phase 8 — money maths & litre-cutoff behaviour of CustomerViewModel.
// Covers the cash-fixed authorise cutoff (incl. the "never dispense more than paid" floor),
// the below-minimum rejection, and the audit record written on completion.
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.FuelType
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CustomerViewModelMoneyTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val harness = VmHarness()
    private fun state(vm: CustomerViewModel) = vm.ui.value.state

    // ---- cutoff computation -------------------------------------------------------

    @Test
    fun `cash-fixed cutoff is amount over price`() {
        val vm = harness.build()
        vm.onAttendantCashFixed()
        vm.onCashFixedAuthorise(cashAmountKobo = 500_000) // ₦5000 @ ₦1000/L

        val s = state(vm) as TransactionState.CashFixedDispensing
        assertEquals(5.0, s.litresCutoff, 0.0)
        assertEquals(500_000, s.cashAmountKobo)
    }

    @Test
    fun `cash-fixed cutoff floors to 0_01L so we never dispense more than paid`() {
        val vm = harness.build()
        vm.onAttendantCashFixed()
        // ₦2500.999 → 2.50099 L; must floor DOWN to 2.50, not round to 2.51.
        vm.onCashFixedAuthorise(cashAmountKobo = 250_099)

        val s = state(vm) as TransactionState.CashFixedDispensing
        assertEquals(2.50, s.litresCutoff, 0.0)
    }

    @Test
    fun `cash-fixed below the minimum dispense is rejected as recoverable error`() {
        val vm = harness.build()
        vm.onAttendantCashFixed()
        vm.onCashFixedAuthorise(cashAmountKobo = 500) // ₦5 → 0.005 L → floors to 0.0

        val s = state(vm) as TransactionState.Error
        assertTrue(s.recoverable)
        // Customer-facing wording, not the diagnostic number. The attendant typed this amount, so
        // the actionable detail is theirs and belongs behind the PIN once there is somewhere to put
        // it (OQ #17 item 3). Asserted loosely on purpose — the exact prose is the copy draft's
        // business, but it must stay pointed at a person rather than at the arithmetic.
        assertTrue(s.message.contains("attendant", ignoreCase = true))
        // No dispense should have started.
        assertEquals(0, harness.relay.startCount)
    }

    @Test
    fun `cash-fixed authorise is ignored unless in amount-entry state`() {
        val vm = harness.build() // Idle after boot
        vm.onCashFixedAuthorise(cashAmountKobo = 500_000)
        assertTrue(state(vm) is TransactionState.Idle)
    }

    // ---- audit record on completion ----------------------------------------------

    @Test
    fun `completed cash-fixed writes an accurate audit record`() {
        val vm = harness.build()
        vm.onAttendantCashFixed()
        vm.onCashFixedAuthorise(cashAmountKobo = 500_000)
        harness.pulseSource.emitPulse(count = 500) // 5.0 L → hits cutoff → Complete

        val record = harness.transactions.last!!
        assertEquals(TransactionFlow.CASH_FIXED, record.flow)
        assertNull(record.paymentMethod)                    // cash has no digital method
        assertEquals(5.0, record.litresDispensed, 0.0)
        assertEquals(500_000, record.amountKobo)
        assertEquals(TEST_KOBO_PER_LITRE, record.priceKoboPerLitre)
        assertTrue(record.id.startsWith("BLC-"))
        assertEquals(record.id, record.transactionRef)
    }

    // ---- price guard --------------------------------------------------------------

    @Test
    fun `start is blocked with a recoverable error when price is unset`() {
        harness.deviceConfig.config =
            app.balancee.smartpump.display.domain.model.DeviceConfig(koboPerLitre = 0)
        val vm = harness.build()
        vm.onStartTransaction()

        val s = state(vm) as TransactionState.Error
        assertTrue(s.recoverable)
        assertTrue(s.message.contains("Fuel parameters not set"))
    }

    @Test
    fun `prepay completion records the digital method used`() {
        val vm = harness.build()
        vm.onStartTransaction()
        vm.onModeTileTap(app.balancee.smartpump.display.domain.model.TransactionMode.PRE_PAY)
        vm.onAmountTileTap(amountNaira = 5000)
        vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
        vm.onModeConfirm()          // → Pending → PrepayAwaitingPayment
        harness.payment.succeed()   // → FixedDispensing
        harness.pulseSource.emitPulse(count = 500) // → Complete

        val record = harness.transactions.last!!
        assertEquals(TransactionFlow.FIXED_PREPAY_DIGITAL, record.flow)
        assertEquals(PaymentMethod.BALANCEE_APP, record.paymentMethod)
        assertEquals(500_000, record.amountKobo)
        assertEquals(5.0, record.litresDispensed, 0.0)
    }

    // ---- receipt price (TODO #37) -------------------------------------------------

    @Test
    fun `completion screen shows the same price as the saved record after a price change`() {
        val vm = harness.build()    // boots at TEST_KOBO_PER_LITRE (₦1000/L)
        harness.deviceConfig.config = DeviceConfig(koboPerLitre = 120_000, fuelType = FuelType.PETROL)

        vm.onStartTransaction()     // picks up ₦1200/L
        vm.onModeTileTap(TransactionMode.PRE_PAY)
        vm.onAmountTileTap(amountNaira = 6000)
        vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
        vm.onModeConfirm()
        harness.payment.succeed()
        harness.pulseSource.emitPulse(count = 500) // 5.0 L → Complete

        assertTrue(state(vm) is TransactionState.Complete)
        val record = harness.transactions.last!!
        assertEquals(120_000, record.priceKoboPerLitre)
        // The completion screen reads this. The pre-pay path never refreshed it, so the screen
        // showed the boot-time price while the shared receipt showed the new one.
        assertEquals(record.priceKoboPerLitre, vm.ui.value.priceKoboPerLitre)
    }
}
