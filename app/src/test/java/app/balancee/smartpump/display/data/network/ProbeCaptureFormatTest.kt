// The capture file is the artefact that outlives the sitting — it is what a fixture gets built
// from, possibly weeks later. Two properties have to hold: the body survives unescaped, and the
// file says which server it came from. This project now has fixtures from dev and an activation
// code for production, so a capture without its server is a capture that cannot be trusted.
package app.balancee.smartpump.display.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ProbeCaptureFormatTest {

    private val at = Instant.parse("2026-09-16T13:45:09Z")

    private fun capture(body: String, truncated: Boolean = false) = ProbeCapture(
        method = "GET",
        path = "/api/pump/config",
        httpCode = 200,
        at = at,
        body = body,
        truncated = truncated,
    )

    @Test
    fun `the body is written verbatim, not escaped into a container format`() {
        val literal = """{"status":true,"data":{"prices":{"PETROL":87000}}}"""

        val rendered = ProbeCaptureFormat.render(
            baseUrl = "https://api.balancee.app/",
            capturedAt = at,
            captures = listOf(capture(literal)),
        )

        assertTrue(rendered.lines().any { it == literal })
    }

    @Test
    fun `the server is named in the file`() {
        val rendered = ProbeCaptureFormat.render(
            baseUrl = "https://api.dev.balancee.app/",
            capturedAt = at,
            captures = listOf(capture("{}")),
        )

        assertTrue(rendered.contains("https://api.dev.balancee.app/"))
    }

    @Test
    fun `a truncated body is labelled as unusable rather than quietly short`() {
        val rendered = ProbeCaptureFormat.render(
            baseUrl = "https://api.dev.balancee.app/",
            capturedAt = at,
            captures = listOf(capture("xxx", truncated = true)),
        )

        assertTrue(rendered.contains("TRUNCATED"))
        assertTrue(rendered.contains("NOT fixture material"))
    }

    @Test
    fun `an empty capture list says so instead of producing a plausible empty file`() {
        val rendered = ProbeCaptureFormat.render(
            baseUrl = "https://api.dev.balancee.app/",
            capturedAt = at,
            captures = emptyList(),
        )

        assertTrue(rendered.contains("nothing captured"))
    }

    @Test
    fun `the file name carries no colons — they do not survive every filesystem it is copied to`() {
        val name = ProbeCaptureFormat.fileName(at)

        assertEquals("api-capture-20260916-134509.txt", name)
    }
}
