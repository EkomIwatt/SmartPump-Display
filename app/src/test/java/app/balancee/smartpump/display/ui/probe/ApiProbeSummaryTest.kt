// toConfigSummary is the panel's whole judgement: everything else on that card is layout. The one
// case worth the most is a 200 that parsed into nothing, which reads as success everywhere else in
// the app and is exactly the shape defect #11 had.
package app.balancee.smartpump.display.ui.probe

import app.balancee.smartpump.display.data.network.ApiError
import app.balancee.smartpump.display.data.network.ApiResult
import app.balancee.smartpump.display.data.network.dto.PumpConfigResponse
import app.balancee.smartpump.display.domain.model.FuelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ApiProbeSummaryTest {

    /** The values are the ones actually observed — docs/api-probes/2026-09-16-prod-config/. */
    private val observedConfig = PumpConfigResponse(
        pumpId = "3727aebf-3c77-4180-a818-4254cbeeae72",
        stationName = "Kachi",
        fuelType = FuelType.PETROL,
        pricePerUnit = 1490,
        updatedAt = "2026-09-15T09:44:39.187Z",
    )

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

    @Test
    fun `a business refusal reports the absent code rather than hiding it`() {
        val summary = ApiResult.Failure(
            ApiError.Business(message = "Invalid API key", code = null, httpCode = 401),
        ).toConfigSummary()

        assertEquals(ProbeTone.Failure, summary.tone)
        assertTrue(summary.headline.contains("401"))
        assertTrue(summary.detail.contains("Invalid API key"))
        assertTrue(summary.detail.contains("absent"))
    }

    @Test
    fun `a parse failure says the fixture must be rebuilt from the bytes`() {
        val summary = ApiResult.Failure(
            ApiError.Serialization(IllegalArgumentException("no value for prices")),
        ).toConfigSummary()

        assertEquals(ProbeTone.Failure, summary.tone)
        assertTrue(summary.detail.contains("fixture"))
    }

    @Test
    fun `no credentials is a caution — nothing left the device`() {
        val summary = ApiResult.Failure(ApiError.NotActivated).toConfigSummary()

        assertEquals(ProbeTone.Caution, summary.tone)
        assertTrue(summary.detail.contains("never left the device"))
    }

    @Test
    fun `a non-envelope body is reported as such, which is what a wrong base URL looks like`() {
        val summary = ApiResult.Failure(
            ApiError.Http(code = 404, body = "<!DOCTYPE html><title>404</title>"),
        ).toConfigSummary()

        assertEquals(ProbeTone.Failure, summary.tone)
        assertTrue(summary.headline.contains("404"))
        assertTrue(summary.detail.contains("wrong base URL"))
    }

    @Test
    fun `a network failure is a caution, not a verdict on the server`() {
        val summary = ApiResult.Failure(ApiError.Network(IOException("timeout"))).toConfigSummary()

        assertEquals(ProbeTone.Caution, summary.tone)
        assertTrue(summary.detail.contains("timeout"))
    }

    @Test
    fun `the production server is recognised, and dev is not mistaken for it`() {
        assertTrue(ApiProbeUiState(baseUrl = "https://api.balancee.app/").productionServer)
        assertFalse(ApiProbeUiState(baseUrl = "https://api.dev.balancee.app/").productionServer)
    }

    @Test
    fun `probing needs credentials — the button is dead without them`() {
        assertFalse(ApiProbeUiState(activated = false).canProbe)
        assertTrue(ApiProbeUiState(activated = true).canProbe)
        assertFalse(ApiProbeUiState(activated = true, running = true).canProbe)
    }
}
