// The literal bytes the server sent, held for the activation gate (TODO #32).
//
// Why this has to exist: PumpApiClient hands callers a parsed DTO, and the parsed object is exactly
// what cannot answer the question the gate asks. Defect #11 got in because our fixtures restated
// the response shape instead of copying it, and a fully green suite agreed with them for two
// months. #32 step 2 therefore says to build the /config fixture "from those bytes, not from our
// restatement" — so something has to keep the bytes.
//
// What it must never keep is the activation response, which carries apiKey and signingSecret
// (Reference §4.1). Rather than write a second allowlist that could drift from the first, this
// reuses PumpLoggingInterceptor.bodyLoggingAllowed(): the predicate that already decides which
// bodies are safe to print, that already omits /activate deliberately, and that already has tests.
// One predicate, one place to be wrong.
package app.balancee.smartpump.display.data.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** One exchange, as it happened. Bodies are decoded text, never a re-serialisation of a parsed object. */
data class ProbeCapture(
    val method: String,
    val path: String,
    val httpCode: Int,
    val at: Instant,
    val body: String,
    /** True when the response was longer than [MAX_CAPTURE_BYTES] — a fixture built from a
     *  truncated body would be a quieter version of the #11 mistake, so it is stated, not implied. */
    val truncated: Boolean,
    /**
     * What we sent, for the calls that send anything.
     *
     * Added 2026-09-16 after the decimal-amount run (#18c): the capture showed a 200 and could not
     * show what had been *asked*, so the finding rested on someone's memory of a text box. A probe
     * that cannot evidence its own input is only half an instrument — and on a POST the input is
     * the entire experiment.
     */
    val requestBody: String? = null,
)

@Singleton
class ProbeResponseRecorder @Inject constructor(private val clock: Clock) {

    private val _captures = MutableStateFlow<List<ProbeCapture>>(emptyList())

    /** Newest first. Bounded — this is a bench instrument, not a log. */
    val captures: StateFlow<List<ProbeCapture>> = _captures.asStateFlow()

    fun record(
        method: String,
        path: String,
        httpCode: Int,
        body: String,
        truncated: Boolean,
        requestBody: String? = null,
    ) {
        val capture = ProbeCapture(
            method = method,
            path = path,
            httpCode = httpCode,
            at = clock.instant(),
            body = body,
            truncated = truncated,
            requestBody = requestBody,
        )
        _captures.update { (listOf(capture) + it).take(MAX_CAPTURES) }
    }

    fun clear() = _captures.update { emptyList() }

    companion object {
        const val MAX_CAPTURES = 20
        const val MAX_CAPTURE_BYTES = 64L * 1024
    }
}

/**
 * Records each body-safe exchange into [recorder]. Installed only in debug builds (NetworkModule).
 *
 * `peekBody` rather than reading the response body: the real one still has to reach Retrofit's
 * converter untouched, and a body consumed here would fail the actual call — an instrument that
 * changes the measurement. The request body is read into a fresh `Buffer` for the same reason, the
 * way PumpSigningInterceptor already does to sign it.
 *
 * The request is captured under the **same allowlist** as the response, so `/activate` — whose
 * request carries the activation code and whose response carries the secrets — is excluded from
 * both by one predicate (#12).
 */
class ProbeCaptureInterceptor(
    private val recorder: ProbeResponseRecorder,
    private val enabled: Boolean,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!enabled) return response

        val path = request.url.encodedPath
        if (!PumpLoggingInterceptor.bodyLoggingAllowed(path)) return response

        val peeked = response.peekBody(ProbeResponseRecorder.MAX_CAPTURE_BYTES)
        val bytes = peeked.bytes()
        recorder.record(
            method = request.method,
            path = path,
            httpCode = response.code,
            body = bytes.toString(Charsets.UTF_8),
            truncated = bytes.size.toLong() >= ProbeResponseRecorder.MAX_CAPTURE_BYTES,
            requestBody = request.body?.let { sent ->
                runCatching {
                    Buffer().use { buffer ->
                        sent.writeTo(buffer)
                        buffer.readString(sent.contentType()?.charset() ?: Charsets.UTF_8)
                    }
                }.getOrNull()
            },
        )
        return response
    }
}
