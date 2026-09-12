// Activation is the one irreversible call in the app: the code is single-use, the secrets are
// emitted once, and the pumpId is settled permanently. So these tests are mostly about the failure
// half — specifically, about never reporting "activated" for a response we did not actually keep.
//
// MockWebServer rather than a fake client: what is being protected is a real response travelling
// through the real Retrofit/OkHttp stack into the real envelope parser, which is where a response
// gets lost. The success fixture is the Reference's literal §4.1 JSON.
package app.balancee.smartpump.display.data.repository

import app.balancee.smartpump.display.data.network.PumpApiClient
import app.balancee.smartpump.display.data.network.PumpApiService
import app.balancee.smartpump.display.data.network.PumpSigningInterceptor
import app.balancee.smartpump.display.domain.network.DeviceIdProvider
import app.balancee.smartpump.display.domain.network.PumpCredentials
import app.balancee.smartpump.display.domain.network.PumpCredentialsStore
import app.balancee.smartpump.display.domain.repository.ActivationOutcome
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
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PumpActivationRepositoryImplTest {

    private val ourDeviceId = "11111111-2222-3333-4444-555555555555"
    private val issuedPumpId = "7f108b57-7559-4837-8dfb-33c7aac7d632"

    private lateinit var server: MockWebServer
    private lateinit var store: RecordingStore
    private lateinit var repo: PumpActivationRepositoryImpl

    /** Reference §4.1, literal. The one response in this API that cannot be asked for twice. */
    private val activateSuccess = """
        {
          "status": true,
          "message": "Device activated successfully",
          "data": {
            "deviceId": "$ourDeviceId",
            "pumpId": "$issuedPumpId",
            "apiKey": "bal_live_9f8e7d6c5b4a",
            "signingSecret": "sec_a1b2c3d4e5f6"
          }
        }
    """.trimIndent()

    private class FakeDeviceIds(private val id: String) : DeviceIdProvider {
        override fun deviceId(): String = id
    }

    /**
     * A store whose write and whose read-back can be made to fail independently — the two halves
     * the repository checks separately, because a write that "succeeded" into an unreadable blob is
     * the failure that looks most like success.
     */
    private class RecordingStore(
        var throwOnSave: Throwable? = null,
        var readBackAs: PumpCredentials? = null,
        var swallowSave: Boolean = false,
    ) : PumpCredentialsStore {
        var creds: PumpCredentials? = null
        var saveCalls = 0

        // readBackAs only takes effect once something has been written — otherwise the store would
        // report itself already activated and the call would never be attempted.
        override fun current(): PumpCredentials? =
            if (saveCalls > 0) readBackAs ?: creds else creds
        override val isActivated: Boolean get() = current() != null
        override suspend fun save(credentials: PumpCredentials) {
            saveCalls++
            throwOnSave?.let { throw it }
            if (!swallowSave) creds = credentials
        }
        override suspend fun clear() { creds = null }
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        store = RecordingStore()
        val json = Json { ignoreUnknownKeys = true }
        val http = OkHttpClient.Builder()
            .addInterceptor(
                PumpSigningInterceptor(
                    store,
                    Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC),
                ),
            )
            .build()
        val service = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PumpApiService::class.java)
        val deviceIds = FakeDeviceIds(ourDeviceId)
        repo = PumpActivationRepositoryImpl(PumpApiClient(service, deviceIds), store, deviceIds)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() } // one test shuts it down itself
    }

    // ---- The happy path, and the whole point of the seam ----------------------------------------

    @Test
    fun `a successful activation persists every field of the once-only response`() = runBlocking {
        server.enqueue(MockResponse().setBody(activateSuccess))

        val outcome = repo.activate("ACT-CODE-1")

        assertEquals(ActivationOutcome.Activated(issuedPumpId), outcome)
        val saved = store.current()!!
        assertEquals(ourDeviceId, saved.deviceId)
        assertEquals(issuedPumpId, saved.pumpId)
        assertEquals("bal_live_9f8e7d6c5b4a", saved.apiKey)
        assertEquals("sec_a1b2c3d4e5f6", saved.signingSecret)
    }

    @Test
    fun `the device reports activated afterwards`() = runBlocking {
        server.enqueue(MockResponse().setBody(activateSuccess))
        assertTrue(!repo.isActivated)

        repo.activate("ACT-CODE-1")

        assertTrue(repo.isActivated)
    }

    @Test
    fun `the request carries the code and our deviceId`() = runBlocking {
        server.enqueue(MockResponse().setBody(activateSuccess))

        repo.activate("ACT-CODE-1")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("ACT-CODE-1"))
        assertTrue(body.contains(ourDeviceId))
    }

    // ---- Never report success for a response we did not keep ------------------------------------

    @Test
    fun `a write that throws is CredentialsLost, not Activated`() = runBlocking {
        server.enqueue(MockResponse().setBody(activateSuccess))
        store.throwOnSave = IOException("keystore unavailable")

        val outcome = repo.activate("ACT-CODE-1")

        assertTrue(outcome is ActivationOutcome.CredentialsLost)
        assertTrue(
            (outcome as ActivationOutcome.CredentialsLost).detail.contains("revoke and reissue"),
        )
    }

    /** The failure that looks most like success: save returns cleanly, nothing is retrievable. */
    @Test
    fun `a silently-dropped write is caught by the read-back`() = runBlocking {
        server.enqueue(MockResponse().setBody(activateSuccess))
        store.swallowSave = true

        val outcome = repo.activate("ACT-CODE-1")

        assertTrue(outcome is ActivationOutcome.CredentialsLost)
    }

    @Test
    fun `the write is retried once before giving up`() = runBlocking {
        server.enqueue(MockResponse().setBody(activateSuccess))
        store.throwOnSave = IOException("keystore unavailable")

        repo.activate("ACT-CODE-1")

        assertEquals(2, store.saveCalls)
    }

    @Test
    fun `credentials that read back different are not reported as activated`() = runBlocking {
        server.enqueue(MockResponse().setBody(activateSuccess))
        store.readBackAs = PumpCredentials(ourDeviceId, "wrong-pump", "wrong-key", "wrong-secret")

        val outcome = repo.activate("ACT-CODE-1")

        assertTrue(outcome is ActivationOutcome.CredentialsLost)
    }

    /**
     * A response we cannot parse may well have been the successful one, in which case the code is
     * spent and the secrets are gone. Softening this into "try again" would send the station
     * hunting for a second code, or hide that it needs a reissue.
     */
    @Test
    fun `an unreadable success body is CredentialsLost, not a retry`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":true,"data":{"deviceId":"only-this"}}"""))

        val outcome = repo.activate("ACT-CODE-1")

        assertTrue(outcome is ActivationOutcome.CredentialsLost)
    }

    // ---- Refusals: nothing was issued, a fresh code can be tried ---------------------------------

    /** Verbatim from docs/api-probes/2026-09-12/body-activate-empty-body.json. */
    @Test
    fun `the real 400 surfaces the server message and stable code`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"status":false,"message":"An activation code is required.","code":"INVALID_REQUEST"}""",
            ),
        )

        val outcome = repo.activate("")

        assertEquals(
            ActivationOutcome.Refused("An activation code is required.", "INVALID_REQUEST", 400),
            outcome,
        )
        assertNull(store.current())
    }

    @Test
    fun `a spent code is a refusal that stores nothing`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"status":false,"message":"Activation code has already been used"}"""),
        )

        val outcome = repo.activate("ACT-CODE-1")

        assertTrue(outcome is ActivationOutcome.Refused)
        assertNull(store.current())
        assertTrue(!repo.isActivated)
    }

    // ---- Unknown, which is not the same as "no" ---------------------------------------------------

    @Test
    fun `no connectivity is Unreachable rather than a refusal`() = runBlocking {
        server.shutdown()

        val outcome = repo.activate("ACT-CODE-1")

        assertTrue(outcome is ActivationOutcome.Unreachable)
    }

    /** A 5xx can land after the server has already committed the activation. */
    @Test
    fun `a 5xx is Unreachable rather than a refusal`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        val outcome = repo.activate("ACT-CODE-1")

        assertTrue(outcome is ActivationOutcome.Unreachable)
    }

    // ---- Guards -----------------------------------------------------------------------------------

    /**
     * A valid second code would succeed and overwrite the stored credentials, abandoning the pumpId
     * the backend already holds for this unit. Refused locally, so the request is never sent.
     */
    @Test
    fun `activating twice is refused without touching the network`() = runBlocking {
        store.creds = PumpCredentials(ourDeviceId, "existing-pump", "key", "secret")

        val outcome = repo.activate("ACT-CODE-2")

        assertEquals(ActivationOutcome.AlreadyActivated, outcome)
        assertEquals(0, server.requestCount)
        assertEquals("existing-pump", store.current()!!.pumpId)
    }

    /**
     * The credentials are kept: they are the irreplaceable half, and the server's deviceId is the
     * one it will authenticate. The mismatch is reported because nothing downstream could spot it.
     */
    @Test
    fun `a deviceId echo mismatch keeps the credentials and reports the disagreement`() = runBlocking {
        val theirs = "99999999-8888-7777-6666-555555555555"
        server.enqueue(MockResponse().setBody(activateSuccess.replace(ourDeviceId, theirs)))

        val outcome = repo.activate("ACT-CODE-1")

        assertTrue(outcome is ActivationOutcome.IdentityMismatch)
        val mismatch = outcome as ActivationOutcome.IdentityMismatch
        assertEquals(ourDeviceId, mismatch.sent)
        assertEquals(theirs, mismatch.returned)
        assertEquals(theirs, store.current()!!.deviceId)
    }
}
