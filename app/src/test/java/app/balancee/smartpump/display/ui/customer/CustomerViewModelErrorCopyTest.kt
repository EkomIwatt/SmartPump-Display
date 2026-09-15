// OQ #17 — the customer/attendant split on errors, approved 2026-09-12.
//
// These assert the SPLIT rather than the prose: that nothing diagnostic reaches the customer line,
// and that the attendant line carries it instead. Exact wording is the copy draft's business and
// will change; the split is the invariant, because breaking it puts a naira figure or a gateway
// error in front of someone who cannot act on either.
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import app.balancee.smartpump.display.domain.usecase.CanStartTransactionUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CustomerViewModelErrorCopyTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val harness = VmHarness()
    private fun error(vm: CustomerViewModel) = vm.ui.value.state as TransactionState.Error

    /**
     * A pump with neither field set. Note it is an empty config rather than a **null** one: these
     * are unit tests on the `debug` variant, so `BuildConfig.DEBUG` is true and the ViewModel's
     * boot sequence runs `seedDefaultConfigIfMissing()`, which would quietly fill a null config in
     * with ₦870/L and PETROL and take the guard out of the picture entirely.
     */
    private fun unconfigured() {
        harness.deviceConfig.config =
            harness.deviceConfig.config!!.copy(koboPerLitre = 0, fuelType = null)
    }

    // ---- Unconfigured pump ----------------------------------------------------------------------

    @Test
    fun `an unconfigured pump tells the customer one line and the attendant which fields`() {
        unconfigured()
        val vm = harness.build()

        vm.onStartTransaction()

        val e = error(vm)
        assertEquals(CanStartTransactionUseCase.CUSTOMER_MESSAGE, e.message)
        assertNotNull(e.attendantDetail)
        assertTrue(e.attendantDetail!!.contains("fuel type"))
        assertTrue(e.attendantDetail.contains("price"))
    }

    @Test
    fun `a missing price alone names only the price to the attendant`() {
        harness.deviceConfig.config = harness.deviceConfig.config!!.copy(koboPerLitre = 0)
        val vm = harness.build()

        vm.onStartTransaction()

        val e = error(vm)
        assertTrue(e.attendantDetail!!.contains("price", ignoreCase = true))
        assertTrue(!e.attendantDetail.contains("fuel type", ignoreCase = true))
    }

    /**
     * The guard and the cash-fixed price check both mean "this pump has no usable price". They used
     * to say it differently, one of them in operator language on the customer-facing display.
     */
    @Test
    fun `the guard and the cash-fixed price check give the customer the same sentence`() {
        harness.deviceConfig.config = harness.deviceConfig.config!!.copy(koboPerLitre = 0)

        val viaGuard = harness.build().also { it.onStartTransaction() }
        val viaCashFixed = harness.build().also {
            it.onAttendantCashFixed()
            it.onCashFixedAuthorise(cashAmountKobo = 500_000)
        }

        assertEquals(error(viaGuard).message, error(viaCashFixed).message)
    }

    // ---- Nothing diagnostic on the customer line -------------------------------------------------

    /**
     * The attendant typed the amount, so the minimum-dispense figure is theirs. A naira amount on
     * the customer card is a number the customer cannot act on.
     */
    @Test
    fun `a below-minimum amount keeps the figure off the customer line`() {
        val vm = harness.build()
        vm.onAttendantCashFixed()
        vm.onCashFixedAuthorise(cashAmountKobo = 500) // ₦5 @ ₦1000/L → 0.005 L → floors to 0.0

        val e = error(vm)
        assertTrue(e.message.contains("attendant", ignoreCase = true))
        assertTrue("naira figure leaked to the customer", !e.message.contains("₦"))
        assertTrue(e.attendantDetail!!.contains("₦"))
        assertTrue(e.attendantDetail.contains("0.01"))
    }

    @Test
    fun `a failed payment keeps the processor's reason off the customer line`() {
        val vm = harness.build()
        vm.onStartTransaction()
        vm.onModeTileTap(TransactionMode.PRE_PAY)
        vm.onAmountTileTap(amountNaira = 5000)
        vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
        vm.onModeConfirm()

        harness.payment.fail(reason = "gateway timeout ref=abc123")

        val e = error(vm)
        assertTrue("processor reason leaked to the customer", !e.message.contains("gateway timeout"))
        assertTrue(e.attendantDetail!!.contains("gateway timeout ref=abc123"))
    }

    // ---- The recoverable flag is now read --------------------------------------------------------

    /**
     * Every local failure is recoverable today, so this pins the flag rather than proving both
     * branches. The screen reads it now — gold "please try again" against red "cannot continue" —
     * and the non-recoverable branch arrives with the server errors in the payment phase.
     */
    @Test
    fun `local failures are marked recoverable`() {
        unconfigured()
        val vm = harness.build()

        vm.onStartTransaction()

        assertTrue(error(vm).recoverable)
    }
}
