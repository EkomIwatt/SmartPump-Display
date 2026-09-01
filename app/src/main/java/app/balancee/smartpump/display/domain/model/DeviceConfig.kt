// Device configuration pushed from the Balanceè operator app and cached locally.
// A null config (or null koboPerLitre) must block all transactions.
package app.balancee.smartpump.display.domain.model

import androidx.compose.runtime.Immutable

/**
 * @param pumpLabel            Display name for the nozzle, e.g. "PUMP 1". Screen caption only —
 *                             NOT the API's `pumpId`, which is the backend UUID held on
 *                             PumpCredentials and required in /authorise bodies. Renamed from
 *                             `pumpId` to kill that collision (TODO #13).
 * @param stationName          Station name shown on receipts, e.g. "Total Lekki Ph2".
 * @param koboPerLitre         Current fuel price in kobo (100 kobo = ₦1). e.g. 87_000 = ₦870/L.
 * @param virtualAccountNumber NIP bank-transfer account for post-fill-up QR generation. Null = not yet set.
 * @param updatedAt            Epoch millis when the operator last pushed this config.
 */
@Immutable
data class DeviceConfig(
    val pumpLabel: String = "PUMP 1",
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

    /**
     * Total cost in kobo for [litres] dispensed, rounded to the nearest kobo. Kept in kobo
     * (not naira) so a sub-naira price — e.g. 87_050 = ₦870.50/L — bills exactly:
     * 38.1 L → 3_316_605 kobo (₦33,166.05) rather than a truncated ₦33,166.
     */
    fun costKobo(litres: Double): Long = Math.round(litres * koboPerLitre)
}
