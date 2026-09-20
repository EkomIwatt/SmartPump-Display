// Phase 10c-bis — the write-through that makes the displayed price and the authorised price the
// same number.
//
// The defect these cover is not a crash: before 10c-bis every one of these paths "worked", in the
// sense that a sale went through. What was wrong is that nothing connected the two prices, so the
// only reason they agreed was that someone had typed one to match the other.
package app.balancee.smartpump.display.data.config

import app.balancee.smartpump.display.data.network.ApiResult
import app.balancee.smartpump.display.data.network.PumpApiClient
import app.balancee.smartpump.display.data.network.PumpApiService
import app.balancee.smartpump.display.data.network.dto.ActivateRequest
import app.balancee.smartpump.display.data.network.dto.ActivateResponse
import app.balancee.smartpump.display.data.network.dto.ApiEnvelope
import app.balancee.smartpump.display.data.network.dto.AuthoriseRequest
import app.balancee.smartpump.display.data.network.dto.PumpConfigResponse
import app.balancee.smartpump.display.data.network.dto.PumpTransactionResponse
import app.balancee.smartpump.display.data.network.dto.UploadTransactionRequest
import app.balancee.smartpump.display.data.network.dto.UploadTransactionResponse
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.EventType
import app.balancee.smartpump.display.domain.model.FuelType
import app.balancee.smartpump.display.domain.network.DeviceIdProvider
import app.balancee.smartpump.display.ui.customer.FakeDeviceConfigRepository
import app.balancee.smartpump.display.ui.customer.FakeEventRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PumpConfigSyncTest {

    /** Verbatim from docs/api-probes/2026-09-16-prod-config/. ₦1,490/L. */
    private val serverConfig = PumpConfigResponse(
        pumpId = "3727aebf-3c77-4180-a818-4254cbeeae72",
        stationName = "Kachi",
        fuelType = FuelType.PETROL,
        pricePerUnit = 1490,
        updatedAt = "2026-09-15T09:44:39.187Z",
    )

    private val service = FakeConfigService(serverConfig)
    private val deviceConfig = FakeDeviceConfigRepository(config = null)
    private val events = FakeEventRepository()
    private val sync = PumpConfigSync(
        client = PumpApiClient(service, FixedDeviceId),
        deviceConfig = deviceConfig,
        events = events,
    )

    // ---- storing what it reads ----------------------------------------------------

    /**
     * A pump that has been activated but never configured by hand becomes sellable from the
     * backend alone. That is the boss ask this closes: a price change should not be a visit to
     * every pump on the forecourt.
     */
    @Test
    fun `an unconfigured pump takes its price and fuel from the server`() = runTest {
        sync.refresh()

        assertEquals(149_000L, deviceConfig.config?.koboPerLitre)
        assertEquals(FuelType.PETROL, deviceConfig.config?.fuelType)
    }

    /** Naira on the wire, kobo in the app. A x100 lost here is a pump selling at 1/100th price. */
    @Test
    fun `the server's naira price is stored as kobo`() = runTest {
        service.config = serverConfig.copy(pricePerUnit = 870)

        sync.refresh()

        assertEquals(87_000L, deviceConfig.config?.koboPerLitre)
    }

    /** Everything the sync does not own is left exactly as the operator set it. */
    @Test
    fun `a sync leaves the operator's own fields alone`() = runTest {
        deviceConfig.config = DeviceConfig(
            pumpLabel = "PUMP 3",
            stationName = "Total Lekki Ph2",
            koboPerLitre = 87_000,
            fuelType = FuelType.PETROL,
            virtualAccountNumber = "0123456789",
        )

        sync.refresh()

        assertEquals("PUMP 3", deviceConfig.config?.pumpLabel)
        assertEquals("0123456789", deviceConfig.config?.virtualAccountNumber)
    }

    // ---- the station name (10g) ----------------------------------------------------

    /**
     * The receipt's name is the backend's, as of 10g.
     *
     * It was deliberately left alone by 10c-bis, on the grounds that a price sync was the wrong
     * place to resolve a name duplication. Resolved on its own terms since: a receipt is a
     * financial document, so it carries the name the operator's books use — not one an attendant
     * typed. The screens keep `StationIdentity.displayName`, which is branding.
     */
    @Test
    fun `the station name on receipts comes from the backend`() = runTest {
        deviceConfig.config = DeviceConfig(
            stationName = "Total Lekki Ph2",
            koboPerLitre = 149_000,
            fuelType = FuelType.PETROL,
        )

        sync.refresh()

        assertEquals("Kachi", deviceConfig.config?.stationName)
    }

    /** A name change alone is a change, and must be stored even when the price has not moved. */
    @Test
    fun `a station name change alone is written`() = runTest {
        deviceConfig.config = DeviceConfig(
            stationName = "Total Lekki Ph2",
            koboPerLitre = 149_000,
            fuelType = FuelType.PETROL,
        )

        sync.refresh()

        assertEquals(1, deviceConfig.saveCount)
    }

    /** It is a name, not a price: moving it must not put a price-change row in the log. */
    @Test
    fun `a station name change alone is not logged as a price change`() = runTest {
        deviceConfig.config = DeviceConfig(
            stationName = "Total Lekki Ph2",
            koboPerLitre = 149_000,
            fuelType = FuelType.PETROL,
        )

        sync.refresh()

        assertTrue(events.recorded.none { it.type == EventType.PRICE_SYNCED })
    }

    /**
     * A blank name from the backend is treated as a gap in its record, not as a rename. Receipts
     * printing an empty line are worse than receipts printing a stale name.
     */
    @Test
    fun `a blank station name from the backend is ignored`() = runTest {
        service.config = serverConfig.copy(stationName = "   ")
        deviceConfig.config = DeviceConfig(
            stationName = "Total Lekki Ph2",
            koboPerLitre = 149_000,
            fuelType = FuelType.PETROL,
        )

        sync.refresh()

        assertEquals("Total Lekki Ph2", deviceConfig.config?.stationName)
    }

    /** An unconfigured pump takes the backend's name along with its price. */
    @Test
    fun `an unconfigured pump takes the station name too`() = runTest {
        sync.refresh()

        assertEquals("Kachi", deviceConfig.config?.stationName)
    }

    /**
     * An unchanged price writes nothing at all. `updatedAt` must keep meaning "when the price last
     * changed" — bumping it on every authorise would turn it into "when we last had signal", and
     * the operator screen shows it.
     */
    @Test
    fun `nothing is written when nothing moved`() = runTest {
        // The station name is part of "nothing moved" since 10g, so the fixture carries the
        // server's name. Without it this test passes for the wrong reason — a name that differs
        // is a change, and writing it is correct.
        deviceConfig.config = DeviceConfig(
            stationName = "Kachi",
            koboPerLitre = 149_000,
            fuelType = FuelType.PETROL,
        )

        sync.refresh()

        assertEquals(0, deviceConfig.saveCount)
    }

    // ---- the log -------------------------------------------------------------------

    /** The operator's only evidence that the screen's price changed with nobody at the pump. */
    @Test
    fun `a changed price is logged with both figures`() = runTest {
        deviceConfig.config = DeviceConfig(koboPerLitre = 87_000, fuelType = FuelType.PETROL)

        sync.refresh()

        val entry = events.recorded.single()
        assertEquals(EventType.PRICE_SYNCED, entry.type)
        assertTrue(entry.detail!!.contains("₦870.00 → ₦1,490.00"))
    }

    /**
     * A first sync is not a change. Logging one would put a "price changed" row in the log of
     * every pump the day it is activated, which trains an operator to ignore the row.
     */
    @Test
    fun `a pump's first ever price is not logged as a change`() = runTest {
        sync.refresh()

        assertTrue(events.recorded.isEmpty())
    }

    /** A fuel-type correction is stored but is not a price move. */
    @Test
    fun `a fuel type change alone is not logged as a price change`() = runTest {
        deviceConfig.config = DeviceConfig(koboPerLitre = 149_000, fuelType = FuelType.DIESEL)

        sync.refresh()

        assertEquals(FuelType.PETROL, deviceConfig.config?.fuelType)
        assertTrue(events.recorded.isEmpty())
    }

    // ---- a backend with no price (review #7) ---------------------------------------

    /**
     * The defect this section exists for. `pricePerUnit` is a non-null `Long`, so a station whose
     * price has never been set does not fail to parse — it arrives as **0** and every layer above
     * treated it as a price. Storing it wiped the last figure the pump knew, which stops *cash*
     * sales: a flow that consults no backend at all, broken by a field only card sales read.
     */
    @Test
    fun `a zero price from the backend does not overwrite the price the pump knows`() = runTest {
        service.config = serverConfig.copy(pricePerUnit = 0)
        deviceConfig.config = DeviceConfig(koboPerLitre = 149_000, fuelType = FuelType.PETROL)

        sync.refresh()

        assertEquals(149_000L, deviceConfig.config?.koboPerLitre)
    }

    /** Discarding the figure is not discarding the response — the rest of it is still true. */
    @Test
    fun `a zero price still lets the rest of the config through`() = runTest {
        service.config = serverConfig.copy(pricePerUnit = 0)
        deviceConfig.config = DeviceConfig(
            stationName = "Total Lekki Ph2",
            koboPerLitre = 149_000,
            fuelType = FuelType.DIESEL,
        )

        sync.refresh()

        assertEquals("Kachi", deviceConfig.config?.stationName)
        assertEquals(FuelType.PETROL, deviceConfig.config?.fuelType)
        assertEquals(149_000L, deviceConfig.config?.koboPerLitre)
    }

    /** ₦1,490 → ₦0 is not a price change. Logging it as one would be logging a change that was refused. */
    @Test
    fun `a zero price is not logged as a price change`() = runTest {
        service.config = serverConfig.copy(pricePerUnit = 0)
        deviceConfig.config = DeviceConfig(koboPerLitre = 149_000, fuelType = FuelType.PETROL)

        sync.refresh()

        assertTrue(events.recorded.none { it.type == EventType.PRICE_SYNCED })
    }

    /**
     * It is logged as its own thing, though. An operator whose card sales have stopped needs the
     * log to name the one call that explains it, and to say the pump is still selling for cash.
     */
    @Test
    fun `a refused price is logged, naming the price the pump kept`() = runTest {
        service.config = serverConfig.copy(pricePerUnit = 0)
        deviceConfig.config = DeviceConfig(koboPerLitre = 149_000, fuelType = FuelType.PETROL)

        sync.refresh()

        val entry = events.recorded.single()
        assertEquals(EventType.PRICE_SYNC_REJECTED, entry.type)
        assertTrue(entry.detail!!.contains("₦1,490.00"))
    }

    /**
     * `/config` is fetched before **every** authorise, so a row per fetch would bury everything
     * else in the log by the end of a shift. Once per rejected figure per app run is the budget.
     */
    @Test
    fun `a backend with no price is logged once, not once per sale`() = runTest {
        service.config = serverConfig.copy(pricePerUnit = 0)
        deviceConfig.config = DeviceConfig(koboPerLitre = 149_000, fuelType = FuelType.PETROL)

        repeat(5) { sync.refresh() }

        assertEquals(1, events.recorded.count { it.type == EventType.PRICE_SYNC_REJECTED })
    }

    /** A price that comes back is news again, so the next gap is logged rather than swallowed. */
    @Test
    fun `a price that returns and lapses again is logged twice`() = runTest {
        deviceConfig.config = DeviceConfig(koboPerLitre = 149_000, fuelType = FuelType.PETROL)

        service.config = serverConfig.copy(pricePerUnit = 0)
        sync.refresh()
        service.config = serverConfig
        sync.refresh()
        service.config = serverConfig.copy(pricePerUnit = 0)
        sync.refresh()

        assertEquals(2, events.recorded.count { it.type == EventType.PRICE_SYNC_REJECTED })
    }

    /** A pump with nothing to keep says so, and stays shut — the guard reads 0 as Missing.PRICE. */
    @Test
    fun `an unconfigured pump given no price is left unsellable`() = runTest {
        service.config = serverConfig.copy(pricePerUnit = 0)

        sync.refresh()

        assertEquals(0L, deviceConfig.config?.koboPerLitre)
        assertTrue(events.recorded.single().detail!!.contains("no price at all"))
    }

    /** The flag the payment path reads, so a caller never has to rediscover the arithmetic. */
    @Test
    fun `fetch reports a zero price as unusable`() = runTest {
        service.config = serverConfig.copy(pricePerUnit = 0)

        val synced = (sync.fetch() as ApiResult.Success).data

        assertFalse(synced.hasUsablePrice)
        assertFalse(synced.priceChanged)
    }

    // ---- when the server cannot be reached -----------------------------------------

    /**
     * The contract: a failure is not an exception and not a wipe. The pump keeps selling at the
     * last price it knew, which is the whole reason the operator's field survives 10c-bis.
     */
    @Test
    fun `an unreachable server leaves the stored price untouched`() = runTest {
        deviceConfig.config = DeviceConfig(koboPerLitre = 87_000, fuelType = FuelType.PETROL)
        service.failure = IOException("no route to host")

        sync.refresh()

        assertEquals(87_000L, deviceConfig.config?.koboPerLitre)
        assertEquals(0, deviceConfig.saveCount)
        assertTrue(events.recorded.isEmpty())
    }

    // ---- what the caller learns ----------------------------------------------------

    /** The displaced price, which no caller can recover afterwards — the write has already happened. */
    @Test
    fun `fetch reports the price it displaced`() = runTest {
        deviceConfig.config = DeviceConfig(koboPerLitre = 87_000, fuelType = FuelType.PETROL)

        val synced = (sync.fetch() as ApiResult.Success).data

        assertEquals(87_000L, synced.previousKoboPerLitre)
        assertEquals(149_000L, synced.koboPerLitre)
        assertTrue(synced.priceChanged)
    }

    /** No prior price is not a change, so a fresh pump's first sale is not a mid-sale race. */
    @Test
    fun `a first sync does not report a price change`() = runTest {
        val synced = (sync.fetch() as ApiResult.Success).data

        assertNull(synced.previousKoboPerLitre)
        assertFalse(synced.priceChanged)
    }

    @Test
    fun `a failed fetch is a Failure, not a throw`() = runTest {
        service.failure = IOException("no route to host")

        assertTrue(sync.fetch() is ApiResult.Failure)
    }
}

private object FixedDeviceId : DeviceIdProvider {
    override fun deviceId(): String = "device-fixed-0001"
}

/** Only `/config` does anything; everything else fails loudly if this path starts calling it. */
private class FakeConfigService(var config: PumpConfigResponse) : PumpApiService {
    var failure: Throwable? = null

    override suspend fun config(): ApiEnvelope<PumpConfigResponse> {
        failure?.let { throw it }
        return ApiEnvelope(status = true, message = "Pump config", data = config)
    }

    override suspend fun activate(body: ActivateRequest): ApiEnvelope<ActivateResponse> =
        error("activate is not part of the config sync")

    override suspend fun authorise(body: AuthoriseRequest): ApiEnvelope<PumpTransactionResponse> =
        error("authorise is not part of the config sync")

    override suspend fun authoriseRaw(body: JsonObject): ApiEnvelope<PumpTransactionResponse> =
        error("authoriseRaw is the probe panel's")

    override suspend fun transactionStatus(transactionId: String): ApiEnvelope<PumpTransactionResponse> =
        error("polling arrives in 10d")

    override suspend fun uploadTransaction(
        body: UploadTransactionRequest,
    ): ApiEnvelope<UploadTransactionResponse> =
        error("upload arrives in 10f")
}
