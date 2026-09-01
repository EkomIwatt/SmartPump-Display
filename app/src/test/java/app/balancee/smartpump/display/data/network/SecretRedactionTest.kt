// The second door onto the same leak (TODO #12): both types that hold the once-only credentials
// are data classes, so the generated toString() prints apiKey and signingSecret in full. Anything
// that interpolates one — a stray Log.d, a crash report, an ApiResult dump — leaks them just as
// surely as logging the /activate body did.
package app.balancee.smartpump.display.data.network

import app.balancee.smartpump.display.data.network.dto.ActivateResponse
import app.balancee.smartpump.display.domain.network.PumpCredentials
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactionTest {

    private val creds = PumpCredentials(
        deviceId = "device_001",
        apiKey = "bal_live_xxxxxxxxxxxxxxxxxxxx",
        signingSecret = "sec_xxxxxxxxxxxxxxxxxxxxxxxx",
    )

    private val activateResponse = ActivateResponse(
        deviceId = "device_001",
        pumpId = "7f108b57-7559-4837-8dfb-33c7aac7d632",
        apiKey = "bal_live_xxxxxxxxxxxxxxxxxxxx",
        signingSecret = "sec_xxxxxxxxxxxxxxxxxxxxxxxx",
    )

    @Test
    fun `PumpCredentials toString hides both secrets and keeps the deviceId`() {
        val printed = "$creds"

        assertFalse(printed.contains("bal_live_xxxxxxxxxxxxxxxxxxxx"))
        assertFalse(printed.contains("sec_xxxxxxxxxxxxxxxxxxxxxxxx"))
        assertTrue(printed.contains("device_001")) // not secret, and the useful part of a log line
    }

    @Test
    fun `ActivateResponse toString hides both secrets and keeps the ids`() {
        val printed = "$activateResponse"

        assertFalse(printed.contains("bal_live_xxxxxxxxxxxxxxxxxxxx"))
        assertFalse(printed.contains("sec_xxxxxxxxxxxxxxxxxxxxxxxx"))
        assertTrue(printed.contains("device_001"))
        assertTrue(printed.contains("7f108b57-7559-4837-8dfb-33c7aac7d632"))
    }

    /** Redaction must stop at printing — the values themselves still have to round-trip. */
    @Test
    fun `redacting toString does not affect equality or the stored values`() {
        assertEquals(creds, creds.copy())
        assertEquals("sec_xxxxxxxxxxxxxxxxxxxxxxxx", creds.signingSecret)
        assertEquals("bal_live_xxxxxxxxxxxxxxxxxxxx", creds.apiKey)
    }

    /** kotlinx uses the generated serializer, not toString() — the wire must be unchanged. */
    @Test
    fun `ActivateResponse still serialises its real values`() {
        val encoded = Json.encodeToString(activateResponse)

        assertTrue(encoded.contains("bal_live_xxxxxxxxxxxxxxxxxxxx"))
        assertTrue(encoded.contains("sec_xxxxxxxxxxxxxxxxxxxxxxxx"))
    }
}
