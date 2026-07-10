package app.balancee.smartpump.display.data.network

import app.balancee.smartpump.display.data.network.dto.AuthoriseRequest
import app.balancee.smartpump.display.data.network.dto.FuelType
import app.balancee.smartpump.display.data.network.dto.UploadTransactionRequest
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PumpApiClientTest {

    private val creds = PumpCredentials("dev-01", "bal_live_test", "test-signing-secret")
    private val clock = Clock.fixed(Instant.parse("2026-07-03T12:00:00Z"), ZoneOffset.UTC)

    private lateinit var server: MockWebServer
    private lateinit var store: FakeStore
    private lateinit var client: PumpApiClient

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
        client = PumpApiClient(service)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `authorise 2xx maps to Success with parsed DTO`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"status":"PENDING_PAYMENT","transactionId":"T1","paymentReference":"PR1",""" +
                """"authorizationUrl":"https://paystack/x","expiresAt":"2026-07-03T12:05:00Z"}"""
        ))

        val result = client.authorise(authoriseReq)

        assertTrue(result is ApiResult.Success)
        val body = (result as ApiResult.Success).data
        assertEquals("PENDING_PAYMENT", body.status)
        assertEquals("https://paystack/x", body.authorizationUrl)
    }

    @Test
    fun `non-2xx maps to Http error carrying code and body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad amount"}"""))

        val result = client.authorise(authoriseReq)

        assertTrue(result is ApiResult.Failure)
        val error = (result as ApiResult.Failure).error
        assertTrue(error is ApiError.Http)
        assertEquals(400, (error as ApiError.Http).code)
        assertTrue(error.body!!.contains("bad amount"))
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
        server.enqueue(MockResponse().setBody(
            """{"status":"DISPENSED","transactionId":"T1","paymentReference":"PR1"}"""
        ))

        val result = client.uploadTransaction(uploadReq)

        assertTrue(result is ApiResult.Success)
        assertEquals("DISPENSED", (result as ApiResult.Success).data.status)
        assertEquals(2, server.requestCount) // one failure + one retry that succeeded
    }
}
