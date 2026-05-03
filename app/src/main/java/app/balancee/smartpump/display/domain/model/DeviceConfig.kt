// Device configuration pushed from the Balanceè operator app and cached locally.
// A null config (or null koboPerLitre) must block all transactions.
package app.balancee.smartpump.display.domain.model

import androidx.compose.runtime.Immutable

/**
 * @param pumpId               Display name for the nozzle, e.g. "PUMP 1".
 * @param stationName          Station name shown on receipts, e.g. "Total Lekki Ph2".
 * @param koboPerLitre         Current fuel price in kobo (100 kobo = ₦1). e.g. 87_000 = ₦870/L.
 * @param virtualAccountNumber NIP bank-transfer account for post-fill-up QR generation. Null = not yet set.
 * @param updatedAt            Epoch millis when the operator last pushed this config.
 */
@Immutable
data class DeviceConfig(
    val pumpId: String = "PUMP 1",
    val stationName: String = "SmartPump Station",
    val koboPerLitre: Long,
    val virtualAccountNumber: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val nairaPerLitre: Double get() = koboPerLitre / 100.0

    /**
     * Litre cutoff for a fixed cash/pre-pay amount.
     * Floors to 2 decimal places — never dispense more than was paid.
     */
    fun litresCutoff(amountKobo: Long): Double =
        Math.floor((amountKobo.toDouble() / koboPerLitre) * 100.0) / 100.0
}
