// Backing VM for the Phase 4b debug screen. Surfaces the mock-hardware and mock-payment
// knobs, the operator-pushable DeviceConfig fields, and the Flow 5 "SMS arrived" injector.
//
// The mocks (MockPulseSource / MockPaymentProcessor) are @Singleton @Inject — Hilt hands
// back the same instances bound behind the PulseSource / PaymentProcessor interfaces in
// HardwareModule / PaymentModule, so changes from this VM affect the live customer VM.
package app.balancee.smartpump.display.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.balancee.smartpump.display.data.hardware.MockPulseSource
import app.balancee.smartpump.display.data.payment.MockPaymentProcessor
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.FuelType
import app.balancee.smartpump.display.domain.repository.DeviceConfigRepository
import app.balancee.smartpump.display.domain.repository.StationIdentityRepository
import app.balancee.smartpump.display.domain.security.SecurityPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DebugUiState(
    val pulsesPerSecond: Int = 50,
    val tankCapacityLitres: Double = 60.0,
    val autoApprove: Boolean = true,
    val pendingDelayMs: Long = 3_000L,
    val failureReason: String = "Mock: payment declined",
    val deviceConfig: DeviceConfig? = null,
    val saveStatus: String? = null,
    val pinBypassEnabled: Boolean = false,
    val isDebugBuild: Boolean = false,
    val stationDisplayName: String? = null,
    val stationId: String? = null,
    val resetStatus: String? = null,
)

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val pulse: MockPulseSource,
    private val payment: MockPaymentProcessor,
    private val configRepo: DeviceConfigRepository,
    private val identityRepo: StationIdentityRepository,
    private val security: SecurityPreferences,
) : ViewModel() {

    private val _ui = MutableStateFlow(DebugUiState())
    val ui: StateFlow<DebugUiState> = _ui.asStateFlow()

    init {
        // Mirror the mock + config state into a single UI snapshot.
        viewModelScope.launch {
            combine(
                pulse.pulsesPerSecond,
                pulse.tankCapacityLitres,
                payment.autoApprove,
                payment.pendingDelayMs,
                payment.failureReason,
            ) { pps, tank, approve, delay, reason ->
                Quint(pps, tank, approve, delay, reason)
            }.collect { (pps, tank, approve, delay, reason) ->
                _ui.update {
                    it.copy(
                        pulsesPerSecond = pps,
                        tankCapacityLitres = tank,
                        autoApprove = approve,
                        pendingDelayMs = delay,
                        failureReason = reason,
                    )
                }
            }
        }
        viewModelScope.launch {
            configRepo.observeConfig().collect { config ->
                _ui.update { it.copy(deviceConfig = config) }
            }
        }
        viewModelScope.launch {
            security.pinBypassEnabled.collect { enabled ->
                _ui.update {
                    it.copy(
                        pinBypassEnabled = enabled,
                        isDebugBuild = security.isDebugBuild,
                    )
                }
            }
        }
        viewModelScope.launch {
            identityRepo.observeIdentity().collect { identity ->
                _ui.update {
                    it.copy(
                        stationId = identity?.stationId,
                        stationDisplayName = identity?.displayName,
                    )
                }
            }
        }
    }

    // ---- Hardware knobs ----
    fun setPulsesPerSecond(value: Int) = pulse.setPulsesPerSecond(value)
    fun setTankCapacityLitres(value: Double) = pulse.setTankCapacityLitres(value)
    fun injectDisconnect() = pulse.injectDisconnect()
    fun injectParseError() = pulse.injectParseError()

    // ---- Payment knobs ----
    fun setAutoApprove(value: Boolean) = payment.setAutoApprove(value)
    fun setPendingDelayMs(value: Long) = payment.setPendingDelayMs(value)
    fun setFailureReason(value: String) = payment.setFailureReason(value)

    /**
     * Flow 5 "SMS arrived" injector — also useful for any pre-pay / fill-up digital flow
     * to short-circuit the configured pendingDelayMs. The terminal result is whatever
     * `autoApprove` is set to at receive time.
     */
    fun triggerInstantResolve() = payment.triggerInstantResolve()

    // ---- Security knobs (Phase 5c) ----
    fun setPinBypass(enabled: Boolean) = security.setPinBypassEnabled(enabled)

    /**
     * Wipe the station-identity row. The IdentityGateViewModel observes the same row and
     * flips back to NotProvisioned, so the next composition drops into OnboardingScreen.
     * In debug builds, IdentityGateViewModel will then re-seed the Demo identity on its
     * next init — so re-running this from the debug screen effectively cycles the gate
     * once. To exercise the real onboarding UI a tester should flip the bypass off and
     * toggle the build to release; OR call this from a release build.
     */
    fun resetOnboarding() {
        viewModelScope.launch {
            try {
                identityRepo.reset()
                _ui.update { it.copy(resetStatus = "Onboarding reset at ${java.time.LocalTime.now()}") }
            } catch (t: Throwable) {
                _ui.update { it.copy(resetStatus = "Reset failed: ${t.message}") }
            }
        }
    }

    // ---- DeviceConfig form ----
    fun saveDeviceConfig(
        pumpLabel: String,
        stationName: String,
        koboPerLitre: Long,
        fuelType: FuelType?,
        virtualAccountNumber: String?,
    ) {
        viewModelScope.launch {
            try {
                configRepo.saveConfig(
                    DeviceConfig(
                        pumpLabel = pumpLabel.ifBlank { "PUMP 1" },
                        stationName = stationName.ifBlank { "SmartPump Station" },
                        koboPerLitre = koboPerLitre.coerceAtLeast(1L),
                        fuelType = fuelType,
                        virtualAccountNumber = virtualAccountNumber?.ifBlank { null },
                    )
                )
                _ui.update { it.copy(saveStatus = "Saved at ${java.time.LocalTime.now()}") }
            } catch (t: Throwable) {
                _ui.update { it.copy(saveStatus = "Save failed: ${t.message}") }
            }
        }
    }
}

// 5-arg combine doesn't have a Quintuple in stdlib; use a small local holder.
private data class Quint<A, B, C, D, E>(
    val a: A, val b: B, val c: C, val d: D, val e: E,
)
