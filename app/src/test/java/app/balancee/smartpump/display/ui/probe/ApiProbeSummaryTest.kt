// The summary functions are the panel's whole judgement; everything else on that card is layout.
// Two of them read backwards from the rest and that is the point of testing them: on the clock-skew
// probe and on the deliberately-wrong authorise, a REFUSAL is the success, and reporting those in
// red would tell the operator the opposite of what happened.
package app.balancee.smartpump.display.ui.probe

import app.balancee.smartpump.display.data.network.ApiError
import app.balancee.smartpump.display.data.network.ApiResult
import app.balancee.smartpump.display.data.network.dto.AuthoriseResponse
import app.balancee.smartpump.display.data.network.dto.TransactionStatusResponse
import app.balancee.smartpump.display.data.network.dto.UploadTransactionResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ApiProbeSummaryTest {

    // ---- /config -------------------------------------------------------------------------

    @Test
    fun `a config success reports the fuel and price it actually received`() {
        val summary = ApiResult.Success(observedConfig).toConfigSummary()

        assertEquals(ProbeTone.Success, summary.tone)
        assertTrue(summary.headline.contains("PETROL"))
        assertTrue(summary.headline.contains("1490"))
        assertTrue(summary.detail.contains("Kachi"))
    }

    @Test
    fun `the price is not dressed up in a currency the server never stated`() {
        // 1490 is naira by inference, not by contract (#18c). A panel that printed ₦1,490.00 would
        // be asserting a unit nobody has confirmed — the same class of move that produced the
        // Map<FuelType, Long> this DTO replaced.
        val summary = ApiResult.Success(observedConfig).toConfigSummary()

        assertFalse(summary.detail.contains("₦"))
        assertTrue(summary.detail.contains("inferred"))
    }

    @Test
    fun `a 200 is reported as proof that GET signing is accepted`() {
        val summary = ApiResult.Success(observedConfig).toConfigSummary()

        assertTrue(summary.detail.contains("GET signing"))
    }

    // ---- clock skew (#15) ------------------------------------------------------------------

    @Test
    fun `a refused stale timestamp is a SUCCESS — it is the observation #15 needs`() {
        val summary = ApiResult.Failure(
            ApiError.Business("Request timestamp is not fresh", code = null, httpCode = 401),
        ).toSkewSummary()

        assertEquals(ProbeTone.Success, summary.tone)
        // The string has to be carried verbatim — it is what the error mapping will match on.
        assertTrue(summary.detail.contains("Request timestamp is not fresh"))
    }

    @Test
    fun `an ACCEPTED stale timestamp is the worrying one`() {
        val summary = ApiResult.Success(observedConfig).toSkewSummary()

        assertEquals(ProbeTone.Caution, summary.tone)
        assertTrue(summary.headline.contains("10 minutes"))
    }

    // ---- /transactions/{id} (#18d) ---------------------------------------------------------

    @Test
    fun `a status success names the status and asks for it to be recorded`() {
        val summary = ApiResult.Success(
            TransactionStatusResponse(
                status = "PENDING_PAYMENT",
                transactionId = "T1",
                paymentReference = null,
            ),
        ).toStatusSummary("T1")

        assertEquals(ProbeTone.Success, summary.tone)
        assertTrue(summary.headline.contains("PENDING_PAYMENT"))
        assertTrue(summary.detail.contains("#18d"))
    }

    @Test
    fun `a status failure says which id was asked about`() {
        // Without it, a 404 in the capture file is unattributable a week later.
        val summary = ApiResult.Failure(
            ApiError.Business("Transaction not found", code = null, httpCode = 404),
        ).toStatusSummary("probe-not-a-real-id")

        assertTrue(summary.detail.contains("probe-not-a-real-id"))
    }

    // ---- /authorise ------------------------------------------------------------------------

    private val authorised = AuthoriseResponse(
        status = "PENDING_PAYMENT",
        transactionId = "T1",
        paymentReference = "PR1",
        authorizationUrl = "https://checkout.paystack.com/abc123",
        expiresAt = "2026-09-16T18:00:00Z",
    )

    @Test
    fun `a happy authorise warns that a real payment now exists`() {
        val summary = ApiResult.Success(authorised).toAuthoriseSummary(AuthoriseVariant.Happy)

        assertEquals(ProbeTone.Success, summary.tone)
        assertTrue(summary.detail.contains("Do not scan"))
    }

    @Test
    fun `a wrong amount that is ACCEPTED is reported as a caution, not a pass`() {
        // The server is documented to enforce amount == litres × price exactly. If it takes an
        // amount a naira out, that is a finding about the server, and a green tick would bury it.
        val summary = ApiResult.Success(authorised).toAuthoriseSummary(AuthoriseVariant.Mismatch)

        assertEquals(ProbeTone.Caution, summary.tone)
        assertTrue(summary.headline.contains("ACCEPTED"))
    }

    @Test
    fun `a wrong amount that is REFUSED is the success, and carries the code question`() {
        val summary = ApiResult.Failure(
            ApiError.Business("Amount mismatch for PETROL", code = null, httpCode = 400),
        ).toAuthoriseSummary(AuthoriseVariant.Mismatch)

        assertEquals(ProbeTone.Success, summary.tone)
        assertTrue(summary.detail.contains("Amount mismatch for PETROL"))
        assertTrue(summary.detail.contains("#18f"))
    }

    @Test
    fun `a refused HAPPY authorise stays a failure — nothing intentional about it`() {
        val summary = ApiResult.Failure(
            ApiError.Business("Amount mismatch for PETROL", code = null, httpCode = 400),
        ).toAuthoriseSummary(AuthoriseVariant.Happy)

        assertEquals(ProbeTone.Failure, summary.tone)
    }

    @Test
    fun `an accepted decimal amount says plainly what has to change in the app`() {
        val summary = ApiResult.Success(authorised).toAuthoriseSummary(AuthoriseVariant.Decimal)

        assertTrue(summary.headline.contains("#18c"))
        assertTrue(summary.detail.contains("decimal type"))
    }

    // ---- /transactions/upload --------------------------------------------------------------

    @Test
    fun `a successful upload is what the upload job was waiting to be told`() {
        val summary = ApiResult.Success(
            UploadTransactionResponse(
                status = "DISPENSED",
                transactionId = "T1",
                paymentReference = "PR1",
            ),
        ).toUploadSummary()

        assertEquals(ProbeTone.Success, summary.tone)
        assertTrue(summary.detail.contains("7e"))
    }

    // ---- shared failure mapping ------------------------------------------------------------

    @Test
    fun `a business refusal reports the absent code rather than hiding it`() {
        val summary = ApiError.Business("Invalid API key", code = null, httpCode = 401).toSummary()

        assertEquals(ProbeTone.Failure, summary.tone)
        assertTrue(summary.headline.contains("401"))
        assertTrue(summary.detail.contains("absent"))
    }

    @Test
    fun `a parse failure says the fixture must be rebuilt from the bytes`() {
        val summary = ApiError.Serialization(IllegalArgumentException("no value")).toSummary()

        assertEquals(ProbeTone.Failure, summary.tone)
        assertTrue(summary.detail.contains("fixture"))
    }

    @Test
    fun `no credentials is a caution — nothing left the device`() {
        val summary = ApiError.NotActivated.toSummary()

        assertEquals(ProbeTone.Caution, summary.tone)
        assertTrue(summary.detail.contains("never left the device"))
    }

    @Test
    fun `a non-envelope body is reported as such, which is what a wrong base URL looks like`() {
        val summary = ApiError.Http(404, "<!DOCTYPE html><title>404</title>").toSummary()

        assertEquals(ProbeTone.Failure, summary.tone)
        assertTrue(summary.detail.contains("wrong base URL"))
    }

    @Test
    fun `a network failure is a caution, not a verdict on the server`() {
        val summary = ApiError.Network(IOException("timeout")).toSummary()

        assertEquals(ProbeTone.Caution, summary.tone)
        assertTrue(summary.detail.contains("timeout"))
    }

    // ---- the state's own guards -------------------------------------------------------------

    @Test
    fun `the production server is recognised, and dev is not mistaken for it`() {
        assertTrue(ApiProbeUiState(baseUrl = "https://api.balancee.app/").productionServer)
        assertFalse(ApiProbeUiState(baseUrl = "https://api.dev.balancee.app/").productionServer)
    }

    @Test
    fun `probing needs credentials — the buttons are dead without them`() {
        assertFalse(ApiProbeUiState(activated = false).canProbe)
        assertTrue(ApiProbeUiState(activated = true).canProbe)
        assertFalse(ApiProbeUiState(activated = true, running = true).canProbe)
    }
}
