package app.balancee.smartpump.display.ui.customer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invariant: **what is committed is what is on the screen, or nothing is committed.**
 *
 * The first test is the 10g gate's defect, reproduced at the price the pump was actually running
 * (₦1,490/L). Before the fix it committed 2008 and the wire carried ₦2,007.03.
 */
class CustomAmountEntryTest {

    private val price = 149_000L // ₦1,490/L — what production returned on 2026-09-19

    private fun type(entry: CustomAmountEntry, digits: String): CustomAmountEntry =
        digits.fold(entry) { acc, c -> acc.digit(Character.getNumericValue(c), price) }

    @Test
    fun `a stray digit does not outlive its deletion`() {
        // Types 2008, spots the stray 8, deletes it. The screen now reads 200.
        val entry = type(CustomAmountEntry(), "2008").backspace(price)

        assertEquals("200", entry.typed)
        assertEquals(
            "the deleted digit was still being charged: this is the 10g overcharge",
            200,
            entry.committedNaira,
        )
    }

    @Test
    fun `deleting into an invalid value commits nothing rather than the last valid one`() {
        // 200 is the floor, so "20" is invalid. The old code kept 200 committed and relied on a
        // validity gate; this asserts the commitment itself is gone.
        val entry = type(CustomAmountEntry(), "200").backspace(price)

        assertEquals("20", entry.typed)
        assertFalse(entry.valid)
        assertNull(entry.committedNaira)
    }

    @Test
    fun `deleting everything commits nothing`() {
        var entry = type(CustomAmountEntry(), "500")
        assertEquals(500, entry.committedNaira)

        repeat(3) { entry = entry.backspace(price) }

        assertEquals("", entry.typed)
        assertNull(entry.committedNaira)
    }

    @Test
    fun `retyping after a deletion commits the new value`() {
        val entry = type(CustomAmountEntry(), "2008").backspace(price).digit(5, price)

        assertEquals("2005", entry.typed)
        assertEquals(2005, entry.committedNaira)
    }

    @Test
    fun `every prefix either commits itself or commits nothing`() {
        // The invariant, walked over a whole entry. No keystroke may leave a commitment that
        // disagrees with the text beside it.
        var entry = CustomAmountEntry()
        for (c in "123456") {
            entry = entry.digit(Character.getNumericValue(c), price)
            val committed = entry.committedNaira
            if (committed != null) {
                assertEquals(entry.typed.toDouble().toInt(), committed)
            } else {
                assertFalse("committed nothing, so it must not be valid", entry.valid)
            }
        }
    }

    @Test
    fun `backspace on empty input is a no-op`() {
        val entry = CustomAmountEntry().backspace(price)

        assertEquals("", entry.typed)
        assertNull(entry.committedNaira)
    }

    @Test
    fun `litres mode commits naira, and a deletion re-derives it`() {
        var entry = CustomAmountEntry(mode = AmountEntryMode.LITRES)
        entry = type(entry, "12")

        assertTrue(entry.valid)
        assertEquals(12 * 1_490, entry.committedNaira)

        entry = entry.backspace(price)

        assertEquals("1", entry.typed)
        assertEquals(1_490, entry.committedNaira)
    }

    @Test
    fun `litres above the maximum commit nothing`() {
        val entry = type(CustomAmountEntry(mode = AmountEntryMode.LITRES), "201")

        assertFalse(entry.valid)
        assertNull(entry.committedNaira)
    }

    @Test
    fun `clearing drops both the text and the commitment`() {
        val entry = type(CustomAmountEntry(), "5000").cleared()

        assertEquals("", entry.typed)
        assertNull(entry.committedNaira)
        assertEquals(AmountEntryMode.AMOUNT, entry.mode)
    }

    @Test
    fun `clearing to the other mode keeps the mode and drops the value`() {
        val entry = type(CustomAmountEntry(), "5000").cleared(AmountEntryMode.LITRES)

        assertEquals(AmountEntryMode.LITRES, entry.mode)
        assertNull(entry.committedNaira)
    }

    @Test
    fun `a trailing decimal point is not a commitment of its own`() {
        // "200." parses as 200.0, which is valid — so it commits 200, matching what is shown.
        val entry = type(CustomAmountEntry(), "200").decimal(price)

        assertEquals("200.", entry.typed)
        assertEquals(200, entry.committedNaira)
    }
}
