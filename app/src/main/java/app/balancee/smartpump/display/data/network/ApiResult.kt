// Typed outcome for every Pump API call. Callers never see raw exceptions — PumpApiClient funnels
// them into ApiError so ViewModels/WorkManager can branch on the failure kind (retry a transient
// network blip, surface a 4xx, halt on "not activated") without try/catch at every call site.
package app.balancee.smartpump.display.data.network

sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

/** The failure taxonomy PumpApiClient maps every thrown exception into. */
sealed interface ApiError {

    /** No usable response reached us — no connectivity, DNS, timeout, socket reset. Retryable. */
    data class Network(val cause: Throwable) : ApiError

    /** A response came back with a non-2xx status. [body] is the raw error payload if any. */
    data class Http(val code: Int, val body: String?) : ApiError

    /** Response body couldn't be parsed into the expected DTO — a contract mismatch. Not retryable. */
    data class Serialization(val cause: Throwable) : ApiError

    /**
     * The server answered cleanly and said no: the response envelope carried `status:false` (or
     * `status:true` with no `data`), and [message] is the reason string it supplied — e.g.
     * "Amount mismatch for PETROL…", "PETROL is currently out of stock". A considered refusal, so
     * never retryable.
     *
     * [httpCode] is null for envelope-level failures on a 2xx response, and set when the envelope
     * was recovered from a 4xx error body — which is where these messages mostly arrive.
     *
     * [code] is the server's stable error code (e.g. "INVALID_REQUEST"), and the thing worth
     * matching on: the messages are human-readable prose with values interpolated into them, so
     * matching those breaks silently the day someone rewords one. It is nullable because the
     * server is inconsistent about sending it — observed on the 400 from /activate and on none of
     * the 401s (docs/api-probes/2026-09-12/) — so callers must be able to degrade to [message].
     */
    data class Business(
        val message: String?,
        val code: String? = null,
        val httpCode: Int? = null,
    ) : ApiError

    /** A signed call was attempted before the device was activated (no credentials). Not retryable. */
    data object NotActivated : ApiError

    /** Anything else. Not assumed retryable. */
    data class Unknown(val cause: Throwable) : ApiError
}

/**
 * What to do about a failure — three outcomes, not two (TODO #45).
 *
 * The taxonomy had only retryable and terminal, and `ApiError.Business` was flatly terminal on the
 * grounds that it is "a considered refusal". `PAYMENT_NOT_CONFIRMED` is a 409 that parses as
 * `Business` and is **not** a refusal: it is true now and false a minute later, once payment
 * confirms. An upload job treating it as final drops the record permanently — a dispense that never
 * reaches the backend, which is the single outcome the upload job exists to prevent.
 */
enum class RetryPolicy {

    /** Transport trouble. Worth an immediate backoff inside the same call window. */
    RETRY_NOW,

    /**
     * The server answered, and its answer will change on its own. **Not** worth retrying in-flight:
     * three attempts over a second and a half will not outlast a payment confirming, and burning
     * them here hands the durable queue a call that has already given up. This wants rescheduling.
     */
    RETRY_LATER,

    /** Asking again produces the same answer. The only outcome that may discard work. */
    TERMINAL,
}

/**
 * How to treat this failure.
 *
 * `Business` splits on the server's `code` and never on its prose (TODO #18f) — see
 * [PumpErrorCodes.NOT_YET]. A `Business` with no code at all is terminal, which is the safe
 * reading: the app cannot tell a temporary refusal from a permanent one without being told, and
 * retrying an unknown refusal forever is worse than surfacing it to a human once.
 */
val ApiError.retryPolicy: RetryPolicy
    get() = when (this) {
        is ApiError.Network -> RetryPolicy.RETRY_NOW
        is ApiError.Http -> if (code in 500..599) RetryPolicy.RETRY_NOW else RetryPolicy.TERMINAL
        is ApiError.Business ->
            if (code != null && code in PumpErrorCodes.NOT_YET) RetryPolicy.RETRY_LATER
            else RetryPolicy.TERMINAL

        is ApiError.NotActivated -> RetryPolicy.TERMINAL
        is ApiError.Serialization -> RetryPolicy.TERMINAL
        is ApiError.Unknown -> RetryPolicy.TERMINAL
    }

/**
 * Worth retrying **within this call window**, which is narrower than "worth retrying at all".
 *
 * Deliberately false for [RetryPolicy.RETRY_LATER]: that case needs a scheduler, not a tight loop.
 * Unchanged in behaviour from before #45 — transient network and 5xx, nothing else.
 */
val ApiError.isRetryable: Boolean
    get() = retryPolicy == RetryPolicy.RETRY_NOW

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> this
}

/** The success value, or null on failure. */
fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Success)?.data
