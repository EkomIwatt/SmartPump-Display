// Guard called before any new transaction can begin (PRE-PAY, FILL UP, CASH FIXED).
// Returns the active DeviceConfig if a price-per-litre has been pushed; otherwise PriceNotSet,
// which the VM surfaces as "Price not set — contact operator" and blocks the flow.
// See docs/flows.md "Price guard" and state-machine invariant #4.
package app.balancee.smartpump.display.domain.usecase

import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.repository.DeviceConfigRepository
import javax.inject.Inject

class CanStartTransactionUseCase @Inject constructor(
    private val deviceConfig: DeviceConfigRepository,
) {

    suspend operator fun invoke(): Result {
        val config = deviceConfig.getConfig() ?: return Result.PriceNotSet
        return if (config.koboPerLitre > 0) Result.Allowed(config) else Result.PriceNotSet
    }

    sealed class Result {
        data class Allowed(val config: DeviceConfig) : Result()
        data object PriceNotSet : Result()
    }
}
