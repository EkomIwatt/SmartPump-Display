// Controls the dispense relay (GPIO line on the Arduino that energises the pump motor solenoid).
// Open == fuel can flow. Always close on transaction end, error, or app teardown.
package app.balancee.smartpump.display.domain.hardware

import kotlinx.coroutines.flow.StateFlow

interface RelayController {

    /** Live state of the relay. True == open (fuel flows). Drives the mock pulse generator. */
    val isOpen: StateFlow<Boolean>

    /** Close the circuit so fuel flows. Idempotent. */
    suspend fun open()

    /** Open the circuit so fuel stops. MUST be called on every terminal state. Idempotent. */
    suspend fun close()
}
