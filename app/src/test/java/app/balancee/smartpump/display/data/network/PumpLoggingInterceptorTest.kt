// TODO #12: the /activate response body carries apiKey + signingSecret (Reference §4.1) and
// HttpLoggingInterceptor.redactHeader() cannot touch bodies. These tests drive a real OkHttp stack
// against MockWebServer with a collecting logger and assert on what was actually written — not on
// how the interceptor is configured, which is the thing that was wrong before.
package app.balancee.smartpump.display.data.network

import app.balancee.smartpump.display.data.network.dto.ActivateRequest
import app.balancee.smartpump.display.data.network.dto.AuthoriseRequest
import app.balancee.smartpump.display.domain.model.FuelType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class PumpLoggingInterceptorTest {

    private lateinit var server: MockWebServer
    private val logged = StringBuilder()
    private val collector = HttpLoggingInterceptor.Logger { message ->
        logged.append(message).append('\n')
    }

    /** Reference §4.1's literal success response — the one body that must never be printed. */
    private val activateResponse = """
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
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun service(enabled: Boolean = true): PumpApiService {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val http = OkHttpClient.Builder()
            .addInterceptor(PumpLoggingInterceptor(enabled = enabled, logger = collector))
            .build()
        return Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PumpApiService::class.java)
    }

    // ---- The leak this ticket exists to close ---------------------------------------------------

    @Test
    fun `activate response body is never written to the log`() = runBlocking {
        server.enqueue(MockResponse().setBody(activateResponse))

        service().activate(ActivateRequest("PMP-O8l6zj", "device_001"))

        val out = logged.toString()
        // The exact strings a leaked logcat would be grepped for.
        assertFalse(out.contains("signingSecret"))
        assertFalse(out.contains("apiKey"))
        assertFalse(out.contains("sec_xxxxxxxxxxxxxxxxxxxxxxxx"))
        assertFalse(out.contains("bal_live_xxxxxxxxxxxxxxxxxxxx"))
    }

    @Test
    fun `activate request body is not logged either — the activation code is single-use`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(activateResponse))

            service().activate(ActivateRequest("PMP-O8l6zj", "device_001"))

            assertFalse(logged.toString().contains("PMP-O8l6zj"))
        }

    /**
     * Guards the obvious wrong fix: silencing logging altogether would pass the tests above while
     * throwing away the debugging value the BODY level exists for.
     */
    @Test
    fun `activate is still logged at header level, so the call is visible`() = runBlocking {
        server.enqueue(MockResponse().setBody(activateResponse))

        service().activate(ActivateRequest("PMP-O8l6zj", "device_001"))

        val out = logged.toString()
        assertTrue(out.contains("api/pump/activate"))
        assertTrue(out.contains("200"))
    }

    // ---- Non-sensitive paths keep full body logging ---------------------------------------------

    @Test
    fun `authorise still logs its body in full`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"status":true,"message":"Transaction authorised","data":{""" +
                    """"status":"PENDING_PAYMENT","transactionId":"txn_0007",""" +
                    """"paymentReference":"PR1","authorizationUrl":"https://paystack/x",""" +
                    """"expiresAt":"2026-06-13T19:28:42Z"}}""",
            ),
        )

        service().authorise(AuthoriseRequest("P1", "txn_0007", 7000, 10.0, FuelType.PETROL))

        val out = logged.toString()
        assertTrue(out.contains("PENDING_PAYMENT"))       // response body logged
        assertTrue(out.contains("\"expectedLitres\""))    // request body logged
    }

    @Test
    fun `logging disabled writes nothing at all`() = runBlocking {
        server.enqueue(MockResponse().setBody(activateResponse))

        service(enabled = false).activate(ActivateRequest("PMP-O8l6zj", "device_001"))

        assertTrue(logged.toString().isEmpty())
    }

    // ---- The allowlist predicate ----------------------------------------------------------------

    @Test
    fun `only vetted paths may log bodies`() {
        // Denied — carries the once-only credentials.
        assertFalse(PumpLoggingInterceptor.bodyLoggingAllowed("/api/pump/activate"))
        // Allowed — vetted as credential-free.
        assertTrue(PumpLoggingInterceptor.bodyLoggingAllowed("/api/pump/authorise"))
        assertTrue(PumpLoggingInterceptor.bodyLoggingAllowed("/api/pump/config"))
        assertTrue(PumpLoggingInterceptor.bodyLoggingAllowed("/api/pump/transactions/upload"))
        assertTrue(PumpLoggingInterceptor.bodyLoggingAllowed("/api/pump/transactions/txn_0007"))
    }

    @Test
    fun `the allowlist is default-deny, so a future endpoint cannot leak by omission`() {
        assertFalse(PumpLoggingInterceptor.bodyLoggingAllowed("/api/pump/rotate-credentials"))
        assertFalse(PumpLoggingInterceptor.bodyLoggingAllowed("/api/pump/activate/retry"))
        assertFalse(PumpLoggingInterceptor.bodyLoggingAllowed("/"))
    }

    @Test
    fun `paths still resolve under a base URL with a prefix`() {
        assertTrue(PumpLoggingInterceptor.bodyLoggingAllowed("/v1/api/pump/authorise"))
        assertFalse(PumpLoggingInterceptor.bodyLoggingAllowed("/v1/api/pump/activate"))
    }
}
