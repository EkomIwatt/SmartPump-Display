// The arithmetic behind TODO #18c, which turned out to be the substantive half of the question.
//
// The server checks `amount == expectedLitres × pricePerUnit` exactly. At the observed ₦1490/L most
// litre figures produce a fractional naira amount, and `AuthoriseRequest.amount` is a Long — so the
// sale cannot be expressed, let alone rounded. Whether that is survivable depends on an answer we
// do not have yet; what this file pins down is when the question arises, which is far more often
// than "occasionally".
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
    fun `hundredths are not — this is the case that breaks fill-ups`() {
        val plan = amountFor(2.35, price)

        assertTrue(plan is AmountPlan.Fractional)
        assertEquals(3501.5, (plan as AmountPlan.Fractional).naira, 0.001)
    }

    @Test
    fun `a metered fill-up lands on the broken case as a matter of course`() {
        // A fill-up stops when the tank is full, not on a convenient figure. 38.17 L is the sort of
        // number a real dispense produces, and it cannot be authorised in whole naira.
        assertTrue(amountFor(38.17, price) is AmountPlan.Fractional)
    }

    @Test
    fun `a price ending in 50 kobo would break even half-litres`() {
        // Not hypothetical: the Reference's own worked example uses ₦870.50/L. Our price field is a
        // Long, so such a price cannot even be received today — but the arithmetic is the reason
        // the decimals question matters beyond one field's type.
        assertTrue(amountFor(0.5, 871L) is AmountPlan.Fractional)
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
