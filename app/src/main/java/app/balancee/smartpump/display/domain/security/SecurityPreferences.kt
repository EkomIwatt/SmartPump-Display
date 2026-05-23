// Runtime security toggles. Singleton — same instance shared between debug screen, attendant
// overlay, and identity gate. Never persisted: the PIN-bypass flag must not survive a
// reinstall, and in release builds it cannot be enabled at all.
//
// The bypass is seeded ON in debug builds so a developer demoing the app does not have to
// type the PIN before every attendant action. In release builds it is hard-pinned OFF and
// any call to [setPinBypassEnabled] is silently ignored.
package app.balancee.smartpump.display.domain.security

import app.balancee.smartpump.display.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecurityPreferences @Inject constructor() {

    private val _pinBypassEnabled = MutableStateFlow(BuildConfig.DEBUG)
    val pinBypassEnabled: StateFlow<Boolean> = _pinBypassEnabled.asStateFlow()

    /** No-op in release builds; honoured in debug. */
    fun setPinBypassEnabled(enabled: Boolean) {
        if (BuildConfig.DEBUG) {
            _pinBypassEnabled.value = enabled
        }
    }

    /** True in debug builds, false in release. Used by the debug screen to show/hide knobs. */
    val isDebugBuild: Boolean = BuildConfig.DEBUG
}
