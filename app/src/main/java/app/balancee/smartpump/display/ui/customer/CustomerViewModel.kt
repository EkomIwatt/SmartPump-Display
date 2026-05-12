// Customer-side state holder. In Phase 3a only Idle ↔ ModeSelect transitions are wired;
// PRE-PAY / FILL UP selections move into placeholder states that subsequent 3b–3f phases
// will render screens for. Persistence (PulseRepository) plumbing arrives in Phase 5.
package app.balancee.smartpump.display.ui.customer

import androidx.lifecycle.ViewModel
import app.balancee.smartpump.display.domain.model.TransactionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class CustomerViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow<TransactionState>(TransactionState.Idle)
    val state: StateFlow<TransactionState> = _state.asStateFlow()

    fun onStartTransaction() {
        _state.update { current ->
            if (current is TransactionState.Idle) TransactionState.ModeSelect else current
        }
    }

    fun onSelectPrePay() {
        _state.update { current ->
            if (current is TransactionState.ModeSelect) TransactionState.PrepayAmountSelect else current
        }
    }

    fun onSelectFillUp() {
        _state.update { current ->
            if (current is TransactionState.ModeSelect) TransactionState.FillupAwaitingAttendantAuth else current
        }
    }

    fun onCancel() {
        _state.value = TransactionState.Idle
    }
}
