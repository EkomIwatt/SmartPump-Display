// Current fuel price pushed from the Balanceè operator app. Stored locally for offline use.
package app.balancee.smartpump.display.domain.model

import androidx.compose.runtime.Immutable

/**
 * @param koboPerLitre Price in kobo (100 kobo = ₦1). e.g. ₦870/L = 87_000 kobo/L.
 * @param updatedAt    Epoch millis when the price was last pushed from the backend.
 */
@Immutable
data class FuelPrice(
    val koboPerLitre: Long,
    val updatedAt: Long,
) {
    /** Convenience accessor returning Naira per litre as a Double for display. */
    val nairaPerLitre: Double get() = koboPerLitre / 100.0

    /**
     * Calculate litre cutoff for a fixed cash amount.
     * Floors to 2 decimal places to never dispense more than paid.
     */
    fun litresCutoff(amountKobo: Long): Double =
        Math.floor((amountKobo.toDouble() / koboPerLitre) * 100) / 100
}
