// Backs the debug screen. Injects the *concrete* mock types so it can poke at controls
// that aren't on the abstract Hardware/Payment interfaces (pulsesPerSecond, autoApprove,
// inject failure events). Hilt returns the same @Singleton instance regardless of whether
// the call site asks for the interface or the impl, so this is safe alongside production
// code that depends on PulseSource / PaymentProcessor.
package app.balancee.smartpump.display.ui.screens.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.balancee.smartpump.display.data.hardware.MockPulseSource
import app.balancee.smartpump.display.data.hardware.MockRelayController
import app.balancee.smartpump.display.data.payment.MockPaymentProcessor
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.repository.DeviceConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val mockPulseSource: MockPulseSource,
    private val mockPaymentProcessor: MockPaymentProcessor,
    mockRelay: MockRelayController,
    private val deviceConfigRepository: DeviceConfigRepository,
) : ViewModel() {

    val pulsesPerSecond: StateFlow<Int> = mockPulseSource.pulsesPerSecond
    val autoApprove: StateFlow<Boolean> = mockPaymentProcessor.autoApprove
    val pendingDelayMs: StateFlow<Long> = mockPaymentProcessor.pendingDelayMs
    val relayOpen: StateFlow<Boolean> = mockRelay.isOpen

    val config: StateFlow<DeviceConfig?> = deviceConfigRepository.observeConfig()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setPulsesPerSecond(v: Int) = mockPulseSource.setPulsesPerSecond(v)
    fun injectDisconnect() = mockPulseSource.injectDisconnect()
    fun injectParseError() = mockPulseSource.injectParseError()

    fun setAutoApprove(v: Boolean) = mockPaymentProcessor.setAutoApprove(v)
    fun setPendingDelayMs(v: Long) = mockPaymentProcessor.setPendingDelayMs(v)

    fun saveConfig(pumpId: String, stationName: String, koboPerLitre: Long, virtualAccountNumber: String?) {
        viewModelScope.launch {
            deviceConfigRepository.saveConfig(
                DeviceConfig(
                    pumpId = pumpId,
                    stationName = stationName,
                    koboPerLitre = koboPerLitre,
                    virtualAccountNumber = virtualAccountNumber?.takeIf { it.isNotBlank() },
                ),
            )
        }
    }
}
