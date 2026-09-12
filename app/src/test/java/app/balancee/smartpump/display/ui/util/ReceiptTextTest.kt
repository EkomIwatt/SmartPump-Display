// The receipt is the only artefact of a sale a customer takes away with them, and once shared it
// cannot be corrected. These assert on the exact characters rather than on "contains a number".
package app.balancee.smartpump.display.ui.util

import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.FuelType
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.Transaction
import app.balancee.smartpump.display.domain.model.TransactionFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ReceiptTextTest {

    /** Fixed so the test does not depend on the machine's timezone. */
    private val lagos = ZoneId.of("Africa/Lagos")

    /** 2026-09-12T13:32:00Z = 14:32 in Lagos (UTC+1, no DST). */
    private val createdAt = 1_789_219_920_000L

    private val config = DeviceConfig(
        pumpLabel = "PUMP 1",
        stationName = "Total Lekki Ph2",
        koboPerLitre = 87_050,
        fuelType = FuelType.PETROL,
    )

    private val transaction = Transaction(
        id = "BLC-00847",
        flow = TransactionFlow.FIXED_PREPAY_DIGITAL,
        paymentMethod = PaymentMethod.BANK_QR_TRANSFER,
        litresDispensed = 5.75,
        amountKobo = 500_537,
        priceKoboPerLitre = 87_050,
        transactionRef = "BLC-847",
        createdAt = createdAt,
    )

    @Test
    fun `a complete receipt reads exactly as expected`() {
        val text = buildReceiptText(transaction, config, lagos)

        assertEquals(
            """
            Total Lekki Ph2
            PUMP 1

            Receipt BLC-847
            12 Sep 2026, 14:32

            Fuel: Petrol
            Litres: 5.75 L
            Price: ₦870.50 per litre
            Total: ₦5,005.37

            Paid by bank transfer
            Thank you for your patronage.
            """.trimIndent(),
            text,
        )
    }

    /** Sub-naira pricing is the reason money is carried as kobo; the receipt must not round it. */
    @Test
    fun `a sub-naira price survives to the receipt`() {
        val text = buildReceiptText(transaction, config, lagos)

        assertTrue(text.contains("₦870.50 per litre"))
    }

    @Test
    fun `litres always carry two decimals`() {
        val text = buildReceiptText(transaction.copy(litresDispensed = 10.0), config, lagos)

        assertTrue(text.contains("Litres: 10.00 L"))
    }

    @Test
    fun `the timestamp is the transaction's own, not the time of sharing`() {
        val older = transaction.copy(createdAt = createdAt - 6 * 60 * 60 * 1000L)

        val text = buildReceiptText(older, config, lagos)

        assertTrue(text.contains("12 Sep 2026, 08:32"))
    }

    // ---- Payment method wording ------------------------------------------------------------------

    /**
     * The two cash flows carry no payment method at all, so null means cash here — it is not an
     * unknown. A receipt reading "Paid by unknown" would be worse than useless in a dispute.
     */
    @Test
    fun `a null payment method reads as cash`() {
        val text = buildReceiptText(transaction.copy(paymentMethod = null), config, lagos)

        assertTrue(text.contains("Paid by cash"))
    }

    /**
     * Guards the `when` against a payment method added later and never given wording. USSD is
     * excluded from the underscore check only because its wording *is* the acronym.
     */
    @Test
    fun `every payment method has customer-facing wording`() {
        PaymentMethod.entries.forEach { method ->
            val text = buildReceiptText(transaction.copy(paymentMethod = method), config, lagos)
            val line = text.lines().single { it.startsWith("Paid by ") }
            assertTrue("no wording for $method", line.length > "Paid by ".length)
            assertTrue("raw enum name leaked for $method", !line.contains("_"))
        }
    }

    // ---- Degraded inputs -------------------------------------------------------------------------

    /**
     * The config read can come back empty on a factory-fresh unit or a failed read. The numbers are
     * the part that matters, so the receipt still goes out — just without the heading.
     */
    @Test
    fun `a missing config still produces a usable receipt`() {
        val text = buildReceiptText(transaction, null, lagos)

        assertTrue(text.startsWith("SmartPump"))
        assertTrue(text.contains("Receipt BLC-847"))
        assertTrue(text.contains("Total: ₦5,005.37"))
        assertTrue(!text.contains("Fuel:"))
    }

    @Test
    fun `an unset fuel type omits the line rather than guessing`() {
        val text = buildReceiptText(transaction, config.copy(fuelType = null), lagos)

        assertTrue(!text.contains("Fuel:"))
        assertTrue(text.contains("Litres: 5.75 L"))
    }

    @Test
    fun `a zero-litre record still renders`() {
        val text = buildReceiptText(
            transaction.copy(litresDispensed = 0.0, amountKobo = 0),
            config,
            lagos,
        )

        assertTrue(text.contains("Litres: 0.00 L"))
        assertTrue(text.contains("Total: ₦0.00"))
    }
}
