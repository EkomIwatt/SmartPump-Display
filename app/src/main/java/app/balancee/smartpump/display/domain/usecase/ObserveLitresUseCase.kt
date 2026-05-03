// Converts raw pulse events from PulseSource into a live litres-dispensed Flow.
// PulseSource is injected in Phase 3 when the hardware layer is wired up.
package app.balancee.smartpump.display.domain.usecase

import javax.inject.Inject

class ObserveLitresUseCase @Inject constructor() {
    // TODO Phase 3: inject PulseSource + DeviceConfigRepository
    // Emit: pulseCount * litresPerPulse (from DeviceConfig), starting from restorePulseCount()
    // litresPerPulse is hardware-specific — the Arduino spec gives the conversion factor
}
