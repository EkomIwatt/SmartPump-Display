// Fixtures marked "captured" are byte-for-byte copies of files in docs/api-probes/2026-09-12/ —
// real responses from api.dev.balancee.app, not restatements of them. The Reference describes the
// failure envelope but never prints one, and a fixture that restates an assumption tests only its
// own self-consistency (TODO #11 is exactly that mistake). If one of these needs editing to make a
// test pass, re-run probe.sh and look at the wire.
package app.balancee.smartpump.display.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiErrorBodyTest {

    // ---- Captured from the live dev server -----------------------------------------------------

    @Test
    fun `captured 400 from activate carries message and stable code`() {
        val parsed = parseApiErrorBody(
            """{"status":false,"message":"An activation code is required.","code":"INVALID_REQUEST"}""",
        )

        assertEquals("An activation code is required.", parsed!!.message)
        assertEquals("INVALID_REQUEST", parsed.code)
    }

    @Test
    fun `captured 401 has no code, which is why the field is nullable`() {
        val parsed = parseApiErrorBody("""{"status":false,"message":"Missing pump authentication headers"}""")

        assertEquals("Missing pump authentication headers", parsed!!.message)
        assertNull(parsed.code)
    }

    @Test
    fun `captured 401 from the fake-credentials probe`() {
        val parsed = parseApiErrorBody("""{"status":false,"message":"Invalid API key"}""")

        assertEquals("Invalid API key", parsed!!.message)
        assertNull(parsed.code)
    }

    // ---- Everything that must NOT be read as a refusal ------------------------------------------

    @Test
    fun `null and blank bodies are not envelopes`() {
        assertNull(parseApiErrorBody(null))
        assertNull(parseApiErrorBody(""))
        assertNull(parseApiErrorBody("   "))
    }

    /** What a route the backend never deployed actually returns. */
    @Test
    fun `an HTML error page is not an envelope`() {
        assertNull(parseApiErrorBody("<!DOCTYPE html><html><head><title>404</title></head></html>"))
    }

    /** A proxy or load balancer failing in front of the API. */
    @Test
    fun `plain text from a proxy is not an envelope`() {
        assertNull(parseApiErrorBody("502 Bad Gateway"))
    }

    @Test
    fun `valid JSON without a status field is not an envelope`() {
        assertNull(parseApiErrorBody("""{"error":"something went wrong"}"""))
        assertNull(parseApiErrorBody("""{"message":"looks close but has no status"}"""))
    }

    @Test
    fun `a JSON array is not an envelope`() {
        assertNull(parseApiErrorBody("""[{"status":false,"message":"nope"}]"""))
    }

    /**
     * A 4xx whose envelope claims success is a contract violation, not a refusal. Flattening it
     * into a Business error would hide the contradiction; the caller keeps the raw body instead.
     */
    @Test
    fun `status true is not a refusal even on an error response`() {
        assertNull(parseApiErrorBody("""{"status":true,"message":"Transaction authorised"}"""))
    }

    /** `"status":"false"` is a string, not the boolean the envelope specifies. */
    @Test
    fun `a stringly-typed status is not accepted`() {
        assertNull(parseApiErrorBody("""{"status":"false","message":"nope"}"""))
    }

    // ---- Shape tolerance -----------------------------------------------------------------------

    @Test
    fun `a bare status false parses with no message`() {
        val parsed = parseApiErrorBody("""{"status":false}""")

        assertEquals(null, parsed!!.message)
        assertNull(parsed.code)
    }

    @Test
    fun `unknown fields are ignored and a null message stays null`() {
        val parsed = parseApiErrorBody(
            """{"status":false,"message":null,"code":"OUT_OF_STOCK","requestId":"abc","data":null}""",
        )

        assertNull(parsed!!.message)
        assertEquals("OUT_OF_STOCK", parsed.code)
    }

    /** `code` is a sibling of `message`, not nested in `data` — the captures decide this. */
    @Test
    fun `a code nested inside data is not read as the stable code`() {
        val parsed = parseApiErrorBody("""{"status":false,"message":"nope","data":{"code":"NESTED"}}""")

        assertNull(parsed!!.code)
    }
}
