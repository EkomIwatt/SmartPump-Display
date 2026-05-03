// Validates preconditions and initiates a new transaction.
// Guards: price must be set, no other transaction in flight.
package app.balancee.smartpump.display.domain.usecase

import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.repository.DeviceConfigRepository
import app.balancee.smartpump.display.domain.repository.PulseRepository
import javax.inject.Inject

class StartTransactionUseCase @Inject constructor(
    private val deviceConfigRepository: DeviceConfigRepository,
    private val pulseRepository: PulseRepository,
) {
    /**
     * Returns null if preconditions pass and the transaction can start.
     * Returns an error message string if something blocks the start.
     * TODO Phase 3: wire to CustomerViewModel state transitions
     */
    suspend fun validate(mode: TransactionMode): String? {
        val config = deviceConfigRepository.getConfig()
        if (config == null) return "Price not set — contact operator"
        if (config.koboPerLitre <= 0) return "Price not set — contact operator"
        return null
    }
}
