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

    @Test fun naira_per_litre_keeps_fraction() {
        assertEquals(870.50, config(87_050).nairaPerLitre, 1e-9)
    }
}
