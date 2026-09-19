// Phase 10f — the upload job's decisions, with no Android anywhere near them.
//
// Driven through a real PumpApiClient over a hand-written fake service, like the processor's tests
// and for the same reason: the envelope unwrapping and the #45 taxonomy are part of what is being
// asserted, and stubbing the client would skip both. The refusal fixtures are the bytes captured at
// the #32 gate.
//
// What these are really about: a dispense that never reaches the backend is the one outcome this
// job exists to prevent, and there are exactly two ways to cause it — marking a record sent when it
// was not, and abandoning one that would have gone through on the next try.
package app.balancee.smartpump.display.data.sync

import app.balancee.smartpump.display.data.network.PumpApiClient
import app.balancee.smartpump.display.data.network.PumpApiService
import app.balancee.smartpump.display.data.network.PumpErrorCodes
import app.balancee.smartpump.display.data.network.dto.ActivateRequest
import app.balancee.smartpump.display.data.network.dto.ActivateResponse
import app.balancee.smartpump.display.data.network.dto.ApiEnvelope
import app.balancee.smartpump.display.data.network.dto.AuthoriseRequest
import app.balancee.smartpump.display.data.network.dto.PumpConfigResponse
import app.balancee.smartpump.display.data.network.dto.PumpTransactionResponse
import app.balancee.smartpump.display.data.network.dto.UploadTransactionRequest
import app.balancee.smartpump.display.domain.model.EventType
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.Transaction
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.network.DeviceIdProvider
import app.balancee.smartpump.display.domain.network.PumpCredentials
import app.balancee.smartpump.display.domain.network.PumpCredentialsStore
import app.balancee.smartpump.display.ui.customer.FakeEventRepository
import app.balancee.smartpump.display.ui.customer.FakeTransactionRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TransactionUploaderTest {

    private val service = FakeUploadService()
    private val credentials = FakeCredentialsStore()
    private val transactions = FakeTransactionRepository()
    private val events = FakeEventRepository()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-19T15:00:00Z"), ZoneOffset.UTC)

    private val uploader = TransactionUploader(
        transactions = transactions,
        credentials = credentials,
        client = PumpApiClient(service, FixedDeviceId),
        events = events,
        clock = clock,
    )

    private fun sale(
        id: String = "txn-1",
        reference: String? = "BPM-990f0b736cb8436fbc8673469f2d1671",
        litres: Double = 3.355,
        startedAt: Long? = 1_789_827_600_000L,   // 2026-09-19T14:20:00Z
        createdAt: Long = 1_789_827_780_000L,    // three minutes later
        syncedAt: Long? = null,
        uploadError: String? = null,
    ) = Transaction(
        id = id,
        flow = TransactionFlow.FIXED_PREPAY_DIGITAL,
        paymentMethod = PaymentMethod.BALANCEE_APP,
        litresDispensed = litres,
        amountKobo = 499_895,
        priceKoboPerLitre = 149_000,
        transactionRef = id,
        createdAt = createdAt,
        syncedAt = syncedAt,
        paymentReference = reference,
        startedAt = startedAt,
        uploadError = uploadError,
    )

    private fun refusal(code: String, message: String, httpCode: Int) = HttpException(
        Response.error<Unit>(
            httpCode,
            """{"status":false,"message":"$message","code":"$code"}"""
                .toResponseBody("application/json".toMediaType()),
        ),
    )

    // ---- the happy path, and what it writes ------------------------------------------------------

    @Test
    fun `a dispense goes out with what actually flowed, and the record closes`() = runTest {
        transactions.saveTransaction(sale())

        val run = uploader.uploadPending()

        assertEquals(UploadRun.SETTLED, run)
        val sent = service.uploads.single()
        assertEquals("pump-fixed-0001", sent.pumpId)
        assertEquals("txn-1", sent.transactionId)
        assertEquals("BPM-990f0b736cb8436fbc8673469f2d1671", sent.paymentReference)
        // #47: the backend accepts a figure other than the authorised one, so a partial dispense,
        // an early end and 7h's recovered pulses are all reported honestly.
        assertEquals(3.355, sent.actualLitresDispensed, 0.0)
        assertEquals("2026-09-19T14:20:00Z", sent.startedAt)
        assertEquals("2026-09-19T14:23:00Z", sent.completedAt)
        assertEquals(clock.millis(), transactions.byId("txn-1")!!.syncedAt)
    }

    /** **#48.** The backend's first write is the only one that counts, so there is never a second. */
    @Test
    fun `a record that has already gone out is never sent again`() = runTest {
        transactions.saveTransaction(sale(syncedAt = 1L))

        val run = uploader.uploadPending()

        assertEquals(UploadRun.SETTLED, run)
        assertTrue("nothing should have been re-sent", service.uploads.isEmpty())
    }

    /**
     * A cash sale has no reference and `/transactions/upload` requires one. It is outside the
     * upload path, not behind in it — treating it as pending would leave it in the queue forever,
     * failing on a field it can never have.
     */
    @Test
    fun `a cash sale is never offered to the backend`() = runTest {
        transactions.saveTransaction(sale(id = "cash", reference = null))

        assertEquals(UploadRun.SETTLED, uploader.uploadPending())
        assertTrue(service.uploads.isEmpty())
        assertNull("and it is not condemned either", transactions.byId("cash")!!.uploadError)
    }

    @Test
    fun `an empty queue is settled, and asks the server nothing`() = runTest {
        assertEquals(UploadRun.SETTLED, uploader.uploadPending())
        assertTrue(service.uploads.isEmpty())
    }

    /** Oldest first: the order the station's day happened in is the order it is reported in. */
    @Test
    fun `pending records go out oldest first`() = runTest {
        transactions.saveTransaction(sale(id = "second", createdAt = 2_000L))
        transactions.saveTransaction(sale(id = "first", createdAt = 1_000L))

        uploader.uploadPending()

        assertEquals(listOf("first", "second"), service.uploads.map { it.transactionId })
    }

    // ---- the failures that must NOT close a record -----------------------------------------------

    @Test
    fun `no signal leaves the record pending and asks to be run again`() = runTest {
        transactions.saveTransaction(sale())
        service.failure = IOException("no route to host")

        assertEquals(UploadRun.RETRY, uploader.uploadPending())
        val record = transactions.byId("txn-1")!!
        assertNull("not marked sent", record.syncedAt)
        assertNull("not condemned either", record.uploadError)
    }

    @Test
    fun `a 500 is retried rather than abandoned`() = runTest {
        transactions.saveTransaction(sale())
        service.failure = HttpException(
            Response.error<Unit>(503, "upstream down".toResponseBody("text/plain".toMediaType())),
        )

        assertEquals(UploadRun.RETRY, uploader.uploadPending())
        assertNull(transactions.byId("txn-1")!!.uploadError)
    }

    /**
     * **The case #45 exists for, and the single outcome this job exists to prevent.**
     *
     * `PAYMENT_NOT_CONFIRMED` is a 409 that parses as a considered refusal and is not one: the
     * backend has not seen the money land yet, which it may do a minute later. Read as final, this
     * discards the record of fuel that a customer has already taken.
     */
    @Test
    fun `a payment the backend has not confirmed yet is waited out, not discarded`() = runTest {
        transactions.saveTransaction(sale())
        service.failure = refusal(
            PumpErrorCodes.PAYMENT_NOT_CONFIRMED,
            "Payment has not been confirmed for this transaction. Do not dispense until payment is confirmed.",
            409,
        )

        assertEquals(UploadRun.RETRY, uploader.uploadPending())
        val record = transactions.byId("txn-1")!!
        assertNull(record.syncedAt)
        assertNull("the record must still be there tomorrow", record.uploadError)
        assertTrue("and no alarm was raised for something that resolves itself", events.recorded.isEmpty())
    }

    /** It resolves, and the next run sends it. The whole point of waiting. */
    @Test
    fun `a payment that confirms later goes out on the next run`() = runTest {
        transactions.saveTransaction(sale())
        service.failure = refusal(PumpErrorCodes.PAYMENT_NOT_CONFIRMED, "not yet", 409)
        uploader.uploadPending()

        service.failure = null
        assertEquals(UploadRun.SETTLED, uploader.uploadPending())
        assertNotNull(transactions.byId("txn-1")!!.syncedAt)
    }

    /**
     * Credentials are a property of the device, not a verdict on the sale. The shared taxonomy
     * calls `NotActivated` terminal — correct for a screen a customer is standing at, wrong here,
     * where condemning the queue would throw away a day of real dispenses over a state that a
     * re-activation fixes.
     */
    @Test
    fun `a pump with no credentials waits rather than condemning its records`() = runTest {
        transactions.saveTransaction(sale())
        credentials.credentials = null

        assertEquals(UploadRun.RETRY, uploader.uploadPending())
        assertTrue("nothing was even attempted", service.uploads.isEmpty())
        assertNull(transactions.byId("txn-1")!!.uploadError)
    }

    // ---- the failures that DO close a record, loudly ---------------------------------------------

    /**
     * A refusal that will read the same tomorrow. The record is abandoned — but it stays in the
     * log carrying the reason, and the pump log gets an entry, because the station has sold fuel
     * the backend's ledger does not know about and that needs a person.
     */
    @Test
    fun `a considered refusal stops the retries and raises it with a human`() = runTest {
        transactions.saveTransaction(sale())
        service.failure = refusal(
            PumpErrorCodes.TRANSACTION_NOT_FOUND,
            "No transaction was found for this id.",
            404,
        )

        assertEquals("the run itself is not a failure", UploadRun.SETTLED, uploader.uploadPending())
        val record = transactions.byId("txn-1")!!
        assertNull("never marked sent — it was not", record.syncedAt)
        assertNotNull("but it is no longer offered", record.uploadError)

        val event = events.last!!
        assertEquals(EventType.DISPENSE_UPLOAD_FAILED, event.type)
        assertEquals("txn-1", event.transactionRef)
        assertTrue("the litres are what a person needs", event.detail!!.contains("3.36 L"))
    }

    @Test
    fun `a condemned record is not retried on the next run`() = runTest {
        transactions.saveTransaction(sale())
        service.failure = refusal(PumpErrorCodes.INVALID_REQUEST, "malformed", 400)
        uploader.uploadPending()
        service.failure = null

        assertEquals(UploadRun.SETTLED, uploader.uploadPending())
        assertTrue("it had its answer", service.uploads.isEmpty())
    }

    /**
     * One bad record must not hold the rest of the day behind it, and one transient failure must
     * not stop the records after it from going out.
     */
    @Test
    fun `a refused record does not block the ones behind it`() = runTest {
        transactions.saveTransaction(sale(id = "refused", createdAt = 1_000L))
        transactions.saveTransaction(sale(id = "fine", createdAt = 2_000L))
        service.failuresById["refused"] = refusal(PumpErrorCodes.TRANSACTION_NOT_FOUND, "no such id", 404)

        assertEquals(UploadRun.SETTLED, uploader.uploadPending())
        assertNotNull(transactions.byId("refused")!!.uploadError)
        assertNotNull("the next sale still went out", transactions.byId("fine")!!.syncedAt)
    }

    @Test
    fun `one transient failure makes the whole run ask to come back`() = runTest {
        transactions.saveTransaction(sale(id = "blip", createdAt = 1_000L))
        transactions.saveTransaction(sale(id = "fine", createdAt = 2_000L))
        service.failuresById["blip"] = IOException("connection reset")

        assertEquals(UploadRun.RETRY, uploader.uploadPending())
        assertNotNull("and the healthy one still went", transactions.byId("fine")!!.syncedAt)
    }

    /**
     * A single blip never reaches the worker at all: `PumpApiClient.uploadTransaction` already
     * backs off three times inside one call. Worth pinning, because it is the difference between
     * a record that waits fifteen minutes for the next run and one that goes out immediately.
     */
    @Test
    fun `a blip inside one call is absorbed before the job hears about it`() = runTest {
        transactions.saveTransaction(sale())
        var thrown = false
        service.beforeEach = {
            if (!thrown) { thrown = true; throw IOException("connection reset") }
        }

        assertEquals(UploadRun.SETTLED, uploader.uploadPending())
        assertNotNull(transactions.byId("txn-1")!!.syncedAt)
    }

    // ---- the pre-10f record ----------------------------------------------------------------------

    /**
     * A sale completed before 10f has no start time. The window is reported as zero-length rather
     * than invented — the endpoint accepts it, and a fabricated start would put a number in the
     * station's record that nothing measured.
     */
    @Test
    fun `a record with no start time reports the completion time for both ends`() = runTest {
        transactions.saveTransaction(sale(startedAt = null))

        uploader.uploadPending()

        val sent = service.uploads.single()
        assertEquals(sent.completedAt, sent.startedAt)
    }
}

private class FakeUploadService : PumpApiService {

    val uploads = mutableListOf<UploadTransactionRequest>()

    /** Thrown on every call until cleared. */
    var failure: Throwable? = null

    /**
     * Thrown for one transaction id only, so a run can contain one bad record and one good one.
     *
     * Keyed by id rather than by call number on purpose: `PumpApiClient.uploadTransaction` wraps
     * the call in `retryingApiCall`, so a transient failure is retried three times *inside* one
     * upload. A fake that failed only the first call would be absorbed there and never reach the
     * behaviour under test.
     */
    val failuresById = mutableMapOf<String, Throwable>()

    /** Runs before every call, so a test can fail one attempt and let the next through. */
    var beforeEach: (() -> Unit)? = null

    override suspend fun uploadTransaction(
        body: UploadTransactionRequest,
    ): ApiEnvelope<PumpTransactionResponse> {
        beforeEach?.invoke()
        failure?.let { throw it }
        failuresById[body.transactionId]?.let { throw it }
        uploads += body
        return ApiEnvelope(
            status = true,
            message = "Transaction recorded",
            data = PumpTransactionResponse(
                status = "DISPENSED",
                transactionId = body.transactionId,
                paymentReference = body.paymentReference,
            ),
        )
    }

    override suspend fun config(): ApiEnvelope<PumpConfigResponse> = error("not the upload path")
    override suspend fun authorise(body: AuthoriseRequest): ApiEnvelope<PumpTransactionResponse> =
        error("not the upload path")
    override suspend fun authoriseRaw(body: JsonObject): ApiEnvelope<PumpTransactionResponse> =
        error("not the upload path")
    override suspend fun activate(body: ActivateRequest): ApiEnvelope<ActivateResponse> =
        error("not the upload path")
    override suspend fun transactionStatus(
        transactionId: String,
    ): ApiEnvelope<PumpTransactionResponse> = error("not the upload path")
}

private object FixedDeviceId : DeviceIdProvider {
    override fun deviceId(): String = "device-fixed-0001"
}

private class FakeCredentialsStore : PumpCredentialsStore {
    var credentials: PumpCredentials? = PumpCredentials(
        deviceId = "device-fixed-0001",
        pumpId = "pump-fixed-0001",
        apiKey = "bal_live_fake",
        signingSecret = "sec_fake",
    )

    override fun current(): PumpCredentials? = credentials
    override val isActivated: Boolean get() = credentials != null
    override suspend fun save(credentials: PumpCredentials) { this.credentials = credentials }
    override suspend fun clear() { credentials = null }
}
