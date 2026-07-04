// Pure signing primitives for the Balancee Pump API. No Android, no OkHttp — just the string and
// HMAC maths, so it's exhaustively unit-testable against golden vectors.
//
// Contract (docs/phase7_blocker_resolution.md → Authentication):
//   X-Signature = HMAC-SHA256(signingSecret, timestamp + "." + rawRequestBody), hex-encoded.
//   timestamp   = ISO-8601 UTC, "yyyy-MM-dd'T'HH:mm:ss'Z'", within 5 min of server clock.
// The signed body MUST be the exact bytes sent — never re-serialize after signing.
package app.balancee.smartpump.display.data.network

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object PumpRequestSigner {

    /** "yyyy-MM-dd'T'HH:mm:ss'Z'" in UTC — no sub-second component, matching the reference. */
    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    /** Formats an instant as the `X-Timestamp` header value. */
    fun timestamp(instant: Instant): String = TIMESTAMP_FORMAT.format(instant)

    /**
     * The exact string that gets HMAC'd: `timestamp + "." + body`. [body] is the empty string for
     * bodyless requests (GETs) — the '.' separator is always present.
     */
    fun signingString(timestamp: String, body: String): String = "$timestamp.$body"

    /**
     * HMAC-SHA256 of [message] under [secret], lower-case hex. (The reference says "hex-encoded"
     * without specifying case; lower-case is the conventional choice — confirm the server accepts
     * it, or is case-insensitive, when the sandbox lands.)
     */
    fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** Convenience: compute the `X-Signature` for a given secret/timestamp/body in one call. */
    fun signature(signingSecret: String, timestamp: String, body: String): String =
        hmacSha256Hex(signingSecret, signingString(timestamp, body))

    private val HEX = "0123456789abcdef".toCharArray()
}
