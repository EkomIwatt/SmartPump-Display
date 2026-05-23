// Top-level gate that decides whether MainActivity shows the onboarding flow or the
// normal customer/attendant host. Wraps StationIdentityRepository.observeIdentity().
//
// In debug builds, also performs the first-boot auto-provisioning so developers can demo
// without walking the install flow each launch. Demo identity: stationId "DEMO-001",
// displayName "Demo Station", no logo, PIN "0000". Release builds skip this entirely and
// force a manual onboarding.
package app.balancee.smartpump.display.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.balancee.smartpump.display.BuildConfig
import app.balancee.smartpump.display.domain.model.StationIdentity
import app.balancee.smartpump.display.domain.repository.StationIdentityRepository
import app.balancee.smartpump.display.domain.security.SecurityPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface GateState {
    data object Loading : GateState
    data object NotProvisioned : GateState
    data class Provisioned(val identity: StationIdentity) : GateState
}

@HiltViewModel
class IdentityGateViewModel @Inject constructor(
    private val repo: StationIdentityRepository,
    private val security: SecurityPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow<GateState>(GateState.Loading)
    val state: StateFlow<GateState> = _state.asStateFlow()

    /** Live PIN-bypass flag — true in debug builds by default, always false in release. */
    val pinBypassEnabled: StateFlow<Boolean> = security.pinBypassEnabled

    /** Suspending PIN verify — handed to AttendantOverlayHost. */
    suspend fun verifyPin(rawPin: String): Boolean = repo.verifyPin(rawPin)

    init {
        viewModelScope.launch {
            // Debug builds: auto-seed the demo identity on first boot so the customer flow
            // is reachable without typing in onboarding. Production builds force the real
            // install flow.
            if (BuildConfig.DEBUG && !repo.isProvisioned()) {
                repo.provision(
                    stationId = "DEMO-001",
                    displayName = "Demo Station",
                    logoBytes = null,
                    rawPin = "0000",
                )
            }
            repo.observeIdentity().collect { identity ->
                _state.value = if (identity == null) {
                    GateState.NotProvisioned
                } else {
                    GateState.Provisioned(identity)
                }
            }
        }
    }
}
