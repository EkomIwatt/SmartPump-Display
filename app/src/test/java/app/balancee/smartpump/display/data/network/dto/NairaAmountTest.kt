// TODO #44 — how a naira amount reaches the server, and why the type had to change.
//
// The fixtures are not invented. They are the bytes from docs/api-probes/2026-09-16-prod-gate/,
// where `{"amount":3501.5,"expectedLitres":2.35}` at ₦1490/L was accepted and paid for real. The
// serializer's job is to reproduce that exactly: the signature is computed over these bytes, so the
// wire form is not cosmetic.
package app.balancee.smartpump.display.data.network.dto

import app.balancee.smartpump.display.domain.model.FuelType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class NairaAmountTest {

    private val json = Json { encodeDefaults = true }

    private fun bodyFor(amount: BigDecimal, litres: Double): String =
        json.encodeToString(
            AuthoriseRequest.serializer(),
            AuthoriseRequest(
                pumpId = "3727aebf-3c77-4180-a818-4254cbeeae72",
                transactionId = "probe-1d6dc982",
                amount = amount,
                expectedLitres = litres,
                fuelType = FuelType.PETROL,
            ),
        )

    // ---- the wire form --------------------------------------------------------------

    /** The exact request the gate sent and the server accepted. */
    @Test
    fun `a fractional amount goes out as a bare JSON number, matching the captured request`() {
        val body = bodyFor(nairaForSale(litres = 2.35, koboPerLitre = 149_000), litres = 2.35)

        assertTrue(body, body.contains("\"amount\":3501.5"))
        assertTrue("must not be quoted: $body", !body.contains("\"amount\":\""))
    }

    @Test
    fun `a whole amount drops its decimal part rather than sending 2980_00`() {
        val body = bodyFor(nairaForSale(litres = 2.0, koboPerLitre = 149_000), litres = 2.0)

        assertTrue(body, body.contains("\"amount\":2980"))
        assertTrue("no trailing zeros: $body", !body.contains("2980.0"))
    }

    /**
     * `stripTrailingZeros()` renders 3500.00 as `3.5E+3`. That is legal JSON and an absurd thing to
     * find in a payment, so the serializer uses toPlainString(). This test is the reason it does.
     */
    @Test
    fun `a round thousand is not sent in scientific notation`() {
        val body = bodyFor(BigDecimal("3500.00"), litres = 2.0)

        assertTrue(body, body.contains("\"amount\":3500"))
        assertTrue("no exponent: $body", !body.contains("E+"))
    }

    @Test
    fun `an amount survives a round trip without going through a Double`() {
        val original = BigDecimal("33166.05")
        val decoded = json.decodeFromString(
            AuthoriseRequest.serializer(),
            bodyFor(original, litres = 38.1),
        )

        assertEquals(0, original.compareTo(decoded.amount))
    }

    // ---- the arithmetic -------------------------------------------------------------

    /**
     * The conversion the money note has always described. Exact because the scale is set rather
     * than divided.
     */
    @Test
    fun `kobo become naira exactly`() {
        assertEquals(BigDecimal("3501.50"), nairaFromKobo(350_150))
        assertEquals(BigDecimal("0.01"), nairaFromKobo(1))
        assertEquals(BigDecimal("0.00"), nairaFromKobo(0))
    }

    /**
     * The case that killed the `Long`. 1490 × 2.3 is 3426.9999999999995 as a Double — a value that
     * fails an equality check while looking correct in every log and on every screen.
     */
    @Test
    fun `decimal arithmetic does not drift where binary floating point does`() {
        val exact = nairaForSale(litres = 2.3, koboPerLitre = 149_000)

        assertEquals(0, BigDecimal("3427").compareTo(exact))
        // What the naive Double route produces, for contrast.
        assertTrue(2.3 * 1490.0 != 3427.0)
    }

    @Test
    fun `a sub-naira price still lands on a representable amount`() {
        // ₦870.50/L — the Reference's own worked example, and unrepresentable in whole naira.
        assertEquals(0, BigDecimal("33166.05").compareTo(nairaForSale(38.1, 87_050)))
    }

    /**
     * **A finding for 10c, pinned here so it cannot be rediscovered as a surprise.**
     *
     * Pre-pay quotes litres floored to 2dp, so at a price that does not divide the amount evenly the
     * litres are worth *less* than the money taken: ₦5000 buys 3.35 L at ₦1490/L, which is ₦4991.50
     * of fuel. The server checks `amount == expectedLitres × pricePerUnit` exactly, so sending the
     * ₦5000 the customer actually paid **is a refused sale**, not a fifty-kobo discrepancy.
     *
     * That is a product decision rather than a serialization one — charge for the litres, or quote
     * unfloored litres — and 10c has to make it. What this test fixes is that the two figures are
     * genuinely different, so no one can assume they are the same.
     */
    @Test
    fun `pre-pay amount and the exact product disagree when the price does not divide evenly`() {
        val paid = nairaFromKobo(500_000)                       // ₦5000 tendered
        val forFlooredLitres = nairaForSale(3.35, 149_000)      // what 3.35 L is worth

        assertEquals(0, BigDecimal("4991.50").compareTo(forFlooredLitres))
        assertTrue("these must not be assumed equal", paid.compareTo(forFlooredLitres) != 0)
    }
}
