// Single rendering point for money. Amounts are carried through the state machine as
// kobo (Long) so a sub-naira fuel price (e.g. 87_050 = ₦870.50/L) is never truncated;
// this formats that kobo value for display as "₦X,XXX.XX".
package app.balancee.smartpump.display.ui.util

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Renders [kobo] as "₦X,XXX.XX" — UK comma grouping on the naira part, always 2 dp. */
fun formatNaira(kobo: Long): String {
    val sign = if (kobo < 0) "-" else ""
    val absKobo = abs(kobo)
    val naira = absKobo / 100
    val frac = absKobo % 100
    val grouped = NumberFormat.getInstance(Locale.UK).format(naira)
    return "$sign₦$grouped.${frac.toString().padStart(2, '0')}"
}
