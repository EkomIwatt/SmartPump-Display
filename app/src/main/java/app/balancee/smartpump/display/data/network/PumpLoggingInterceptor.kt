// Debug HTTP logging for the Pump API that is DEFAULT-DENY about response bodies.
//
// Why this exists (TODO #12): HttpLoggingInterceptor.Level.BODY with redactHeader() redacts
// HEADERS ONLY. The one response that must never be logged — POST /api/pump/activate — carries
// `apiKey` and `signingSecret` in its BODY (Reference §4.1), which is exactly what redactHeader
// cannot touch. `debugRealHw` is a debug build, it runs on the bench tablet against the dev
// backend, and committing logcats to docs/logcats/ is established practice on this project. So the
// realistic failure was a live credential landing in git. Reference §4.1: those secrets are
// "emitted exactly once on activation" — a leak costs a revoke-and-reissue at the station.
//
// The rule here is an ALLOWLIST, not a denylist: a path logs its body only if it appears in
// [BODY_SAFE_PATHS], having been checked. Anything else — including an endpoint added later, such
// as the credential rotation anticipated in OPEN_QUESTIONS #8 — logs headers only until someone
// deliberately opts it in. The failure mode of forgetting is thinner logs, never a leaked secret.
package app.balancee.smartpump.display.data.network

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor

class PumpLoggingInterceptor(
    private val enabled: Boolean,
    logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT,
) : Interceptor {

    private val withBody = build(logger, HttpLoggingInterceptor.Level.BODY)
    private val headersOnly = build(logger, HttpLoggingInterceptor.Level.HEADERS)

    override fun intercept(chain: Interceptor.Chain): Response {
        val allowed = bodyLoggingAllowed(chain.request().url.encodedPath)
        return (if (allowed) withBody else headersOnly).intercept(chain)
    }

    private fun build(
        logger: HttpLoggingInterceptor.Logger,
        bodyLevel: HttpLoggingInterceptor.Level,
    ) = HttpLoggingInterceptor(logger).apply {
        level = if (enabled) bodyLevel else HttpLoggingInterceptor.Level.NONE
        // Still needed: these cover the outbound credential material on every signed request.
        redactHeader("X-Api-Key")
        redactHeader("X-Signature")
    }

    companion object {
        /**
         * Paths whose bodies are safe to print. Matched as a suffix so a base URL with a prefix
         * (`https://host/v1/`) still resolves. `/api/pump/activate` is deliberately absent.
         *
         * Before adding an entry, confirm the endpoint's request AND response bodies carry no
         * credential material — the level applies to both directions.
         */
        private val BODY_SAFE_PATHS = listOf(
            Regex("""/api/pump/authorise$"""),
            Regex("""/api/pump/config$"""),
            Regex("""/api/pump/transactions/upload$"""),
            Regex("""/api/pump/transactions/[^/]+$"""), // transactionStatus
        )

        /** Visible for testing — this class is only as good as this predicate. */
        fun bodyLoggingAllowed(encodedPath: String): Boolean =
            BODY_SAFE_PATHS.any { it.containsMatchIn(encodedPath) }
    }
}
