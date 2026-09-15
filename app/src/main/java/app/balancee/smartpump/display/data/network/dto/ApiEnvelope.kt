// The standardized response envelope every Pump API endpoint wraps its payload in.
//
// Reference §1: "Every response from the server follows a standardized response envelope"
//
//   { "status": true, "message": "Human readable message", "data": { … } }
//
// On success `status` is true and the real payload sits inside `data`. On failure `status` is
// false, `data` is absent or null, and `message` carries the reason.
//
// `code` is NOT in the Reference. It was found on the wire on 2026-09-12 — the 400 from /activate
// returns {"status":false,"message":"An activation code is required.","code":"INVALID_REQUEST"} —
// and it is the stable error code asked for in BOSS_CONFIRMATIONS_DRAFT item 3. It is optional
// because it is genuinely inconsistent: present on that 400, absent from every observed 401. See
// docs/api-probes/2026-09-12/.
//
// Every PumpApiService method therefore returns ApiEnvelope<T>, never T — PumpApiClient calls
// [unwrap] to get at the payload. Note the collision: the envelope's own `status` is a Boolean
// transport flag, while `data.status` on authorise/upload is the transaction status String
// ("PENDING_PAYMENT", "DISPENSED"). They are unrelated fields that happen to share a name.
package app.balancee.smartpump.display.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiEnvelope<T>(
    @SerialName("status") val status: Boolean,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: T? = null,
    @SerialName("code") val code: String? = null,
)

/**
 * Thrown when the transport succeeded (2xx) but the envelope reported a failure, or reported
 * success with no `data` to unwrap. `safeApiCall` maps this to `ApiError.Business`.
 *
 * Not an IOException — a well-formed "no" from the server is not an I/O problem, and it must
 * never be retried.
 */
class EnvelopeFailureException(
    val serverMessage: String?,
    val serverCode: String? = null,
) : RuntimeException(serverMessage)

/**
 * The payload, or [EnvelopeFailureException] if the server said no.
 *
 * Throwing (rather than returning a nullable) keeps every failure path inside the one
 * `safeApiCall` funnel, so call sites stay one-liners.
 */
fun <T> ApiEnvelope<T>.unwrap(): T {
    if (!status) throw EnvelopeFailureException(message, code)
    return data ?: throw EnvelopeFailureException(
        message ?: "Response envelope reported success but carried no data",
        code,
    )
}
