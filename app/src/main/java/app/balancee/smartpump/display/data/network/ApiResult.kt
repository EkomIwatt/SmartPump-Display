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

    /** A signed call was attempted before the device was activated (no credentials). Not retryable. */
    data object NotActivated : ApiError

    /** Anything else. Not assumed retryable. */
    data class Unknown(val cause: Throwable) : ApiError
}

/** True for failures worth retrying: transient network, and 5xx server errors. */
val ApiError.isRetryable: Boolean
    get() = when (this) {
        is ApiError.Network -> true
        is ApiError.Http -> code in 500..599
        else -> false
    }

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> this
}

/** The success value, or null on failure. */
fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Success)?.data
