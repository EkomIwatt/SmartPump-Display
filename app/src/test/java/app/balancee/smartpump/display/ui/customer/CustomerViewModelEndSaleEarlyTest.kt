// OQ #22 (Option 1, decided 2026-09-15) — the attendant ends a fixed sale before its target.
// Before this, a fixed sale had no exit but its target: a tank that filled first or a link that
// stayed down left the pump in dispensing for good, and a power cycle restored the same stuck sale.
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CustomerViewModelEndSaleEarlyTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val harness = VmHarness()
    private fun state(vm: CustomerViewModel) = vm.ui.value.state

    /** ₦5,000 pre-pay at ₦1,000/L → a 5.00 L target, left dispensing. */
    private fun prepayDispensing(): CustomerViewModel {
        val vm = harness.build()
        vm.onStartTransaction()
        vm.onModeTileTap(TransactionMode.PRE_PAY)
        vm.onAmountTileTap(amountNaira = 5000)
        vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
        vm.onModeConfirm()
        harness.payment.succeed()
        assertTrue(state(vm) is TransactionState.FixedDispensing)
        return vm
    }

    @Test
    fun `ending a pre-pay early stops fuel and records what flowed against what was paid`() {
        val vm = prepayDispensing()
        harness.pulseSource.emitPulse(count = 300) // 3.00 of 5.00 L — the tank is full

        vm.onAttendantEndSaleEarly()

        assertFalse(harness.relay.isDispensing.value)
        val done = state(vm) as TransactionState.Complete
        assertEquals(3.0, done.litres, 0.0)
        assertEquals(500_000, done.amountKobo)
        assertEquals(5.0, done.litresTarget!!, 0.0)

        val record = harness.transactions.last!!
        assertEquals(TransactionFlow.FIXED_PREPAY_DIGITAL, record.flow)
        assertEquals(PaymentMethod.BALANCEE_APP, record.paymentMethod)
        assertEquals(3.0, record.litresDispensed, 0.0)
        // What the customer paid, not litres x price: the record must not claim they paid less.
        assertEquals(500_000, record.amountKobo)
        assertEquals(TEST_KOBO_PER_LITRE, record.priceKoboPerLitre)
        assertEquals("Ended by attendant at 3.00 of 5.00 L", record.attendantNote)
    }

    @Test
    fun `ending a cash-fixed sale early records it as cash`() {
        val vm = harness.build()
        vm.onAttendantCashFixed()
        vm.onCashFixedAuthorise(cashAmountKobo = 400_000) // 4.00 L cutoff
        harness.pulseSource.emitPulse(count = 250)

        vm.onAttendantEndSaleEarly()

        val record = harness.transactions.last!!
        assertEquals(TransactionFlow.CASH_FIXED, record.flow)
        assertNull(record.paymentMethod)
        assertEquals(2.5, record.litresDispensed, 0.0)
        assertEquals(400_000, record.amountKobo)
        assertEquals("Ended by attendant at 2.50 of 4.00 L", record.attendantNote)
    }

    @Test
    fun `pulses arriving after the sale ended change nothing`() {
        val vm = prepayDispensing()
        harness.pulseSource.emitPulse(count = 300)
        vm.onAttendantEndSaleEarly()

        harness.pulseSource.emitPulse(count = 500) // would have completed the target

        assertEquals(3.0, (state(vm) as TransactionState.Complete).litres, 0.0)
        assertEquals(1, harness.transactions.saved.size)
    }

    @Test
    fun `a stuck sale restored after a power cycle can be ended`() {
        // The case a power cycle used to loop on: boot resume restores the sale and re-opens the
        // relay, and nothing arrives to finish it.
        harness.pulseRepo.stateToRestore = TransactionState.FixedDispensing(
            flow = TransactionFlow.FIXED_PREPAY_DIGITAL, txnId = "BLC-STUCK",
            priceKoboPerLitre = TEST_KOBO_PER_LITRE, amountKobo = 500_000,
            litresAuthorised = 5.0, litresSoFar = 2.0, method = PaymentMethod.BALANCEE_APP,
        )
        harness.pulseRepo.pulsesToRestore = 200
        val vm = harness.build()
        assertTrue(harness.relay.isDispensing.value)

        vm.onAttendantEndSaleEarly()

        assertFalse(harness.relay.isDispensing.value)
        val record = harness.transactions.last!!
        assertEquals("BLC-STUCK", record.id)
        assertEquals(2.0, record.litresDispensed, 0.0)
        assertEquals("Ended by attendant at 2.00 of 5.00 L", record.attendantNote)
    }

    @Test
    fun `it does nothing outside a fixed sale`() {
        val vm = harness.build()
        vm.onAttendantEndSaleEarly()
        assertTrue(state(vm) is TransactionState.Idle)

        vm.onAttendantFillUpAuthorise()
        val fillup = state(vm)
        vm.onAttendantEndSaleEarly()
        assertEquals(fillup, state(vm))

        assertTrue(harness.transactions.saved.isEmpty())
    }

    @Test
    fun `a sale that reaches its target carries no target and no note`() {
        val vm = prepayDispensing()
        harness.pulseSource.emitPulse(count = 500)

        assertNull((state(vm) as TransactionState.Complete).litresTarget)
        assertNull(harness.transactions.last!!.attendantNote)
    }

    @Test
    fun `a Complete persisted before litresTarget existed still decodes`() {
        // Same settings as PulseRepositoryImpl. A state written by the previous build must not be
        // unreadable after this one is installed.
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val legacy = """{"type":"complete","flow":"CASH_FIXED","txnId":"BLC-OLD",""" +
            """"litres":3.0,"amountKobo":300000,"method":null,"attendantId":null}"""

        val decoded = json.decodeFromString<TransactionState>(legacy) as TransactionState.Complete

        assertEquals("BLC-OLD", decoded.txnId)
        assertNull(decoded.litresTarget)
    }
}
