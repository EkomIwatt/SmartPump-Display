package app.balancee.smartpump.display.data.network

import app.balancee.smartpump.display.data.network.dto.ActivateRequest
import app.balancee.smartpump.display.data.network.dto.AuthoriseRequest
import app.balancee.smartpump.display.data.network.dto.FuelType
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PumpSigningInterceptorTest {

    private val creds = PumpCredentials(
        deviceId = "dev-01",
        apiKey = "bal_live_test",
        signingSecret = "test-signing-secret",
    )
    private val fixedTimestamp = "2026-07-03T12:00:00Z"
    private val clock = Clock.fixed(Instant.parse(fixedTimestamp), ZoneOffset.UTC)

    private lateinit var server: MockWebServer
    private lateinit var store: FakeCredentialsStore
    private lateinit var service: PumpApiService

    private class FakeCredentialsStore(var creds: PumpCredentials?) : PumpCredentialsStore {
        override fun current(): PumpCredentials? = creds
        override val isActivated: Boolean get() = creds != null
        override suspend fun save(credentials: PumpCredentials) { creds = credentials }
        override suspend fun clear() { creds = null }
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        store = FakeCredentialsStore(creds)
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val client = OkHttpClient.Builder()
            .addInterceptor(PumpSigningInterceptor(store, clock))
            .build()
        service = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PumpApiService::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `signed POST carries all four headers and signs the exact body sent`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"status":"PENDING_PAYMENT","transactionId":"T1","paymentReference":"PR1",""" +
                """"authorizationUrl":"https://paystack/x","expiresAt":"2026-07-03T12:05:00Z"}"""
        ))

        service.authorise(
            AuthoriseRequest("P1", "T1", 500000, 5.75, FuelType.PETROL)
        )

        val recorded = server.takeRequest()
        assertEquals("bal_live_test", recorded.getHeader("X-Api-Key"))
        assertEquals("dev-01", recorded.getHeader("X-Device-Id"))
        assertEquals(fixedTimestamp, recorded.getHeader("X-Timestamp"))

        // The signature must be over the exact bytes on the wire — recompute from the recorded body.
        val sentBody = recorded.body.readUtf8()
        val expectedSig = PumpRequestSigner.signature(creds.signingSecret, fixedTimestamp, sentBody)
        assertEquals(expectedSig, recorded.getHeader("X-Signature"))
    }

    @Test
    fun `unsigned activation endpoint carries no signing headers`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"deviceId":"dev-01","pumpId":"P1","apiKey":"bal_live_x","signingSecret":"s"}"""
        ))

        service.activate(ActivateRequest("CODE-123", "dev-01"))

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("X-Api-Key"))
        assertNull(recorded.getHeader("X-Device-Id"))
        assertNull(recorded.getHeader("X-Timestamp"))
        assertNull(recorded.getHeader("X-Signature"))
    }

    @Test
    fun `signed GET with no body signs over timestamp and empty body`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"prices":{"PETROL":87050}}"""))

        service.config()

        val recorded = server.takeRequest()
        val expectedSig = PumpRequestSigner.signature(creds.signingSecret, fixedTimestamp, "")
        assertEquals(expectedSig, recorded.getHeader("X-Signature"))
        assertTrue(recorded.body.size == 0L)
    }

    @Test
    fun `signed request without credentials fails instead of sending unsigned`() {
        store.creds = null
        server.enqueue(MockResponse().setBody("{}"))

        try {
            runBlocking { service.config() }
            fail("expected PumpNotActivatedException when signing without credentials")
        } catch (e: PumpNotActivatedException) {
            assertTrue(e.message!!.contains("not activated"))
        }
        // Nothing must have reached the server.
        assertEquals(0, server.requestCount)
    }
}
