// The arithmetic behind TODO #18c, which turned out to be the substantive half of the question.
//
// The server checks `amount == expectedLitres × pricePerUnit` exactly. At the observed ₦1490/L most
// litre figures produce a fractional naira amount — which, while `amount` was a `Long`, meant the
// sale could not be expressed at all, let alone rounded.
//
// **Both halves are answered now.** The gate observed that the server accepts a decimal
// (#18c, 2026-09-16), and #44 made `amount` a `BigDecimal` so the app can send one. What survives
// here is the frequency claim, which is what made the question urgent and is unchanged: a fractional
// amount is the ordinary case for a metered fill-up, not an edge one. The classification is now a
// label on a probe screen rather than a gate on what can be sent.
package app.balancee.smartpump.display.ui.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountPlanTest {

    /** The real price from docs/api-probes/2026-09-16-prod-config/. */
    private val price = 1490L

    @Test
    fun `whole litres are expressible`() {
        assertEquals(AmountPlan.Exact(2980), amountFor(2.0, price))
    }

    @Test
    fun `tenths of a litre are expressible at a whole-naira price`() {
        // 1490 × 2.3 = 3427 exactly in decimal, and 3426.9999999999995 in binary floating point.
        // If this test ever fails, the epsilon is doing the wrong job.
        assertEquals(AmountPlan.Exact(3427), amountFor(2.3, price))
    }

    @Test
    fun `hundredths are fractional — this is the case that used to break fill-ups`() {
        val plan = amountFor(2.35, price)

        assertTrue(plan is AmountPlan.Fractional)
        assertEquals(3501.5, (plan as AmountPlan.Fractional).naira, 0.001)
    }

    @Test
    fun `a metered fill-up lands on the fractional case as a matter of course`() {
        // A fill-up stops when the tank is full, not on a convenient figure. 38.17 L is the sort of
        // number a real dispense produces. This is why whole naira was never going to be enough.
        assertTrue(amountFor(38.17, price) is AmountPlan.Fractional)
    }

    @Test
    fun `a price ending in 50 kobo makes even half-litres fractional`() {
        // Not hypothetical: the Reference's own worked example uses ₦870.50/L.
        assertTrue(amountFor(0.5, 871L) is AmountPlan.Fractional)
    }

    /**
     * The point of #44: the classification no longer decides whether anything can be sent. Both
     * branches produce a wire amount, and the fractional one is the exact figure the server checks
     * against — 3501.5, which is the literal byte the gate captured.
     */
    @Test
    fun `both branches now produce a sendable wire amount`() {
        assertEquals(0, java.math.BigDecimal("2980").compareTo(amountFor(2.0, price).wireAmount))
        assertEquals(0, java.math.BigDecimal("3501.5").compareTo(amountFor(2.35, price).wireAmount))
    }

    @Test
    fun `the plan is what decides whether the write buttons are live`() {
        val ready = ApiProbeUiState(
            activated = true,
            writesAcknowledged = true,
            litres = "2.0",
            config = observedConfig,
        )
        assertTrue(ready.canWrite)

        // No config means no price, and an authorise built on a guessed price is not a test.
        assertTrue(!ready.copy(config = null).canWrite)
        // The acknowledgement is not decoration.
        assertTrue(!ready.copy(writesAcknowledged = false).canWrite)
        // Nor is a litres box someone has cleared.
        assertTrue(!ready.copy(litres = "").canWrite)
        assertTrue(!ready.copy(litres = "0").canWrite)
    }

    @Test
    fun `upload stays dead until an authorise has actually issued ids`() {
        val state = ApiProbeUiState(
            activated = true,
            writesAcknowledged = true,
            config = observedConfig,
        )

        assertTrue(!state.canUpload)
    }
}
