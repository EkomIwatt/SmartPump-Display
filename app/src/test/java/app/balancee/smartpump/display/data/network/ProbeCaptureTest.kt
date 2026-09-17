// The recorder is only as good as two things: that it never keeps the one response carrying
// credentials, and that what it keeps is what arrived rather than a re-rendering of it. Both are
// tested here against a real MockWebServer through a real OkHttp chain, because the interceptor's
// job is entirely about how it sits in that chain — a unit test of the recorder alone would prove
// nothing about peekBody leaving the body readable downstream.
package app.balancee.smartpump.display.data.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ProbeCaptureTest {

    private val clock = Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC)

    private lateinit var server: MockWebServer
    private lateinit var recorder: ProbeResponseRecorder
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        recorder = ProbeResponseRecorder(clock)
        client = OkHttpClient.Builder()
            .addInterceptor(ProbeCaptureInterceptor(recorder, enabled = true))
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun get(path: String): String {
        val request = Request.Builder().url(server.url(path)).build()
        return client.newCall(request).execute().use { it.body?.string().orEmpty() }
    }

    @Test
    fun `keeps the body verbatim`() {
        // Deliberately awkward: unicode, a trailing space inside a string, and no pretty-printing.
        val literal = """{"status":true,"message":"OK ₦","data":{"prices":{"PETROL":87000}}}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(literal))

        get("/api/pump/config")

        assertEquals(literal, recorder.captures.value.single().body)
    }

    @Test
    fun `leaves the body readable by the caller`() {
        // peekBody, not body — if this ever regresses, every API call through the app fails while
        // the probe panel looks perfectly healthy.
        val literal = """{"status":true,"data":{}}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(literal))

        assertEquals(literal, get("/api/pump/config"))
    }

    @Test
    fun `never records the activation response`() {
        // The one response carrying apiKey and signingSecret. The values below are fake, and the
        // point of the test is that no part of the body reaches the recorder at all.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":true,"data":{"apiKey":"not-a-real-key","signingSecret":"not-a-real-secret"}}""",
            ),
        )

        get("/api/pump/activate")

        assertTrue(recorder.captures.value.isEmpty())
    }

    @Test
    fun `records failures too — a 401 is a finding, not a non-event`() {
        val literal = """{"status":false,"message":"Invalid API key"}"""
        server.enqueue(MockResponse().setResponseCode(401).setBody(literal))

        get("/api/pump/config")

        val capture = recorder.captures.value.single()
        assertEquals(401, capture.httpCode)
        assertEquals(literal, capture.body)
    }

    @Test
    fun `flags truncation rather than handing back a short body that looks whole`() {
        val huge = "x".repeat((ProbeResponseRecorder.MAX_CAPTURE_BYTES + 1024).toInt())
        server.enqueue(MockResponse().setResponseCode(200).setBody(huge))

        get("/api/pump/config")

        assertTrue(recorder.captures.value.single().truncated)
    }

    @Test
    fun `newest first, and bounded`() {
        repeat(ProbeResponseRecorder.MAX_CAPTURES + 3) { i ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"n":$i}"""))
            get("/api/pump/config")
        }

        val captures = recorder.captures.value
        assertEquals(ProbeResponseRecorder.MAX_CAPTURES, captures.size)
        assertEquals("""{"n":${ProbeResponseRecorder.MAX_CAPTURES + 2}}""", captures.first().body)
    }

    @Test
    fun `records nothing when disabled`() {
        val disabled = OkHttpClient.Builder()
            .addInterceptor(ProbeCaptureInterceptor(recorder, enabled = false))
            .build()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":true}"""))

        val request = Request.Builder().url(server.url("/api/pump/config")).build()
        disabled.newCall(request).execute().close()

        assertTrue(recorder.captures.value.isEmpty())
    }

    @Test
    fun `clear empties the list`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":true}"""))
        get("/api/pump/config")
        assertFalse(recorder.captures.value.isEmpty())

        recorder.clear()

        assertTrue(recorder.captures.value.isEmpty())
    }

    @Test
    fun `a POST keeps what was sent, not only what came back`() {
        // The gap this closes: the decimal-amount run (#18c) produced a capture showing a 200 and no
        // record of the amount that earned it, so the finding rested on someone's memory of a text
        // box. On a POST the request IS the experiment.
        val sent = """{"pumpId":"P1","amount":3501.5,"expectedLitres":2.35}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":true}"""))

        val request = Request.Builder()
            .url(server.url("/api/pump/authorise"))
            .post(sent.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().close()

        assertEquals(sent, recorder.captures.value.single().requestBody)
    }

    @Test
    fun `the request body still reaches the server unconsumed`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":true}"""))
        val sent = """{"amount":3501.5}"""

        val request = Request.Builder()
            .url(server.url("/api/pump/authorise"))
            .post(sent.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().close()

        assertEquals(sent, server.takeRequest().body.readUtf8())
    }

    @Test
    fun `a GET has no request body to keep`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":true}"""))

        get("/api/pump/config")

        assertNull(recorder.captures.value.single().requestBody)
    }

    @Test
    fun `the activation request is not kept either — it carries the code`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":true}"""))

        val request = Request.Builder()
            .url(server.url("/api/pump/activate"))
            .post("""{"activationCode":"not-a-real-code"}""".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().close()

        assertTrue(recorder.captures.value.isEmpty())
    }
}
