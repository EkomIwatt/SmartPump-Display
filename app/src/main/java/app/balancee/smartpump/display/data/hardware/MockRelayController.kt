// Debug-build relay. Holds an in-memory dispensing flag that MockPulseSource observes
// to decide whether to emit pulses. No GPIO touched — real impl lands in a later phase.
package app.balancee.smartpump.display.data.hardware

import android.util.Log
import app.balancee.smartpump.display.domain.hardware.RelayController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockRelayController @Inject constructor() : RelayController {

    private val _isDispensing = MutableStateFlow(false)
    override val isDispensing: StateFlow<Boolean> = _isDispensing.asStateFlow()

    override suspend fun startFuelFlow() {
        if (_isDispensing.compareAndSet(expect = false, update = true)) {
            Log.i(TAG, "RELAY ENERGISED — fuel flowing")
        }
    }

    override suspend fun stopFuelFlow() {
        if (_isDispensing.compareAndSet(expect = true, update = false)) {
            Log.i(TAG, "RELAY DE-ENERGISED — fuel stopped")
        }
    }

    private companion object {
        const val TAG = "MockRelay"
    }
}
