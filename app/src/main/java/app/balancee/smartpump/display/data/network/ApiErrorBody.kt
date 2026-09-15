// Reads the response envelope back out of a non-2xx error body (TODO #14).
//
// Reference §1 says every response is enveloped, and §4's error tables list the business messages
// verbatim — but the document never prints a literal failure *body*, so until 2026-09-12 the exact
// bytes were an inference. They are now observed: see docs/api-probes/2026-09-12/, captured against
// api.dev.balancee.app.
//
//   {"status":false,"message":"Missing pump authentication headers"}            (401)
//   {"status":false,"message":"An activation code is required.","code":"INVALID_REQUEST"}  (400)
//
// Two things the captures decide that guesswork would have got wrong:
//
//  1. `code` sits at the TOP level, a sibling of `message` — not inside `data`, which is where a
//     reader of §1 alone would reasonably have put it.
//  2. `code` is NOT always present. The 400 carries one; all three observed 401s do not. So it is
//     nullable, and matching on it must degrade to `message` rather than assume it exists.
//
// Parsing is deliberately conservative: anything that is not a JSON object carrying a real boolean
// `status:false` returns null, and the caller keeps the raw blob as ApiError.Http. A plain-text 502
// from a proxy, an HTML 404 (the shape a route that does not exist actually returns — see the
// control capture), or a 4xx from something that is not this API must never be dressed up as a
// considered refusal from the server.
package app.balancee.smartpump.display.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The envelope fields recovered from an error body. [code] is absent on most paths today. */
data class ApiErrorBody(val message: String?, val code: String?)

private val errorBodyJson = Json { ignoreUnknownKeys = true }

/**
 * [raw] parsed as a failure envelope, or null if it is not one — in which case the caller should
 * fall back to [ApiError.Http] and keep the body as-is.
 *
 * Returns null for a `status:true` body as well: a 4xx whose envelope claims success is a contract
 * violation, not a refusal, and flattening it into [ApiError.Business] would hide that.
 */
fun parseApiErrorBody(raw: String?): ApiErrorBody? {
    if (raw.isNullOrBlank()) return null
    val root = runCatching { errorBodyJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        ?: return null
    val status = (root["status"] as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.booleanOrNull
        ?: return null
    if (status) return null
    return ApiErrorBody(message = root.stringOrNull("message"), code = root.stringOrNull("code"))
}

/** The value at [key] if it is a JSON string; null for absent, null-valued, or non-string. */
private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
