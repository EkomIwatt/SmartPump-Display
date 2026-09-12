// Builds the plain-text receipt the customer shares from the completion screen.
//
// Plain text, deliberately. OQ #14 resolved receipt delivery as the Android system share sheet —
// the customer taps Share and the OS offers WhatsApp, SMS, email, whatever they have — so the
// receipt has to survive being pasted into any of them. A PDF or an image would render in some and
// be useless in others, and none of them are a channel this app controls.
//
// Pure function on purpose: no Android imports, no formatting done at the call site, so the exact
// characters a customer receives are unit-testable.
package app.balancee.smartpump.display.ui.util

import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.Transaction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * Month names are spelled out here rather than taken from the locale, which sounds like
 * over-engineering and is not: `MMM` under `Locale.UK` renders September as "Sept" on a modern JVM
 * and "Sep" on other CLDR/ICU versions, and the JVM's data is not Android's. A receipt is the one
 * artefact a customer keeps, so the same sale must not read differently depending on which Android
 * version the tablet shipped with — and a unit test asserting the text would otherwise be testing
 * the build machine's locale data instead of this code.
 */
private val MONTHS: Map<Long, String> = mapOf(
    1L to "Jan", 2L to "Feb", 3L to "Mar", 4L to "Apr", 5L to "May", 6L to "Jun",
    7L to "Jul", 8L to "Aug", 9L to "Sep", 10L to "Oct", 11L to "Nov", 12L to "Dec",
)

/** `12 Sep 2026, 14:32` — day-first, as Nigeria writes dates; 24-hour, so there is no am/pm doubt. */
private val TIMESTAMP: DateTimeFormatter = DateTimeFormatterBuilder()
    .appendValue(ChronoField.DAY_OF_MONTH)
    .appendLiteral(' ')
    .appendText(ChronoField.MONTH_OF_YEAR, MONTHS)
    .appendLiteral(' ')
    .appendValue(ChronoField.YEAR, 4)
    .appendLiteral(", ")
    .appendValue(ChronoField.HOUR_OF_DAY, 2)
    .appendLiteral(':')
    .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
    .toFormatter(Locale.UK)

/**
 * The receipt for [transaction], as shareable text.
 *
 * [config] supplies the station name and pump label. It is nullable because the config read can
 * come back empty (a factory-fresh unit, a failed read) and a receipt with a missing heading still
 * beats no receipt at all — the numbers are the part that matters.
 *
 * [zone] is injectable so the test suite is not at the mercy of the machine's timezone. The
 * transaction's own `createdAt` is used rather than the current time, so a receipt shared from a
 * screen restored after a power cut still states when the fuel was actually dispensed.
 */
fun buildReceiptText(
    transaction: Transaction,
    config: DeviceConfig?,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val when_ = Instant.ofEpochMilli(transaction.createdAt).atZone(zone).format(TIMESTAMP)

    return buildString {
        appendLine(config?.stationName ?: "SmartPump")
        config?.pumpLabel?.let { appendLine(it) }
        appendLine()
        appendLine("Receipt ${transaction.transactionRef}")
        appendLine(when_)
        appendLine()
        // displayName, not the enum name — COOKING_GAS must read "Cooking gas".
        config?.fuelType?.let { appendLine("Fuel: ${it.displayName}") }
        appendLine("Litres: ${formatLitres(transaction.litresDispensed)} L")
        appendLine("Price: ${formatNaira(transaction.priceKoboPerLitre)} per litre")
        appendLine("Total: ${formatNaira(transaction.amountKobo)}")
        appendLine()
        appendLine("Paid by ${transaction.paymentMethod.describe()}")
        append("Thank you for your patronage.")
    }
}

/** Two decimals, matching the litre count on the completion screen. */
private fun formatLitres(litres: Double): String = String.format(Locale.UK, "%.2f", litres)

/**
 * Cash flows carry no [PaymentMethod] at all — null means cash here, not "unknown", because the
 * only flows that leave it null are the two cash ones (see `Transaction.paymentMethod`).
 */
private fun PaymentMethod?.describe(): String = when (this) {
    null, PaymentMethod.CASH_SEE_ATTENDANT -> "cash"
    PaymentMethod.BALANCEE_APP -> "the Balanceè app"
    PaymentMethod.BANK_QR_TRANSFER -> "bank transfer"
    PaymentMethod.NFC_CARD -> "card"
    PaymentMethod.USSD -> "USSD"
}
