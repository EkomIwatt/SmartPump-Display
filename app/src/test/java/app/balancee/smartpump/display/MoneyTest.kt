package app.balancee.smartpump.display

import app.balancee.smartpump.display.ui.util.formatNaira
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {

    @Test fun whole_naira_groups_with_commas() {
        assertEquals("₦5,000.00", formatNaira(500_000))
    }

    @Test fun sub_naira_price_keeps_kobo() {
        assertEquals("₦870.50", formatNaira(87_050))
    }

    @Test fun fillup_total_with_kobo() {
        assertEquals("₦33,166.05", formatNaira(3_316_605))
    }

    @Test fun zero() {
        assertEquals("₦0.00", formatNaira(0))
    }

    @Test fun single_kobo_pads() {
        assertEquals("₦0.05", formatNaira(5))
    }

    @Test fun exactly_one_naira() {
        assertEquals("₦1.00", formatNaira(100))
    }
}
