// The single funnel every Pump API call goes through: run the suspend call, translate whatever it
// throws into a typed ApiError. Plus a bounded exponential-backoff retry for idempotent calls.
package app.balancee.smartpump.display.data.network

import app.balancee.smartpump.display.data.network.dto.EnvelopeFailureException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

/**
 * Executes [block] and maps failures to [ApiError]. Coroutine cancellation is never swallowed.
 *
 * Catch order matters: [PumpNotActivatedException] is an [IOException] subtype, so it must be
 * caught before the generic IOException arm. [EnvelopeFailureException] is a plain
 * RuntimeException and must be caught before the trailing Throwable arm, or a considered
 * "no" from the server degrades into [ApiError.Unknown].
 *
 * The HttpException arm reads the envelope back out of the error body (TODO #14) so a 4xx refusal
 * arrives as [ApiError.Business] carrying the server's own reason and code, rather than as an
 * opaque blob the caller has to re-parse. It only does so when the body really is a failure
 * envelope; anything else — an HTML 404, a plain-text 502 from a proxy — stays [ApiError.Http]
 * with the bytes intact. Retryability is unchanged by this: [ApiError.Business] is never retried,
 * and 4xx was never retryable under [isRetryable] either, so nothing that used to back off stops.
 */
suspend fun <T> safeApiCall(block: suspend () -> T): ApiResult<T> =
    try {
        ApiResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: PumpNotActivatedException) {
        ApiResult.Failure(ApiError.NotActivated)
    } catch (e: EnvelopeFailureException) {
        ApiResult.Failure(ApiError.Business(e.serverMessage, e.serverCode))
    } catch (e: HttpException) {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        when (val envelope = parseApiErrorBody(body)) {
            null -> ApiResult.Failure(ApiError.Http(e.code(), body))
            else -> ApiResult.Failure(
                ApiError.Business(envelope.message, envelope.code, e.code()),
            )
        }
    } catch (e: SerializationException) {
        ApiResult.Failure(ApiError.Serialization(e))
    } catch (e: IOException) {
        ApiResult.Failure(ApiError.Network(e))
    } catch (e: Throwable) {
        ApiResult.Failure(ApiError.Unknown(e))
    }

/**
 * Calls [call] up to [maxAttempts] times, backing off [initialDelayMs] × [factor] between tries,
 * but only while the failure [shouldRetry] (default: transient network + 5xx). Returns the first
 * success or the last failure.
 *
 * This is an in-flight retry for a single call window — durable, cross-restart retry of the
 * idempotent upload is WorkManager's job (feature phase), not this.
 */
suspend fun <T> retryingApiCall(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 500,
    factor: Double = 2.0,
    shouldRetry: (ApiError) -> Boolean = { it.isRetryable },
    call: suspend () -> ApiResult<T>,
): ApiResult<T> {
    require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    var delayMs = initialDelayMs
    repeat(maxAttempts - 1) {
        val result = call()
        if (result is ApiResult.Success || !shouldRetry((result as ApiResult.Failure).error)) {
            return result
        }
        delay(delayMs)
        delayMs = (delayMs * factor).toLong()
    }
    return call()
}
