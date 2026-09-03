// Guard called before any new transaction can begin (PRE-PAY, FILL UP, CASH FIXED).
// Returns the active DeviceConfig only when the pump is fully configured; otherwise NotConfigured,
// which the VM surfaces as the customer-facing "fuel parameters not set" error and blocks the flow.
// See docs/flows.md "Price guard" and state-machine invariant #4.
package app.balancee.smartpump.display.domain.usecase

import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.repository.DeviceConfigRepository
import javax.inject.Inject

class CanStartTransactionUseCase @Inject constructor(
    private val deviceConfig: DeviceConfigRepository,
) {

    suspend operator fun invoke(): Result {
        val config = deviceConfig.getConfig()
            ?: return Result.NotConfigured(setOf(Missing.PRICE, Missing.FUEL_TYPE))

        val missing = buildSet {
            if (config.koboPerLitre <= 0) add(Missing.PRICE)
            // /authorise requires a fuel type; dispensing without one would bill against a fuel the
            // backend never agreed to. Blocks exactly as an unset price does.
            if (config.fuelType == null) add(Missing.FUEL_TYPE)
        }

        return if (missing.isEmpty()) Result.Allowed(config) else Result.NotConfigured(missing)
    }

    /** Which part of the pump's configuration is absent. */
    enum class Missing { PRICE, FUEL_TYPE }

    sealed class Result {
        data class Allowed(val config: DeviceConfig) : Result()

        /**
         * The pump cannot sell until an operator finishes configuring it.
         *
         * [missing] is kept for the operator config screen, which highlights the offending fields.
         * The **customer** sees one message regardless of which field it is
         * ([CUSTOMER_MESSAGE]) — the distinction is not something they can act on, and the fix path
         * is identical: fetch the attendant.
         */
        data class NotConfigured(val missing: Set<Missing>) : Result()
    }

    companion object {
        /** Shown on the customer screen for every NotConfigured case. */
        const val CUSTOMER_MESSAGE = "Fuel parameters not set — please see attendant."
    }
}
