// The arithmetic behind the pre-pay precision probe (added after 10b surfaced the problem).
//
// A pre-pay customer hands over a round sum. The pump can only promise litres to some finite number
// of decimal places, and the server accepts the sale only if `amount == expectedLitres × price`
// EXACTLY — so the amount that can be charged is the product, and the difference is fuel the
// customer paid for and does not get. How many decimals the server tolerates is therefore a money
// question, and it is the one the Precision probe goes and measures.
package app.balancee.smartpump.display.ui.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class PrecisionQuoteTest {

    /** The real price from docs/api-probes/2026-09-16-prod-config/, in kobo. */
    private val koboPerLitre = 149_000L

    @Test
    fun `at 2dp a five thousand naira pre-pay leaves the customer short`() {
        val q = precisionQuote(BigDecimal("5000"), koboPerLitre, scale = 2)

        assertEquals(BigDecimal("3.35"), q.litres)
        assertEquals(0, BigDecimal("4991.50").compareTo(q.amount))
        assertEquals(0, BigDecimal("8.50").compareTo(q.shortfall))
    }

    @Test
    fun `at 4dp the same sale is short by less than a kobo`() {
        val q = precisionQuote(BigDecimal("5000"), koboPerLitre, scale = 4)

        assertEquals(BigDecimal("3.3557"), q.litres)
        assertEquals(0, BigDecimal("4999.9930").compareTo(q.amount))
        assertTrue("shortfall ${q.shortfall} should be under a kobo", q.shortfall < BigDecimal("0.01"))
    }

    /**
     * The quoted amount is always the exact product, at every scale. That is the whole point: it is
     * what makes the server's equality check pass, and it is what no amount of rounding the tendered
     * figure can achieve.
     */
    @Test
    fun `the quoted amount is always exactly litres times price`() {
        for (scale in 0..6) {
            val q = precisionQuote(BigDecimal("5000"), koboPerLitre, scale)
            val product = q.litres.multiply(BigDecimal.valueOf(koboPerLitre, 2))
            assertEquals("scale $scale", 0, product.compareTo(q.amount))
        }
    }

    /** Never round litres up: the floor is what stops the pump giving away unpaid fuel. */
    @Test
    fun `litres are floored, so the shortfall is never negative`() {
        for (naira in listOf(1000, 2000, 3000, 5000, 7500, 12_345)) {
            val q = precisionQuote(BigDecimal(naira), koboPerLitre, scale = 2)
            assertTrue("$naira: ${q.shortfall}", q.shortfall >= BigDecimal.ZERO)
        }
    }

    /**
     * The worst case at 2dp is one hundredth of a litre's worth, whatever the price — which is the
     * price-independent way to state the cost of the coarse quote, and why it is worth measuring
     * whether a finer one is accepted.
     */
    @Test
    fun `the 2dp shortfall is bounded by one hundredth of a litre at any price`() {
        for (kobo in listOf(87_000L, 149_000L, 87_050L, 250_000L)) {
            val ceiling = BigDecimal.valueOf(kobo, 2).multiply(BigDecimal("0.01"))
            for (naira in listOf(1000, 5000, 9999)) {
                val q = precisionQuote(BigDecimal(naira), kobo, scale = 2)
                assertTrue("$kobo/$naira: ${q.shortfall} vs $ceiling", q.shortfall < ceiling)
            }
        }
    }

    /** A price that divides evenly has no shortfall at all — the case that hides the problem. */
    @Test
    fun `an evenly dividing price hides the whole issue`() {
        val q = precisionQuote(BigDecimal("5000"), koboPerLitre = 125_000L, scale = 2)

        assertEquals(0, BigDecimal("4").compareTo(q.litres))
        assertEquals(0, BigDecimal.ZERO.compareTo(q.shortfall))
    }

    @Test
    fun `the tendered sum for a litre figure is the next whole naira up`() {
        // 2.35 L at ₦1490 is ₦3501.50, so a customer would hand over ₦3502.
        assertEquals(BigDecimal("3502"), tenderedFor(2.35, koboPerLitre))
        // An exact product needs no rounding up.
        assertEquals(BigDecimal("2980"), tenderedFor(2.0, koboPerLitre))
    }
}
