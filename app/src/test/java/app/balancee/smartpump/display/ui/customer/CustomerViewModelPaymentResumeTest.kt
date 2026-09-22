// Phase 10d — the boot-resume trap.
//
// A restart with a sale awaiting payment used to call `PaymentProcessor.process` again. Against the
// mock that is free, which is exactly why it survived for months: nothing in the app or its tests
// could tell the difference. Against the real backend `process` POSTs `/authorise`, so the restart
// creates a **second sale** — and bills a second time a customer who is standing at the pump having
// already paid for the first.
//
// These assert the distinction the fake now records: a resumed sale re-attaches
// (`resumeCount`) and never authorises (`processCount` stays at zero).
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class CustomerViewModelPaymentResumeTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val harness = VmHarness()
    private fun state(vm: CustomerViewModel) = vm.ui.value.state

    private val expiry = Instant.parse("2026-09-19T12:20:00Z")

    private fun restoredPrepay(
        txnId: String = "BLC-RESUME",
        expiresAtEpochMs: Long? = expiry.toEpochMilli(),
        litresAuthorised: Double? = 3.355,
    ) = TransactionState.PrepayAwaitingPayment(
        flow = TransactionFlow.FIXED_PREPAY_DIGITAL,
        amountKobo = 499_895,
        method = PaymentMethod.BALANCEE_APP,
        txnId = txnId,
        priceKoboPerLitre = TEST_KOBO_PER_LITRE,
        checkoutUrl = "https://checkout.paystack.com/jn0ej3u6def5150",
        expiresAtEpochMs = expiresAtEpochMs,
        litresAuthorised = litresAuthorised,
    )

    private fun restoredFillupDigital(txnId: String = "BLC-FILL-RESUME") =
        TransactionState.FillupDigitalAwaitingPayment(
            txnId = txnId,
            verifiedLitres = 38.1,
            amountDueKobo = 3_316_605,
            qrContent = "https://checkout.paystack.com/jn0ej3u6def5150",
            expiresAtEpochMs = expiry.toEpochMilli(),
        )

    // ---- pre-pay (Flow 1) ----------------------------------------------------------

    /** The defect, stated directly. */
    @Test
    fun `a restored prepay resumes the existing sale instead of authorising a new one`() {
        harness.pulseRepo.stateToRestore = restoredPrepay()

        val vm = harness.build()

        assertTrue(state(vm) is TransactionState.PrepayAwaitingPayment)
        assertEquals(0, harness.payment.processCount)
        assertEquals(1, harness.payment.resumeCount)
    }

    /** It has to resume the sale the customer is looking at, not some other one. */
    @Test
    fun `the resumed sale keeps the persisted transaction id`() {
        harness.pulseRepo.stateToRestore = restoredPrepay(txnId = "BLC-KEEP-ME")

        harness.build()

        assertEquals("BLC-KEEP-ME", harness.payment.lastResumedRef)
    }

    /**
     * The server's window kept running while the app was down. Handing the processor a fresh one
     * would hold a QR on screen after the server had stopped honouring it.
     */
    @Test
    fun `the persisted deadline is handed to the processor, not a fresh one`() {
        harness.pulseRepo.stateToRestore = restoredPrepay()

        harness.build()

        assertEquals(expiry, harness.payment.lastResumedDeadline)
    }

    /**
     * The authorised litres, not a re-derivation. At the test price they happen to differ from
     * `litresCutoff(499_895)` — which is the whole reason the figure is persisted.
     */
    @Test
    fun `the resumed request carries the authorised litres`() {
        harness.pulseRepo.stateToRestore = restoredPrepay(litresAuthorised = 3.355)

        harness.build()

        assertEquals(3.355, harness.payment.lastExpectedLitres!!, 0.0)
    }

    /** A state written before 10d has no litres on it; the old derivation still applies. */
    @Test
    fun `a pre-10d state falls back to deriving litres from the amount`() {
        harness.pulseRepo.stateToRestore = restoredPrepay(litresAuthorised = null)

        harness.build()

        // ₦4,998.95 at the harness's ₦1,000/L, floored to 2 dp.
        assertEquals(4.99, harness.payment.lastExpectedLitres!!, 0.0)
    }

    /** Resuming still resolves: the terminal drives the pump exactly as a fresh sale would. */
    @Test
    fun `a resumed sale that comes back PAID starts dispensing`() {
        harness.pulseRepo.stateToRestore = restoredPrepay()
        val vm = harness.build()

        harness.payment.succeed(ref = "BLC-RESUME", amountKobo = 499_895, litresAuthorised = 3.355)

        val dispensing = state(vm) as TransactionState.FixedDispensing
        assertEquals("BLC-RESUME", dispensing.txnId)
        assertEquals(3.355, dispensing.litresAuthorised, 0.0)
    }

    // ---- fill-up digital (Flow 3) --------------------------------------------------

    /**
     * The same trap, and worse here: the fuel is already in the customer's tank, so a second
     * authorise bills again for a fill-up that has already happened.
     */
    @Test
    fun `a restored fill-up payment resumes rather than re-authorising`() {
        harness.pulseRepo.stateToRestore = restoredFillupDigital()

        val vm = harness.build()

        assertTrue(state(vm) is TransactionState.FillupDigitalAwaitingPayment)
        assertEquals(0, harness.payment.processCount)
        assertEquals(1, harness.payment.resumeCount)
        assertEquals("BLC-FILL-RESUME", harness.payment.lastResumedRef)
    }

    /** The metered litres are what the tank gave; a resume must not re-derive them from money. */
    @Test
    fun `the resumed fill-up request carries the measured litres`() {
        harness.pulseRepo.stateToRestore = restoredFillupDigital()

        harness.build()

        assertEquals(38.1, harness.payment.lastExpectedLitres!!, 0.0)
    }

    // ---- a fresh sale is still a fresh sale ---------------------------------------

    /** The guard against over-correcting: a customer starting a sale must still get one. */
    @Test
    fun `a sale started at the pump authorises normally`() {
        val vm = harness.build()
        vm.onStartTransaction()
        vm.onModeTileTap(TransactionMode.PRE_PAY)
        vm.onAmountTileTap(amountNaira = 5000)
        vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
        vm.onModeConfirm()

        assertEquals(1, harness.payment.processCount)
        assertEquals(0, harness.payment.resumeCount)
    }
}
