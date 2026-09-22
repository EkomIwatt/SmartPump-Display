// Controls the dispense relay (GPIO line on the Arduino that energises the pump motor solenoid).
//
// Verb mapping vs docs/state-machine.md:
//   spec "RELAY OPEN"   (circuit open, no fuel)  →  code  !isDispensing
//   spec "RELAY CLOSED" (circuit closed, fuel flows) → code  isDispensing
// The methods are named after the fuel-flow effect rather than the electrical state so callers
// don't have to remember which side of the relay verb means what. Always stop the fuel flow on
// transaction end, error, or app teardown.
//
// Phase 11: fuel is only ever opened under a pulse limit the adapter enforces itself, inside a
// tagged session (docs/serial-protocol.md). See AdapterSession.
package app.balancee.smartpump.display.domain.hardware

import kotlinx.coroutines.flow.StateFlow

interface RelayController {

    /** True while this app has fuel authorised for a sale. Drives the mock pulse generator. */
    val isDispensing: StateFlow<Boolean>

    /**
     * The session the adapter last acknowledged for this app's sale — its tag and the lifetime
     * count it started at. The pulse source counts the sale as `count − start` from it. Null until
     * the first acknowledgement.
     */
    val session: StateFlow<AdapterSession?>

    /**
     * Arm sale [tag] with [limitPulses] and open the relay; waits for the adapter to acknowledge.
     * Safe to call again with the same tag: the adapter treats it as a resume and never grants a
     * second allowance. Anything but [SessionReply.Armed] means fuel is **not** flowing.
     */
    suspend fun startFuelFlow(limitPulses: Long, tag: Long): SessionReply

    /** Resume held sale [tag] under the limit the adapter already holds (`RES`). */
    suspend fun resumeFuelFlow(tag: Long): SessionReply

    /** Ask which session the adapter holds, changing nothing (`SES?`). */
    suspend fun querySession(): SessionReply

    /**
     * De-energise the relay so fuel stops. MUST be called on every terminal state. Idempotent. The
     * adapter HOLDS the session rather than ending it, which is what makes this safe to send on
     * every boot (spec §4).
     */
    suspend fun stopFuelFlow()
}
