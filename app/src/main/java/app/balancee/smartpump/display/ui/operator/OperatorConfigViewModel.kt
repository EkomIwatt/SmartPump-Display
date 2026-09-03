// Backing VM for the operator config screen (Phase 7b).
//
// This is the device-local half of what GET /api/pump/config will eventually push. It exists
// because the Pump API has no endpoint that tells a pump which fuel it dispenses, and /authorise
// requires one (API_CONFORMANCE_AUDIT.md §6 #4). When that endpoint ships this screen stays: it
// becomes the manual override and the backend-unreachable fallback, so nothing here is throwaway.
package app.balancee.smartpump.display.ui.operator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.FuelType
import app.balancee.smartpump.display.domain.repository.DeviceConfigRepository
import app.balancee.smartpump.display.domain.usecase.CanStartTransactionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OperatorConfigUiState(
    val loaded: Boolean = false,
    /** Null until an operator picks one — the pump cannot sell in this state. */
    val fuelType: FuelType? = null,
    /** As typed, in naira. Empty on a pump that has never been configured. */
    val nairaPerLitre: String = "",
    val stationName: String = "",
    val pumpLabel: String = "",
    /** Which fields the transaction guard currently rejects, for inline highlighting. */
    val missing: Set<CanStartTransactionUseCase.Missing> = emptySet(),
    val saveError: String? = null,
    val savedAtMillis: Long? = null,
) {
    /** Parsed price in kobo, or null when what's typed isn't a usable price. */
    val koboPerLitre: Long?
        get() = nairaPerLitre.trim().toDoubleOrNull()
            ?.takeIf { it > 0 }
            ?.let { Math.round(it * 100) }

    val canSave: Boolean get() = fuelType != null && koboPerLitre != null
}

@HiltViewModel
class OperatorConfigViewModel @Inject constructor(
    private val configRepo: DeviceConfigRepository,
    private val canStartTransaction: CanStartTransactionUseCase,
) : ViewModel() {

    private val _ui = MutableStateFlow(OperatorConfigUiState())
    val ui: StateFlow<OperatorConfigUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val config = configRepo.getConfig()
            _ui.update {
                it.copy(
                    loaded = true,
                    fuelType = config?.fuelType,
                    // Deliberately blank rather than a placeholder price when unconfigured: a
                    // pre-filled figure invites an operator to accept it without reading it.
                    nairaPerLitre = config?.let { c -> "%.2f".format(c.koboPerLitre / 100.0) } ?: "",
                    stationName = config?.stationName.orEmpty(),
                    pumpLabel = config?.pumpLabel.orEmpty(),
                )
            }
            refreshMissing()
        }
    }

    fun onFuelTypeSelected(value: FuelType) =
        _ui.update { it.copy(fuelType = value, saveError = null, savedAtMillis = null) }

    fun onPriceChanged(value: String) =
        _ui.update { it.copy(nairaPerLitre = value, saveError = null, savedAtMillis = null) }

    fun onStationNameChanged(value: String) =
        _ui.update { it.copy(stationName = value, savedAtMillis = null) }

    fun onPumpLabelChanged(value: String) =
        _ui.update { it.copy(pumpLabel = value, savedAtMillis = null) }

    fun onSave() {
        val snapshot = _ui.value
        val fuelType = snapshot.fuelType
        val kobo = snapshot.koboPerLitre
        if (fuelType == null || kobo == null) {
            // The button is disabled in this state; this is the belt-and-braces path.
            _ui.update { it.copy(saveError = "Choose a fuel type and enter a price above ₦0.") }
            return
        }

        viewModelScope.launch {
            try {
                val existing = configRepo.getConfig()
                configRepo.saveConfig(
                    DeviceConfig(
                        // Blank falls back to the prior value, then to the model default, so an
                        // operator editing only the price can't wipe the station name by accident.
                        pumpLabel = snapshot.pumpLabel.trim().ifBlank {
                            existing?.pumpLabel ?: "PUMP 1"
                        },
                        stationName = snapshot.stationName.trim().ifBlank {
                            existing?.stationName ?: "SmartPump Station"
                        },
                        koboPerLitre = kobo,
                        fuelType = fuelType,
                        virtualAccountNumber = existing?.virtualAccountNumber,
                    ),
                )
                _ui.update {
                    it.copy(saveError = null, savedAtMillis = System.currentTimeMillis())
                }
                refreshMissing()
            } catch (t: Throwable) {
                _ui.update { it.copy(saveError = "Could not save: ${t.message}") }
            }
        }
    }

    /** Re-asks the real guard rather than re-deriving its rules here, so the two cannot drift. */
    private suspend fun refreshMissing() {
        val missing = when (val result = canStartTransaction()) {
            is CanStartTransactionUseCase.Result.Allowed -> emptySet()
            is CanStartTransactionUseCase.Result.NotConfigured -> result.missing
        }
        _ui.update { it.copy(missing = missing) }
    }
}
