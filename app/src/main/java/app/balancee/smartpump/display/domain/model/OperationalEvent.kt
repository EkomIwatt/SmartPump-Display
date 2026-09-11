// One entry in the operational event log — something that happened to the pump that is not a sale.
// See EventEntity for why the raw pulse count is stored beside the K-factor rather than as litres.
package app.balancee.smartpump.display.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class OperationalEvent(
    val id: Long,
    val type: EventType,
    val createdAtMs: Long,
    val transactionRef: String?,
    val pulses: Int?,
    /** The K-factor in force when this was written, so [pulses] stays interpretable. */
    val pulsesPerLitre: Double?,
    val detail: String?,
) {
    /**
     * Litres this event concerns, converted with the K-factor THAT WAS IN FORCE AT THE TIME, not
     * today's. An entry written before calibration must keep reading as the figure the operator was
     * shown, otherwise a historic record silently changes value when the constant is corrected.
     *
     * Null when the size was never knowable — a restarted adapter or a silent one. Null is not zero:
     * "fuel may have been lost and we cannot say how much" is a worse finding than a number.
     */
    val litres: Double?
        get() {
            val k = pulsesPerLitre ?: return null
            if (k <= 0.0) return null
            return pulses?.let { it / k }
        }
}
