// Debug-build relay. Holds an in-memory open/closed flag that MockPulseSource observes
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

    private val _isOpen = MutableStateFlow(false)
    override val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    override suspend fun open() {
        if (_isOpen.compareAndSet(expect = false, update = true)) {
            Log.i(TAG, "RELAY OPEN — fuel flowing")
        }
    }

    override suspend fun close() {
        if (_isOpen.compareAndSet(expect = true, update = false)) {
            Log.i(TAG, "RELAY CLOSE — fuel stopped")
        }
    }

    private companion object {
        const val TAG = "MockRelay"
    }
}
