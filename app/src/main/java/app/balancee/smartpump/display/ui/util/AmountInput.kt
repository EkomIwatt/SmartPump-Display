// Shared decimal text-entry helpers for the on-screen numeric keypads (pre-pay custom
// amount + cash-fixed). The typed value is a String so it can hold an in-progress decimal
// point. Rules: at most 2 decimal places (kobo), a 6-digit integer cap, a single decimal
// point, and no trailing digits after a lone leading zero (type "." to go sub-naira).
package app.balancee.smartpump.display.ui.util

/** Append a digit, enforcing ≤2 decimals, a 6-digit integer cap, and the leading-zero rule. */
fun appendDigit(typed: String, digit: Int): String {
    if (typed == "0") return typed
    val candidate = typed + digit.toString()
    val dot = candidate.indexOf('.')
    if (dot >= 0 && candidate.length - dot - 1 > 2) return typed
    val intLen = if (dot >= 0) dot else candidate.length
    if (intLen > 6) return typed
    return candidate
}

/** Append a single decimal point (no-op if one is already present; seeds "0." from empty). */
fun appendDecimal(typed: String): String {
    if (typed.contains('.')) return typed
    return if (typed.isEmpty()) "0." else "$typed."
}
