// Renders captures into the file that leaves the tablet.
//
// Plain text, and deliberately not JSON: what gets copied into a fixture is the body verbatim, and
// wrapping the bodies in a second JSON document would mean escaping them — at which point the file
// no longer contains the bytes it was made to preserve.
//
// Every file states its server. A /config payload is meaningless without knowing whether it came
// from dev or production, and this project now has an activation code for one and fixtures from the
// other.
package app.balancee.smartpump.display.data.network

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object ProbeCaptureFormat {

    private val FILE_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    /** Colons are legal on ext4 and not on every host a file gets copied to. Avoided. */
    fun fileName(at: Instant): String = "api-capture-${FILE_STAMP.format(at)}.txt"

    fun render(baseUrl: String, capturedAt: Instant, captures: List<ProbeCapture>): String =
        buildString {
            appendLine("# SmartPump API capture")
            appendLine("# server:     $baseUrl")
            appendLine("# written:    $capturedAt")
            appendLine("# responses:  ${captures.size} (newest first)")
            appendLine(
                "# NOTE: bodies are verbatim. Build fixtures from these bytes, not from a " +
                    "restatement of them (TODO #11).",
            )
            if (captures.isEmpty()) {
                appendLine()
                appendLine("(nothing captured — run a probe first)")
            }
            captures.forEach { capture ->
                appendLine()
                appendLine("---- ${capture.method} ${capture.path} → ${capture.httpCode}")
                appendLine("---- at ${capture.at}")
                capture.requestBody?.let { sent ->
                    appendLine("---- sent:")
                    appendLine(sent)
                    appendLine("---- received:")
                }
                if (capture.truncated) {
                    appendLine("---- TRUNCATED at ${ProbeResponseRecorder.MAX_CAPTURE_BYTES} bytes — NOT fixture material")
                }
                appendLine(capture.body)
            }
        }
}
