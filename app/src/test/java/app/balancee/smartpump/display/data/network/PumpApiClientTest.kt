// Every success fixture below is copied VERBATIM from docs/pump-api-reference-v3.pdf — the literal
// "Success Response (200 OK)" JSON of §4.1/§4.2/§4.3, envelope and all. That is deliberate: the
// previous version of this file hand-wrote unenveloped fixtures matching our DTOs, so the suite was
// green while the client could not actually parse a single real response (TODO #11). Fixtures that
// restate an assumption test only self-consistency. If a fixture here needs editing to make a test
// pass, the Reference is what must be re-read — not the fixture.
package app.balancee.smartpump.display.data.network

import app.balancee.smartpump.display.data.network.dto.AuthoriseRequest
import app.balancee.smartpump.display.domain.model.FuelType
import app.balancee.smartpump.display.data.network.dto.UploadTransactionRequest
import app.balancee.smartpump.display.domain.network.DeviceIdProvider
import app.balancee.smartpump.display.domain.network.PumpCredentials
import app.balancee.smartpump.display.domain.network.PumpCredentialsStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PumpApiClientTest {

    private val creds =
        PumpCredentials("dev-01", "P1", "bal_live_test", "test-signing-secret")
    private val clock = Clock.fixed(Instant.parse("2026-07-03T12:00:00Z"), ZoneOffset.UTC)

    private lateinit var server: MockWebServer
    private lateinit var store: FakeStore
    private lateinit var client: PumpApiClient

    /** The client sources the deviceId itself now, so activation can't be handed an ad-hoc one. */
    private class FakeDeviceIds(private val id: String) : DeviceIdProvider {
        override fun deviceId(): String = id
    }

    private class FakeStore(var creds: PumpCredentials?) : PumpCredentialsStore {
        override fun current(): PumpCredentials? = creds
        override val isActivated: Boolean get() = creds != null
        override suspend fun save(credentials: PumpCredentials) { creds = credentials }
        override suspend fun clear() { creds = null }
    }

    private val authoriseReq = AuthoriseRequest("P1", "T1", 500000, 5.75, FuelType.PETROL)
    private val uploadReq = UploadTransactionRequest(
        "P1", "T1", "PR1", 5.75, "2026-07-03T12:00:00Z", "2026-07-03T12:03:00Z",
    )

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        store = FakeStore(creds)
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val http = OkHttpClient.Builder()
            .addInterceptor(PumpSigningInterceptor(store, clock))
            .build()
        val service = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PumpApiService::class.java)
        client = PumpApiClient(service, FakeDeviceIds("device_001"))
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ---- Success paths: the Reference's literal enveloped responses -----------------------------

    /** Reference §4.1, "Success Response (200 OK)". */
    @Test
    fun `activate unwraps the envelope and parses the once-only credentials`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "status": true,
                  "message": "Pump activated successfully",
                  "data": {
                    "deviceId": "device_001",
                    "pumpId": "7f108b57-7559-4837-8dfb-33c7aac7d632",
                    "apiKey": "bal_live_xxxxxxxxxxxxxxxxxxxx",
                    "signingSecret": "sec_xxxxxxxxxxxxxxxxxxxxxxxx"
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = client.activate("PMP-O8l6zj")

        assertTrue(result is ApiResult.Success)
        val body = (result as ApiResult.Success).data
        assertEquals("device_001", body.deviceId)
        assertEquals("7f108b57-7559-4837-8dfb-33c7aac7d632", body.pumpId)
        assertEquals("bal_live_xxxxxxxxxxxxxxxxxxxx", body.apiKey)
        assertEquals("sec_xxxxxxxxxxxxxxxxxxxxxxxx", body.signingSecret)
    }

    /**
     * The one call that decides this install's identity forever (TODO #16): whatever the provider
     * mints must be what reaches /activate, because the credentials the server issues are bound
     * to it and every later signed request presents it as X-Device-Id.
     */
    @Test
    fun `activate sends the provider's deviceId, not a caller-supplied one`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "status": true,
                  "message": "Pump activated successfully",
                  "data": {
                    "deviceId": "device_001",
                    "pumpId": "7f108b57-7559-4837-8dfb-33c7aac7d632",
                    "apiKey": "bal_live_xxxxxxxxxxxxxxxxxxxx",
                    "signingSecret": "sec_xxxxxxxxxxxxxxxxxxxxxxxx"
                  }
                }
                """.trimIndent(),
            ),
        )

        client.activate("PMP-O8l6zj")

        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains(""""deviceId":"device_001""""))
        assertTrue(sent.contains(""""activationCode":"PMP-O8l6zj""""))
    }

    /**
     * Reference §4.2, "Success Response (200 OK)". Note the two unrelated `status` fields: the
     * envelope's Boolean transport flag and `data.status`, the transaction status String. Reading
     * the outer one as the transaction status is exactly the bug this test exists to catch.
     */
    @Test
    fun `authorise unwraps the envelope and parses the inner data`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "status": true,
                  "message": "Transaction authorised",
                  "data": {
                    "status": "PENDING_PAYMENT",
                    "transactionId": "txn_0007",
                    "paymentReference": "BPM-5397913a552441f4aa853eb60ca47d05",
                    "authorizationUrl": "https://checkout.paystack.com/pkjo1mam84zn2sx",
                    "expiresAt": "2026-06-13T19:28:42Z"
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = client.authorise(authoriseReq)

        assertTrue(result is ApiResult.Success)
        val body = (result as ApiResult.Success).data
        assertEquals("PENDING_PAYMENT", body.status)
        assertEquals("txn_0007", body.transactionId)
        assertEquals("BPM-5397913a552441f4aa853eb60ca47d05", body.paymentReference)
        assertEquals("https://checkout.paystack.com/pkjo1mam84zn2sx", body.authorizationUrl)
        assertEquals("2026-06-13T19:28:42Z", body.expiresAt)
    }

    /** Reference §4.3, "Success Response (200 OK)". */
    @Test
    fun `upload unwraps the envelope and parses the inner data`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "status": true,
                  "message": "Transaction recorded",
                  "data": {
                    "status": "DISPENSED",
                    "transactionId": "txn_0007",
                    "paymentReference": "BPM-5397913a552441f4aa853eb60ca47d05"
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = client.uploadTransaction(uploadReq)

        assertTrue(result is ApiResult.Success)
        val body = (result as ApiResult.Success).data
        assertEquals("DISPENSED", body.status)
        assertEquals("txn_0007", body.transactionId)
        assertEquals("BPM-5397913a552441f4aa853eb60ca47d05", body.paymentReference)
    }

    /** Regression guard for #11: the shape we used to expect must now fail, loudly. */
    @Test
    fun `an unenveloped body is a Serialization failure, not a silent success`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"PENDING_PAYMENT","transactionId":"txn_0007",""" +
                    """"paymentReference":"PR1","authorizationUrl":"https://paystack/x",""" +
                    """"expiresAt":"2026-06-13T19:28:42Z"}""",
            ),
        )

        val result = client.authorise(authoriseReq)

        assertTrue(result is ApiResult.Failure)
        assertTrue((result as ApiResult.Failure).error is ApiError.Serialization)
    }

    // ---- Envelope-level refusals ---------------------------------------------------------------

    /**
     * Reference §1: on failure `status` is false, `data` is absent, and `message` carries the
     * reason. The documented business errors ship as 4xx (covered by the Http arm below), but a
     * 2xx envelope saying no must not be read as success — `data` would be null.
     */
    @Test
    fun `2xx envelope with status false maps to Business carrying the server message`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"status":false,"message":"PETROL is currently out of stock"}""",
            ),
        )

        val result = client.authorise(authoriseReq)

        assertTrue(result is ApiResult.Failure)
        val error = (result as ApiResult.Failure).error
        assertTrue(error is ApiError.Business)
        assertEquals("PETROL is currently out of stock", (error as ApiError.Business).message)
        assertNull(error.httpCode) // envelope-level, not an HTTP status
    }

    @Test
    fun `envelope claiming success with no data is a Business failure, never a crash`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":true,"message":"Transaction authorised"}"""))

        val result = client.authorise(authoriseReq)

        assertTrue(result is ApiResult.Failure)
        assertTrue((result as ApiResult.Failure).error is ApiError.Business)
    }

    /** A refusal is a considered answer, not a blip — the idempotent upload must not retry it. */
    @Test
    fun `upload does not retry an envelope-level refusal`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"status":false,"message":"Payment has not been confirmed for this transaction. """ +
                    """Do not dispense fuel."}""",
            ),
        )

        val result = client.uploadTransaction(uploadReq)

        assertTrue(result is ApiResult.Failure)
        assertTrue((result as ApiResult.Failure).error is ApiError.Business)
        assertEquals(1, server.requestCount)
    }

    // ---- Transport failures (unchanged by #11) -------------------------------------------------

    /**
     * Changed by TODO #14: this used to assert [ApiError.Http] with the reason left as an opaque
     * blob. A 4xx whose body is a failure envelope is a considered refusal, so it now arrives as
     * [ApiError.Business] with the server's own message and the HTTP status filled in.
     */
    @Test
    fun `4xx with a failure envelope maps to Business carrying message and httpCode`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"status":false,"message":"Amount mismatch for PETROL..."}""",
            ),
        )

        val result = client.authorise(authoriseReq)

        assertTrue(result is ApiResult.Failure)
        val error = (result as ApiResult.Failure).error
        assertTrue(error is ApiError.Business)
        assertEquals("Amount mismatch for PETROL...", (error as ApiError.Business).message)
        assertEquals(400, error.httpCode)
        assertNull(error.code) // this endpoint sends no stable code today
    }

    /**
     * Verbatim from docs/api-probes/2026-09-12/body-activate-empty-body.json — the real 400 from
     * api.dev.balancee.app. The `code` sibling is the only stable identifier the server gives us.
     */
    @Test
    fun `the real 400 from activate yields its stable code`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"status":false,"message":"An activation code is required.","code":"INVALID_REQUEST"}""",
            ),
        )

        val result = client.activate("")

        assertTrue(result is ApiResult.Failure)
        val error = (result as ApiResult.Failure).error
        assertTrue(error is ApiError.Business)
        assertEquals("INVALID_REQUEST", (error as ApiError.Business).code)
        assertEquals("An activation code is required.", error.message)
        assertEquals(400, error.httpCode)
    }

    /**
     * Verbatim from docs/api-probes/2026-09-12/body-config-no-auth.json. A 401 with no `code` —
     * the case that makes the field nullable rather than required.
     */
    @Test
    fun `the real 401 maps to Business with a null code`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"status":false,"message":"Missing pump authentication headers"}""",
            ),
        )

        val result = client.config()

        assertTrue(result is ApiResult.Failure)
        val error = (result as ApiResult.Failure).error
        assertTrue(error is ApiError.Business)
        assertEquals("Missing pump authentication headers", (error as ApiError.Business).message)
        assertEquals(401, error.httpCode)
        assertNull(error.code)
    }

    /**
     * VERBATIM from docs/api-probes/2026-09-16-prod-config/api-capture-20260916-171619.txt — the
     * first authenticated /config response this project ever received, captured by the in-app probe
     * panel on production and pulled off the tablet.
     *
     * The DTO this exercises replaced a `Map<FuelType, Long>` that was invented in July from our own
     * summary. Its tests were green throughout. That is the entire reason this fixture is a copy of
     * bytes rather than a description of them.
     */
    @Test
    fun `the real config payload parses into the real shape`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":true,"message":"Pump config","data":{"pumpId":"3727aebf-3c77-4180-a818-4254cbeeae72","stationName":"Kachi","fuelType":"PETROL","pricePerUnit":1490,"updatedAt":"2026-09-15T09:44:39.187Z"}}""",
            ),
        )

        val result = client.config()

        assertTrue(result is ApiResult.Success)
        val config = (result as ApiResult.Success).data
        assertEquals("3727aebf-3c77-4180-a818-4254cbeeae72", config.pumpId)
        assertEquals("Kachi", config.stationName)
        assertEquals(FuelType.PETROL, config.fuelType)
        assertEquals(1490L, config.pricePerUnit)
        assertEquals("2026-09-15T09:44:39.187Z", config.updatedAt)
    }

    /**
     * The failure the old shape could not have: nothing in PumpConfigResponse is defaulted, so a
     * renamed or absent field is a parse error the operator sees, not a silent zero. `pricePerUnit`
     * is the one that matters — a price defaulted to 0 is a wrong-price sale on every litre.
     */
    @Test
    fun `a config missing the price fails loudly instead of defaulting`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":true,"message":"Pump config","data":{"pumpId":"P1","stationName":"Kachi","fuelType":"PETROL","updatedAt":"2026-09-15T09:44:39.187Z"}}""",
            ),
        )

        val result = client.config()

        assertTrue(result is ApiResult.Failure)
        assertTrue((result as ApiResult.Failure).error is ApiError.Serialization)
    }

    /**
     * A decimal price is TODO #18c's open question, observed from the other side: if it ever
     * happens, this must not round money silently. Loud beats plausible.
     */
    @Test
    fun `a decimal price fails loudly rather than rounding`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":true,"message":"Pump config","data":{"pumpId":"P1","stationName":"Kachi","fuelType":"PETROL","pricePerUnit":1490.50,"updatedAt":"2026-09-15T09:44:39.187Z"}}""",
            ),
        )

        val result = client.config()

        assertTrue(result is ApiResult.Failure)
        assertTrue((result as ApiResult.Failure).error is ApiError.Serialization)
    }

    /**
     * A route the backend never built answers with an HTML error page, not an envelope — that is
     * what api.dev.balancee.app actually returns (the control capture). It must stay
     * [ApiError.Http] with the bytes intact, or a deployment mistake would read as the server
     * refusing the sale.
     */
    @Test
    fun `a non-envelope 4xx body stays an Http error with the bytes intact`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setHeader("Content-Type", "text/html")
                .setBody("<!DOCTYPE html><html><head><title>404</title></head></html>"),
        )

        val result = client.authorise(authoriseReq)

        assertTrue(result is ApiResult.Failure)
        val error = (result as ApiResult.Failure).error
        assertTrue(error is ApiError.Http)
        assertEquals(404, (error as ApiError.Http).code)
        assertTrue(error.body!!.contains("<!DOCTYPE html>"))
    }

    @Test
    fun `malformed body maps to Serialization error`() = runBlocking {
        server.enqueue(MockResponse().setBody("not json at all {"))

        val result = client.authorise(authoriseReq)

        assertTrue(result is ApiResult.Failure)
        assertTrue((result as ApiResult.Failure).error is ApiError.Serialization)
    }

    @Test
    fun `signed call without credentials maps to NotActivated and never hits the wire`() = runBlocking {
        store.creds = null

        val result = client.authorise(authoriseReq)

        assertEquals(ApiResult.Failure(ApiError.NotActivated), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `no connectivity maps to Network error`() = runBlocking {
        server.shutdown() // nothing is listening now

        val result = client.authorise(authoriseReq)

        assertTrue(result is ApiResult.Failure)
        assertTrue((result as ApiResult.Failure).error is ApiError.Network)
    }

    @Test
    fun `upload retries a 5xx then succeeds`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "status": true,
                  "message": "Transaction recorded",
                  "data": {
                    "status": "DISPENSED",
                    "transactionId": "txn_0007",
                    "paymentReference": "BPM-5397913a552441f4aa853eb60ca47d05"
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = client.uploadTransaction(uploadReq)

        assertTrue(result is ApiResult.Success)
        assertEquals("DISPENSED", (result as ApiResult.Success).data.status)
        assertEquals(2, server.requestCount) // one failure + one retry that succeeded
    }
}
