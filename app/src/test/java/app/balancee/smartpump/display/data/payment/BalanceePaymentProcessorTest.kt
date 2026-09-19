// Phase 10c — the real processor's authorise half.
//
// Driven through a real PumpApiClient over a hand-written fake PumpApiService, rather than against a
// mocked client: the envelope unwrapping and the error mapping are part of what is being asserted,
// and stubbing the client would skip both. Fixtures are the bytes from
// docs/api-probes/2026-09-16-prod-gate/ and …-prod-config/.
package app.balancee.smartpump.display.data.payment

import app.balancee.smartpump.display.data.config.PumpConfigSync
import app.balancee.smartpump.display.data.network.PumpApiClient
import app.balancee.smartpump.display.data.network.PumpApiService
import app.balancee.smartpump.display.data.network.dto.ActivateRequest
import app.balancee.smartpump.display.data.network.dto.ActivateResponse
import app.balancee.smartpump.display.data.network.dto.ApiEnvelope
import app.balancee.smartpump.display.data.network.dto.AuthoriseRequest
import app.balancee.smartpump.display.data.network.dto.PumpConfigResponse
import app.balancee.smartpump.display.data.network.dto.PumpTransactionResponse
import app.balancee.smartpump.display.data.network.dto.UploadTransactionRequest
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.EventType
import app.balancee.smartpump.display.domain.model.FuelType
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.PaymentRequest
import app.balancee.smartpump.display.domain.model.PaymentResult
import app.balancee.smartpump.display.domain.model.SaleBasis
import app.balancee.smartpump.display.domain.network.DeviceIdProvider
import app.balancee.smartpump.display.ui.customer.FakeDeviceConfigRepository
import app.balancee.smartpump.display.ui.customer.FakeEventRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant

class BalanceePaymentProcessorTest {

    /** Verbatim from docs/api-probes/2026-09-16-prod-config/. */
    private val config = PumpConfigResponse(
        pumpId = "3727aebf-3c77-4180-a818-4254cbeeae72",
        stationName = "Kachi",
        fuelType = FuelType.PETROL,
        pricePerUnit = 1490,
        updatedAt = "2026-09-15T09:44:39.187Z",
    )

    private val service = FakePumpApiService(config)
    private val client = PumpApiClient(service, FixedDeviceId)

    /** Starts holding the server's price, so a test that wants a change has to make one. */
    private val deviceConfig = FakeDeviceConfigRepository(
        DeviceConfig(koboPerLitre = 149_000, fuelType = FuelType.PETROL),
    )
    private val events = FakeEventRepository()

    private val processor = BalanceePaymentProcessor(
        client = client,
        configSync = PumpConfigSync(client, deviceConfig, events),
        events = events,
        transactionIds = { "txn-fixed-0001" },
    )

    private fun tender(amountKobo: Long) = PaymentRequest(
        method = PaymentMethod.BALANCEE_APP,
        amountKobo = amountKobo,
        expectedLitres = 0.0,          // recomputed from /config; deliberately wrong here
        basis = SaleBasis.Tender,
    )

    private fun dispensed(litres: Double) = PaymentRequest(
        method = PaymentMethod.BANK_QR_TRANSFER,
        amountKobo = 0L,               // recomputed from /config; deliberately wrong here
        expectedLitres = litres,
        basis = SaleBasis.Dispensed,
    )

    // ---- what goes out ------------------------------------------------------------

    /**
     * The server's price wins, and the amount is the exact product of it and the quoted litres.
     * ₦5,000 at ₦1,490/L is 3.355 L for ₦4,998.95 — the millilitre quote the precision probe bought.
     */
    @Test
    fun `the body is built from the server's price, not the caller's figures`() = runTest {
        processor.process(tender(500_000)).first()

        val sent = service.lastAuthorise!!
        assertEquals(config.pumpId, sent.pumpId)
        assertEquals("txn-fixed-0001", sent.transactionId)
        assertEquals(0, BigDecimal("4998.95").compareTo(sent.amount))
        assertEquals(3.355, sent.expectedLitres, 0.0)
        assertEquals(FuelType.PETROL, sent.fuelType)
    }

    /**
     * The invariant the whole design turns on. Asserted on what actually left the device rather than
     * on an intermediate, because this is the equality the server evaluates.
     */
    @Test
    fun `amount always equals litres times the server price exactly`() = runTest {
        for (amountKobo in listOf(100_000L, 250_000L, 500_000L, 1_234_500L)) {
            service.lastAuthorise = null
            processor.process(tender(amountKobo)).first()

            val sent = service.lastAuthorise!!
            val product = BigDecimal.valueOf(sent.expectedLitres)
                .multiply(BigDecimal.valueOf(config.pricePerUnit))
            assertEquals("tender $amountKobo", 0, product.compareTo(sent.amount))
        }
    }

    /**
     * `/config` carries the fuel type, so 7b's operator-entered one is no longer what a sale is
     * authorised against. A pump mis-set locally to DIESEL cannot now authorise diesel against a
     * petrol pump's price.
     */
    @Test
    fun `fuel type comes from the server, not the device`() = runTest {
        service.config = config.copy(fuelType = FuelType.DIESEL)

        processor.process(tender(500_000)).first()

        assertEquals(FuelType.DIESEL, service.lastAuthorise!!.fuelType)
    }

    /** A fill-up holds the litres and lets the amount move — the fuel is already gone. */
    @Test
    fun `a dispensed sale is priced on its litres`() = runTest {
        processor.process(dispensed(38.1732)).first()

        val sent = service.lastAuthorise!!
        assertEquals(38.173, sent.expectedLitres, 0.0)
        assertEquals(0, BigDecimal("56877.77").compareTo(sent.amount))
    }

    @Test
    fun `config is fetched before every authorise, never cached`() = runTest {
        processor.process(tender(500_000)).first()
        processor.process(tender(500_000)).first()

        assertEquals(2, service.configCalls)
    }

    // ---- what comes back ----------------------------------------------------------

    @Test
    fun `pending carries the checkout url, the expiry and the reference`() = runTest {
        val pending = processor.process(tender(500_000)).first() as PaymentResult.Pending

        assertEquals("https://checkout.paystack.com/jn0ej3u6def5150", pending.checkoutUrl)
        assertEquals(Instant.parse("2026-09-17T21:46:11.090Z"), pending.expiresAt)
        assertEquals("BPM-990f0b736cb8436fbc8673469f2d1671", pending.paymentReference)
        assertEquals("txn-fixed-0001", pending.transactionRef)
    }

    /**
     * An unparseable expiry must not take the sale down with it — the caller falls back to its own
     * window, which is the same path a response omitting the field takes.
     */
    @Test
    fun `a malformed expiry becomes null rather than an exception`() = runTest {
        service.expiresAt = "not a timestamp"

        val pending = processor.process(tender(500_000)).first() as PaymentResult.Pending

        assertNull(pending.expiresAt)
        assertEquals("https://checkout.paystack.com/jn0ej3u6def5150", pending.checkoutUrl)
    }

    // ---- what fails ---------------------------------------------------------------

    /**
     * A 200 with nothing to scan is worse than a refusal: the screen would show a QR-shaped hole and
     * the customer would stand at it. #46 made the field nullable; this is the caller that cares.
     */
    @Test
    fun `an authorise with no checkout url fails instead of showing an empty QR`() = runTest {
        service.authorizationUrl = null

        val result = processor.process(tender(500_000)).first()

        assertTrue(result is PaymentResult.Failed)
        assertTrue((result as PaymentResult.Failed).reason.contains("payment page"))
    }

    /** Fetch-before-authorise is the correctness guarantee, so a price we cannot read stops the sale. */
    @Test
    fun `an unreadable price stops the sale before anything is authorised`() = runTest {
        service.configFailure = IOException("no route to host")

        val result = processor.process(tender(500_000)).first()

        assertTrue(result is PaymentResult.Failed)
        assertNull("nothing should have been authorised", service.lastAuthorise)
    }

    /** The server's own words, carried through rather than paraphrased into copy nobody agreed. */
    @Test
    fun `a refusal reports the server's message`() = runTest {
        service.authoriseEnvelope = ApiEnvelope(
            status = false,
            message = "Amount mismatch for PETROL",
            code = "AMOUNT_MISMATCH",
        )

        val result = processor.process(tender(500_000)).first()

        assertEquals("Amount mismatch for PETROL", (result as PaymentResult.Failed).reason)
    }

    // ---- the price the customer sees (10c-bis) ------------------------------------

    /**
     * The whole point of 10c-bis: authorising a sale leaves the device holding the price it was
     * authorised at. Before this, `/config` was fetched, used and thrown away, so the figure on the
     * customer's screen stayed whatever an operator last typed.
     */
    @Test
    fun `authorising stores the server's price on the device`() = runTest {
        deviceConfig.config = DeviceConfig(koboPerLitre = 87_000, fuelType = FuelType.PETROL)

        processor.process(tender(500_000)).first()

        assertEquals(149_000L, deviceConfig.config?.koboPerLitre)
    }

    /** The fuel type too — the sale is authorised against it, so the display must not disagree. */
    @Test
    fun `authorising stores the server's fuel type on the device`() = runTest {
        deviceConfig.config = DeviceConfig(koboPerLitre = 149_000, fuelType = FuelType.DIESEL)

        processor.process(tender(500_000)).first()

        assertEquals(FuelType.PETROL, deviceConfig.config?.fuelType)
    }

    /**
     * A fill-up's fuel is already in the tank, so a price change between the nozzle clicking off
     * and this authorise changes what is owed — and the customer watched the old figure climb.
     * Unavoidable (the server checks against its own price), so it is logged rather than hidden.
     */
    @Test
    fun `a price change during a fill-up is recorded against the sale`() = runTest {
        deviceConfig.config = DeviceConfig(koboPerLitre = 87_000, fuelType = FuelType.PETROL)

        processor.process(dispensed(litres = 10.0)).first()

        val race = events.recorded.single { it.type == EventType.PRICE_CHANGED_MID_SALE }
        assertEquals("txn-fixed-0001", race.transactionRef)
        // What the customer saw, and what they are actually being charged.
        assertTrue(race.detail!!.contains("₦870.00 → ₦1,490.00"))
        assertTrue(race.detail.contains("charged ₦14,900.00"))
    }

    /**
     * A pre-pay customer has taken nothing yet and is buying a sum, not a volume, so a re-price just
     * buys them fewer litres. Correct, and not something an operator needs to reconcile.
     */
    @Test
    fun `a price change during a pre-pay sale is not a mid-sale event`() = runTest {
        deviceConfig.config = DeviceConfig(koboPerLitre = 87_000, fuelType = FuelType.PETROL)

        processor.process(tender(500_000)).first()

        assertTrue(events.recorded.none { it.type == EventType.PRICE_CHANGED_MID_SALE })
    }

    /** No change, no row. The log is a record of price moves, not of every authorise. */
    @Test
    fun `an unchanged price logs nothing`() = runTest {
        processor.process(dispensed(litres = 10.0)).first()

        assertTrue(events.recorded.isEmpty())
    }

    /** A tender too small to buy a single step of fuel is refused here, not by the server. */
    @Test
    fun `an amount that buys no fuel never reaches the server`() = runTest {
        val result = processor.process(tender(1)).first()   // ₦0.01 at ₦1,490/L

        assertTrue(result is PaymentResult.Failed)
        assertNull(service.lastAuthorise)
    }
}

private object FixedDeviceId : DeviceIdProvider {
    override fun deviceId(): String = "device-fixed-0001"
}

/**
 * Hand-written, in the project's idiom — no mocking framework. Only the three endpoints this
 * processor touches do anything; the rest exist to satisfy the interface and fail loudly if a future
 * change starts calling them unnoticed.
 */
private class FakePumpApiService(var config: PumpConfigResponse) : PumpApiService {

    var configCalls = 0; private set
    var lastAuthorise: AuthoriseRequest? = null
    var configFailure: Throwable? = null
    var authoriseEnvelope: ApiEnvelope<PumpTransactionResponse>? = null
    var authorizationUrl: String? = "https://checkout.paystack.com/jn0ej3u6def5150"
    var expiresAt: String? = "2026-09-17T21:46:11.090Z"

    override suspend fun config(): ApiEnvelope<PumpConfigResponse> {
        configCalls++
        configFailure?.let { throw it }
        return ApiEnvelope(status = true, message = "Pump config", data = config)
    }

    override suspend fun authorise(body: AuthoriseRequest): ApiEnvelope<PumpTransactionResponse> {
        lastAuthorise = body
        authoriseEnvelope?.let { return it }
        return ApiEnvelope(
            status = true,
            message = "Transaction authorised",
            data = PumpTransactionResponse(
                status = "PENDING_PAYMENT",
                transactionId = body.transactionId,
                paymentReference = "BPM-990f0b736cb8436fbc8673469f2d1671",
                authorizationUrl = authorizationUrl,
                expiresAt = expiresAt,
            ),
        )
    }

    override suspend fun activate(body: ActivateRequest): ApiEnvelope<ActivateResponse> =
        error("activate is not part of the payment path")

    override suspend fun authoriseRaw(body: JsonObject): ApiEnvelope<PumpTransactionResponse> =
        error("authoriseRaw is the probe panel's, not the processor's")

    override suspend fun transactionStatus(transactionId: String): ApiEnvelope<PumpTransactionResponse> =
        error("polling arrives in 10d")

    override suspend fun uploadTransaction(
        body: UploadTransactionRequest,
    ): ApiEnvelope<PumpTransactionResponse> = error("upload arrives in 10f")
}
