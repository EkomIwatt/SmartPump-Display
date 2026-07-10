package app.balancee.smartpump.display.data.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Golden-vector tests for the signing primitives. Vectors were computed independently in Python:
 *   hmac.new(secret, timestamp + "." + body, sha256).hexdigest()
 * so they pin the Kotlin implementation to the documented contract, not to itself.
 */
class PumpRequestSignerTest {

    private val secret = "test-signing-secret"
    private val timestamp = "2026-07-03T12:00:00Z"

    @Test
    fun `signing string joins timestamp and body with a dot`() {
        assertEquals("2026-07-03T12:00:00Z.{\"pumpId\":\"P1\"}",
            PumpRequestSigner.signingString(timestamp, "{\"pumpId\":\"P1\"}"))
    }

    @Test
    fun `bodyless request signs over timestamp plus dot plus empty`() {
        assertEquals("2026-07-03T12:00:00Z.", PumpRequestSigner.signingString(timestamp, ""))
    }

    @Test
    fun `hmac matches independently-computed golden vector with body`() {
        val expected = "fb716099ced7eea386a6ccefef9bb06f75384877cebf8282bf9857a8ac47bcb1"
        assertEquals(expected, PumpRequestSigner.signature(secret, timestamp, "{\"pumpId\":\"P1\"}"))
    }

    @Test
    fun `hmac matches golden vector for empty body`() {
        val expected = "4cab4fa0964d49d449f0db4511b58ba0c37bf15b4690755674e17db91d377fcc"
        assertEquals(expected, PumpRequestSigner.signature(secret, timestamp, ""))
    }

    @Test
    fun `signature is lower-case hex of length 64`() {
        val sig = PumpRequestSigner.signature(secret, timestamp, "body")
        assertEquals(64, sig.length)
        assertEquals(sig.lowercase(), sig)
        assert(sig.all { it in "0123456789abcdef" })
    }

    @Test
    fun `timestamp formats an instant as ISO-8601 UTC seconds with trailing Z`() {
        // Instant with sub-second precision → formatter drops the fraction.
        val instant = Instant.parse("2026-07-03T12:00:00.987654Z")
        assertEquals("2026-07-03T12:00:00Z", PumpRequestSigner.timestamp(instant))
    }
}
