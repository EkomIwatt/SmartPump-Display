package app.balancee.smartpump.display

import app.balancee.smartpump.display.domain.model.DeviceConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceConfigTest {

    private fun config(koboPerLitre: Long) = DeviceConfig(koboPerLitre = koboPerLitre)

    @Test fun cost_rounds_to_nearest_kobo() {
        // 38.1 L × ₦870.50/L = ₦33,166.05 — exact, not the truncated ₦33,166.
        assertEquals(3_316_605L, config(87_050).costKobo(38.1))
    }

    @Test fun cutoff_floors_to_2dp_never_overdispenses() {
        // ₦5,000 at ₦870.50/L = 5.7438… L, floored to 5.74 L (never dispense more than paid).
        assertEquals(5.74, config(87_050).litresCutoff(500_000), 1e-9)
    }

    @Test fun cutoff_exact_litre() {
        assertEquals(1.0, config(87_000).litresCutoff(87_000), 1e-9)
    }

    @Test fun cutoff_is_not_short_changed_by_float_error() {
        // TODO #53: floor(1.15 × 100) in Double is 114.999…, which poured 1.14 L for ₦1,150.
        assertEquals(1.15, config(100_000).litresCutoff(115_000), 1e-9)
    }

    /**
     * Every whole-naira amount the cash screen accepts, at a round and a fractional price, against
     * an exact integer oracle. The float formula failed 137 of these at ₦1,000/L alone.
     */
    @Test fun cutoff_sweep_matches_exact_integer_floor() {
        for (koboPerLitre in listOf(100_000L, 87_050L, 149_000L)) {
            val config = config(koboPerLitre)
            for (naira in 10L..20_000L) {
                val amountKobo = naira * 100
                val expectedCentilitres = amountKobo * 100 / koboPerLitre
                val actual = config.litresCutoff(amountKobo)
                assertEquals(
                    "₦$naira at $koboPerLitre kobo/L",
                    expectedCentilitres,
                    Math.round(actual * 100),
                )
                // And the Double itself must not sit below its own 2 dp value, or a later floor
                // (the pulse limit) could lose the centilitre again.
                assertEquals("₦$naira at $koboPerLitre kobo/L", expectedCentilitres / 100.0, actual, 0.0)
            }
        }
    }

    @Test fun naira_per_litre_keeps_fraction() {
        assertEquals(870.50, config(87_050).nairaPerLitre, 1e-9)
    }
}
