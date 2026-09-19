// Phase 10f — what the ViewModel puts on the record, and when it asks for it to be reported.
//
// The queue's own behaviour is TransactionUploaderTest's. These cover the two things only this
// class can get wrong: writing an audit row the upload can never use, and asking (or failing to
// ask) for a report at the wrong moment.
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CustomerViewModelUploadTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val harness = VmHarness()
    private fun state(vm: CustomerViewModel) = vm.ui.value.state

    /** Deliberately unlike the `BLC-…` shape this pump mints for itself. */
    private val SERVER_TXN_ID = "740e2af7-3573-45b1-a92b-813f2730ac93"

    private fun prepayThrough(litresPulses: Int = 500): CustomerViewModel {
        val vm = harness.build()
        vm.onStartTransaction()
        vm.onModeTileTap(TransactionMode.PRE_PAY)
        vm.onAmountTileTap(amountNaira = 5000)
        vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
        vm.onModeConfirm()
        harness.payment.succeed(litresAuthorised = litresPulses / 100.0)
        harness.pulseSource.emitPulse(count = litresPulses)
        return vm
    }

    // ---- the record ------------------------------------------------------------------------------

    /**
     * The defect 10f went looking for: `PaymentResult.Success` has carried a `paymentReference`
     * since 10a and every call site dropped it, so every digital dispense this app completed was
     * unreportable and nothing said so.
     */
    @Test
    fun `a digital sale records the reference the upload has to quote`() = runTest {
        prepayThrough()

        val record = harness.transactions.last!!
        assertEquals("BPM-TEST-0001", record.paymentReference)
        assertTrue(record.isUploadable)
    }

    /**
     * **The id on the record must be one the server issued** — the upload quotes it verbatim
     * (`TransactionUploader` sends `record.id`), so an id this pump invented describes a sale the
     * backend has never heard of.
     *
     * Found by the 10g gate on a real ₦149 fill-up (2026-09-19). Fill-up mints a local `BLC-…`
     * reference at attendant-authorise, before any server transaction exists, and
     * `onFillupDigitalSuccess` was recording *that* rather than the one `/authorise` returned. The
     * fuel flowed, the money was taken, and the upload was refused as terminal — leaving a paid
     * transaction with no dispense against it, which is the single outcome 10f exists to prevent.
     *
     * Flow 1 never had the bug, so it is asserted here too: the guarantee is "digital sales record
     * the server's id", not "fill-up was patched once".
     */
    @Test
    fun `a fill-up digital sale records the server's id, not the one this pump minted`() = runTest {
        val vm = harness.build()
        vm.onAttendantFillUpAuthorise()
        harness.pulseSource.emitPulse(count = 10) // 0.10 L, as the gate ran it
        vm.onSimulateNozzleShutoff()
        val localRef = (state(vm) as TransactionState.FillupTankFull).txnId
        vm.onFillupPayDigital()
        harness.payment.succeed(ref = SERVER_TXN_ID)

        val record = harness.transactions.last!!
        assertEquals("the upload would quote an id no authorise ever issued", SERVER_TXN_ID, record.id)
        assertNotEquals("the pump's own reference reached the record", localRef, record.id)
        assertTrue(record.isUploadable)
    }

    @Test
    fun `a pre-pay digital sale records the server's id too`() = runTest {
        val vm = harness.build()
        vm.onStartTransaction()
        vm.onModeTileTap(TransactionMode.PRE_PAY)
        vm.onAmountTileTap(amountNaira = 5000)
        vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
        vm.onModeConfirm()
        harness.payment.succeed(ref = SERVER_TXN_ID, litresAuthorised = 5.0)
        harness.pulseSource.emitPulse(count = 500)

        assertEquals(SERVER_TXN_ID, harness.transactions.last!!.id)
    }

    /** `createdAt` is when the sale finished. The upload needs the other end of the window too. */
    @Test
    fun `a digital sale records when fuel started flowing`() = runTest {
        prepayThrough()

        val record = harness.transactions.last!!
        assertNotNull("no start time means the upload invents one", record.startedAt)
        assertTrue("fuel flowed before the sale closed", record.startedAt!! <= record.createdAt)
    }

    /**
     * A cash sale has no reference because nothing authorised it, and `/transactions/upload`
     * requires one. It is outside the upload path rather than behind in it — the distinction that
     * keeps it out of a queue it could never leave.
     */
    @Test
    fun `a cash sale records no reference and is not uploadable`() = runTest {
        val vm = harness.build()
        vm.onAttendantCashFixed()
        vm.onCashFixedAuthorise(cashAmountKobo = 500_000)
        harness.pulseSource.emitPulse(count = 500)

        val record = harness.transactions.last!!
        assertNull(record.paymentReference)
        assertFalse(record.isUploadable)
    }

    /** OQ #22: #47 confirmed the backend takes a figure other than the authorised one. */
    @Test
    fun `a sale ended early still carries the reference, so the short dispense can be reported`() =
        runTest {
            val vm = harness.build()
            vm.onStartTransaction()
            vm.onModeTileTap(TransactionMode.PRE_PAY)
            vm.onAmountTileTap(amountNaira = 5000)
            vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
            vm.onModeConfirm()
            harness.payment.succeed(litresAuthorised = 5.0)
            harness.pulseSource.emitPulse(count = 200)   // 2.0 L of the 5.0 paid for

            vm.onAttendantEndSaleEarly()

            val record = harness.transactions.last!!
            assertEquals("BPM-TEST-0001", record.paymentReference)
            assertEquals("what actually flowed", 2.0, record.litresDispensed, 0.001)
        }

    // ---- the ask ---------------------------------------------------------------------------------

    /**
     * Boot asks once, and that is the only thing that ever reports a sale completed while the
     * forecourt had no internet. Without it the queue only moves when a *new* sale finishes, so a
     * pump that goes quiet keeps its records to itself.
     */
    @Test
    fun `opening the app asks for the queue to be drained`() = runTest {
        harness.build()

        assertEquals(1, harness.uploadScheduler.requests)
    }

    @Test
    fun `finishing a digital sale asks again`() = runTest {
        prepayThrough()

        assertEquals("boot, then the sale", 2, harness.uploadScheduler.requests)
    }

    /** A cash sale has nothing to send, so it must not wake the queue. */
    @Test
    fun `finishing a cash sale does not`() = runTest {
        val vm = harness.build()
        vm.onAttendantCashFixed()
        vm.onCashFixedAuthorise(cashAmountKobo = 500_000)
        harness.pulseSource.emitPulse(count = 500)

        assertTrue("the sale did complete", vm.ui.value.state is TransactionState.Complete)
        assertEquals("boot only", 1, harness.uploadScheduler.requests)
    }
}
