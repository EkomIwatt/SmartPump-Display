// Phase 10a — what the ViewModel asks a payment processor for.
//
// The seam widened from `process(method, amountKobo)` to a PaymentRequest carrying expectedLitres,
// because `POST /api/pump/authorise` requires it and the server checks
// `amount == expectedLitres × pricePerUnit` EXACTLY. That exactness is the whole point of these
// tests: a litre figure that is merely close is not a rounding error at the backend, it is a
// refused sale. Nothing here talks to a network — it asserts what the VM *would* send.
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.FuelType
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CustomerViewModelPaymentRequestTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val harness = VmHarness()
    private fun state(vm: CustomerViewModel) = vm.ui.value.state

    private fun startPrepay(vm: CustomerViewModel, amountNaira: Int) {
        vm.onStartTransaction()
        vm.onModeTileTap(TransactionMode.PRE_PAY)
        vm.onAmountTileTap(amountNaira = amountNaira)
        vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
        vm.onModeConfirm()
    }

    // ---- pre-pay: quote exactly what the pump will stop at ------------------------

    @Test
    fun `prepay sends the amount and the litres it buys`() {
        val vm = harness.build() // ₦1000/L
        startPrepay(vm, amountNaira = 5000)

        val request = harness.payment.lastRequest!!
        assertEquals(500_000L, request.amountKobo)
        assertEquals(5.0, request.expectedLitres, 0.0)
        assertEquals(PaymentMethod.BALANCEE_APP, request.method)
    }

    /**
     * The invariant that makes the exact server-side check survivable: the litres quoted to
     * `/authorise` are the same litres the pump cuts off at. If these two were derived differently
     * the app would authorise one quantity and dispense another — and because the check is exact,
     * the likelier outcome is a sale refused outright at the QR.
     */
    @Test
    fun `prepay expectedLitres equals the cutoff the pump will enforce`() {
        val vm = harness.build()
        startPrepay(vm, amountNaira = 5000)
        val quoted = harness.payment.lastExpectedLitres!!

        harness.payment.succeed()

        val dispensing = state(vm) as TransactionState.FixedDispensing
        assertEquals(dispensing.litresAuthorised, quoted, 0.0)
    }

    /**
     * A price that does not divide the amount evenly is the normal case, not the exotic one.
     * ₦5000 at ₦1490/L is 3.3557… L, which floors to 3.35 — never 3.36, because the floor is what
     * stops the pump giving away fuel nobody paid for.
     */
    @Test
    fun `prepay litres floor to 2dp on a price that does not divide evenly`() {
        harness.deviceConfig.config = DeviceConfig(koboPerLitre = 149_000, fuelType = FuelType.PETROL)
        val vm = harness.build()
        startPrepay(vm, amountNaira = 5000)

        assertEquals(3.35, harness.payment.lastExpectedLitres!!, 0.0)
    }

    // ---- fill-up: the tank is already full, so litres are measured ----------------

    /**
     * The one case where expectedLitres is **not** derived from the amount. The customer has already
     * taken the fuel; the meter knows how much. Deriving it back out of the amount would reintroduce
     * exactly the rounding the exact check refuses.
     */
    @Test
    fun `fill-up digital sends the metered litres, not a figure derived from the amount`() {
        val vm = harness.build()
        vm.onAttendantFillUpAuthorise()
        harness.pulseSource.emitPulse(count = 380) // 3.80 L
        vm.onSimulateNozzleShutoff()

        val full = state(vm) as TransactionState.FillupTankFull
        vm.onFillupPayDigital()

        val request = harness.payment.lastRequest!!
        assertEquals(full.verifiedLitres, request.expectedLitres, 0.0)
        assertEquals(full.amountDueKobo, request.amountKobo)
        assertEquals(PaymentMethod.BANK_QR_TRANSFER, request.method)
    }

    // ---- resume after a restart ---------------------------------------------------

    /**
     * A restored pre-pay must carry litres too. Phase 10d has the harder half of this — resuming a
     * poll on the existing transaction rather than authorising a second sale — but the request the
     * resume builds has to be well-formed either way.
     */
    @Test
    fun `resumed prepay payment still carries expectedLitres`() {
        harness.pulseRepo.stateToRestore = TransactionState.PrepayAwaitingPayment(
            flow = TransactionFlow.FIXED_PREPAY_DIGITAL,
            amountKobo = 500_000,
            method = PaymentMethod.BALANCEE_APP,
            txnId = "BLC-RESUME",
            priceKoboPerLitre = TEST_KOBO_PER_LITRE,
        )
        val vm = harness.build()

        assertEquals(TransactionState.PrepayAwaitingPayment::class, state(vm)::class)
        assertEquals(5.0, harness.payment.lastExpectedLitres!!, 0.0)
    }
}
