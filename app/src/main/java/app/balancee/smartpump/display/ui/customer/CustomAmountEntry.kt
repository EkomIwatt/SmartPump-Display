// Phase 10g. The pre-pay keypad's entry model, lifted out of ModeSelectScreen so it can be
// tested without Compose.
//
// It exists because of a defect the 10g gate found on 2026-09-19, live against production: a
// customer typed ₦200 and the app authorised ₦2,007.03. Every keystroke committed its value,
// but a *deletion* did not — so typing "2008", noticing the stray 8 and backspacing to "200"
// left ₦2,008 committed while the screen read ₦200. The gate that was supposed to catch this
// keyed on whether the typed text was *valid*, and "200" is perfectly valid (CUSTOM_MIN_NAIRA
// is 200), so it never fired. Shipped in every build since 33e9564 (2026-05-26).
//
// The invariant this type exists to hold, and the one the tests pin:
//
//     what is committed is what is on the screen, or nothing is committed.
//
// Every edit — including deletion — goes through `retype`, which re-derives the commitment from
// scratch. There is deliberately no path that leaves `committedNaira` holding a value the typed
// text no longer implies.

package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.ui.util.appendDecimal
import app.balancee.smartpump.display.ui.util.appendDigit
import kotlin.math.roundToInt

/**
 * What the customer has typed into the custom-amount keypad, and what it commits.
 *
 * Immutable on purpose: each edit returns a new instance, which is both what Compose needs to
 * recompose and what lets a test assert a whole keystroke sequence without a UI.
 *
 * @param mode           whether [typed] is naira or litres — both commit naira.
 * @param typed          the digits as typed, a string so it can hold a trailing decimal point.
 * @param committedNaira the naira value [typed] implies, or **null** when it implies none.
 */
internal data class CustomAmountEntry(
    val mode: AmountEntryMode = AmountEntryMode.AMOUNT,
    val typed: String = "",
    val committedNaira: Int? = null,
) {
    private val typedValue: Double? get() = typed.toDoubleOrNull()

    /** Whether [typed] is within the bounds for [mode]. Independent of price. */
    val valid: Boolean
        get() {
            val v = typedValue ?: return false
            return when (mode) {
                AmountEntryMode.AMOUNT -> v >= CUSTOM_MIN_NAIRA && v <= CUSTOM_MAX_NAIRA
                AmountEntryMode.LITRES -> v >= CUSTOM_MIN_LITRES && v <= CUSTOM_MAX_LITRES
            }
        }

    fun digit(digit: Int, priceKoboPerLitre: Long): CustomAmountEntry =
        retype(appendDigit(typed, digit), priceKoboPerLitre)

    fun decimal(priceKoboPerLitre: Long): CustomAmountEntry =
        retype(appendDecimal(typed), priceKoboPerLitre)

    /**
     * Delete the last character.
     *
     * **This re-commits**, which is the whole fix. The version that did not is what charged a
     * customer ₦2,007.03 for a ₦200 sale.
     */
    fun backspace(priceKoboPerLitre: Long): CustomAmountEntry =
        if (typed.isEmpty()) this else retype(typed.dropLast(1), priceKoboPerLitre)

    /** Start over — opening the keypad, switching ₦/L, or tapping a preset. */
    fun cleared(mode: AmountEntryMode = this.mode): CustomAmountEntry = CustomAmountEntry(mode = mode)

    /**
     * Re-derive the commitment from [next]. The single door every edit passes through.
     *
     * When [next] cannot be committed the commitment is **cleared**, never left holding what the
     * previous keystroke implied. A caller may then show nothing, but it can never show one
     * number and charge another.
     */
    private fun retype(next: String, priceKoboPerLitre: Long): CustomAmountEntry =
        copy(typed = next).let { it.copy(committedNaira = it.commitValue(priceKoboPerLitre)) }

    private fun commitValue(priceKoboPerLitre: Long): Int? {
        val v = typedValue?.takeIf { valid } ?: return null
        return when (mode) {
            AmountEntryMode.AMOUNT -> v.roundToInt()
            AmountEntryMode.LITRES -> (v * priceKoboPerLitre / 100.0).roundToInt()
        }
    }
}
