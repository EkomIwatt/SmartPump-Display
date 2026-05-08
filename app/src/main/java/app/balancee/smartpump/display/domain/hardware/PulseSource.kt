// Hardware abstraction over the Arduino-via-USB pulse stream.
// Real impl (Phase 6+) parses raw "PULSE:XXXX\n" frames; mock impl emits synthetic events.
package app.balancee.smartpump.display.domain.hardware

import app.balancee.smartpump.display.domain.model.PulseMessage
import kotlinx.coroutines.flow.Flow

interface PulseSource {

    /**
     * Cold flow of [PulseMessage] events from the pulse adapter.
     * Collection should run for the lifetime of the app — the state machine
     * filters Pulse vs Heartbeat vs Disconnected based on current TransactionState.
     */
    fun observe(): Flow<PulseMessage>
}
