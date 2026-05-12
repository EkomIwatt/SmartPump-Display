// Controls the dispense relay (GPIO line on the Arduino that energises the pump motor solenoid).
//
// Verb mapping vs docs/state-machine.md:
//   spec "RELAY OPEN"   (circuit open, no fuel)  →  code  !isDispensing
//   spec "RELAY CLOSED" (circuit closed, fuel flows) → code  isDispensing
// The methods are named after the fuel-flow effect rather than the electrical state so callers
// don't have to remember which side of the relay verb means what. Always stop the fuel flow on
// transaction end, error, or app teardown.
package app.balancee.smartpump.display.domain.hardware

import kotlinx.coroutines.flow.StateFlow

interface RelayController {

    /** Live state of the relay. True == fuel is flowing. Drives the mock pulse generator. */
    val isDispensing: StateFlow<Boolean>

    /** Energise the relay so fuel flows. Idempotent. */
    suspend fun startFuelFlow()

    /** De-energise the relay so fuel stops. MUST be called on every terminal state. Idempotent. */
    suspend fun stopFuelFlow()
}
