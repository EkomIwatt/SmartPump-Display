// Phase 10c — the quote that satisfies the server, the payment rail and the customer at once.
//
// The invariants are exhaustively checked across a spread of prices rather than asserted on one
// example, because the whole finding behind this file is that a rule which holds at ₦1,490 can fail
// at ₦1,491. A test that only used today's price would have passed on the version of this code that
// produced uncollectable amounts.
package app.balancee.smartpump.display.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SaleQuoteTest {

    /** Prices in kobo/L: today's, a neighbour that behaves differently, the Reference's sub-naira
     *  example, one that divides cleanly, and a prime-ish awkward case. */
    private val prices = listOf(149_000L, 149_100L, 87_050L, 125_000L, 100_003L)

    private val tenders = listOf(50_000L, 100_000L, 200_000L, 500_000L, 750_000L, 1_234_500L)

    // ---- the invariants, across every price ---------------------------------------

    /**
     * The one that the API alone would not have caught. The server accepted ₦3,506.864 quite
     * happily; Paystack cannot collect 350,686.4 kobo. `amountKobo` being a Long is the guarantee,
     * and this asserts the arithmetic actually lands there rather than truncating to it.
     */
    @Test
    fun `the amount is always exactly litres times price, in whole kobo`() {
        for (price in prices) {
            for (tender in tenders) {
                val q = quoteForTender(tender, price)
                val exact = q.litres.multiply(BigDecimal.valueOf(price))
                assertEquals(
                    "price=$price tender=$tender litres=${q.litres}",
                    0,
                    exact.compareTo(BigDecimal.valueOf(q.amountKobo)),
                )
            }
        }
    }

    /** Never dispense more than was paid for. The shortfall is the customer's, never the station's. */
    @Test
    fun `a pre-pay quote never exceeds the tender`() {
        for (price in prices) {
            for (tender in tenders) {
                val q = quoteForTender(tender, price)
                assertTrue("price=$price tender=$tender", q.amountKobo <= tender)
                assertTrue("price=$price tender=$tender", q.shortfallKobo >= 0)
            }
        }
    }

    /** The server has only ever been observed to accept four decimal places of litres. */
    @Test
    fun `quoted litres never exceed four decimal places`() {
        for (price in prices) {
            for (tender in tenders) {
                val q = quoteForTender(tender, price)
                assertTrue("price=$price → ${q.litres}", q.litres.scale() <= 4)
            }
        }
    }

    /**
     * The quote is the best available at that price: one step more litres would cost more than was
     * tendered. Without this, "always whole kobo" could be satisfied by quoting nothing at all.
     */
    @Test
    fun `a pre-pay quote is the most the tender can buy`() {
        for (price in prices) {
            for (tender in tenders) {
                val q = quoteForTender(tender, price)
                val oneStepMore = q.amountKobo + q.litreStepMicros * price / 10_000
                assertTrue(
                    "price=$price tender=$tender could have afforded more",
                    oneStepMore > tender,
                )
            }
        }
    }

    // ---- the step is derived from the price, not chosen ----------------------------

    /**
     * The table from the probe finding. ₦1,490 permits 0.001 L; one naira more permits only 0.01 L.
     * That cliff is why the step is computed per price instead of fixed at a constant.
     */
    @Test
    fun `the litre step is whatever the price permits`() {
        assertEquals(10L, litreStepMicrosFor(149_000L))   // 0.001 L
        assertEquals(100L, litreStepMicrosFor(149_100L))  // 0.01 L
        assertEquals(200L, litreStepMicrosFor(87_050L))   // 0.02 L
        assertEquals(2L, litreStepMicrosFor(125_000L))    // 0.0002 L
    }

    /**
     * The regression this file exists for. At ₦870.50 no plain decimal scale guarantees a whole
     * kobo — 3.35 L × 87_050 is 291,617.5 kobo — so a rule of "floor to 2dp" produces an amount
     * Paystack cannot charge. The step must be 0.02 L here.
     */
    @Test
    fun `a sub-naira price does not get a fractional kobo amount`() {
        val q = quoteForTender(tenderedKobo = 500_000, koboPerLitre = 87_050)

        assertEquals(0, BigDecimal("5.74").compareTo(q.litres))
        assertEquals(499_667L, q.amountKobo)
        assertEquals(333L, q.shortfallKobo)
    }

    // ---- what it costs the customer ------------------------------------------------

    /** Today's price, and the improvement the probe bought: 3dp rather than 2dp. */
    @Test
    fun `at today's price a five thousand naira pre-pay is quoted to the millilitre`() {
        val q = quoteForTender(tenderedKobo = 500_000, koboPerLitre = 149_000)

        assertEquals(0, BigDecimal("3.355").compareTo(q.litres))
        assertEquals(499_895L, q.amountKobo)   // ₦4,998.95
        assertEquals(105L, q.shortfallKobo)    // ₦1.05, against ₦8.50 at 2dp
    }

    @Test
    fun `an evenly dividing price leaves the customer nothing short`() {
        val q = quoteForTender(tenderedKobo = 500_000, koboPerLitre = 125_000)

        assertEquals(0, BigDecimal("4").compareTo(q.litres))
        assertEquals(500_000L, q.amountKobo)
        assertEquals(0L, q.shortfallKobo)
    }

    // ---- fill-ups: measured, not chosen --------------------------------------------

    /**
     * A fill-up has no tender to fall short of — the fuel is already gone. It still has to land on a
     * payable step, and it floors, which costs the station a fraction of a kobo rather than the
     * customer.
     */
    @Test
    fun `a fill-up floors onto a payable step with no shortfall`() {
        val q = quoteForDispensed(litres = 38.1732, koboPerLitre = 149_000)

        assertEquals(0, BigDecimal("38.173").compareTo(q.litres))
        assertEquals(5_687_777L, q.amountKobo)
        assertEquals(0L, q.shortfallKobo)
        assertTrue(q.amountKobo <= Math.round(38.1732 * 149_000))
    }

    @Test
    fun `a fill-up of nothing is a quote of nothing, not a crash`() {
        val q = quoteForDispensed(litres = 0.0, koboPerLitre = 149_000)

        assertEquals(0, BigDecimal.ZERO.compareTo(q.litres))
        assertEquals(0L, q.amountKobo)
    }

    @Test
    fun `a price of zero is rejected rather than dividing by it`() {
        for (bad in listOf(0L, -1L)) {
            try {
                quoteForTender(500_000, bad)
                throw AssertionError("expected a refusal for price $bad")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("positive"))
            }
        }
    }
}
