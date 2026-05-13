// Single-activity host. The customer state host renders the screen for the current
// TransactionState. The attendant overlay (Phase 4a) wraps it from outside: a bottom-edge
// swipe-up affordance exposes the three attendant actions (FILL UP AUTHORISE, AUTHORISE
// CASH ₦…, CASH RECEIVED) — each state-gated against the underlying TransactionState.
// The debug screen entry point lands in Phase 4b.
package app.balancee.smartpump.display

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import app.balancee.smartpump.display.ui.attendant.AttendantOverlayHost
import app.balancee.smartpump.display.ui.customer.CustomerStateHost
import app.balancee.smartpump.display.ui.customer.CustomerViewModel
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmartPumpDisplayTheme {
                SmartPumpRoot()
            }
        }
    }
}

@Composable
private fun SmartPumpRoot(
    customerVm: CustomerViewModel = hiltViewModel(),
) {
    val uiState by customerVm.ui.collectAsState()
    AttendantOverlayHost(
        state = uiState.state,
        onAttendantFillUp = customerVm::onAttendantFillUpAuthorise,
        onAttendantCashFixed = customerVm::onAttendantCashFixed,
        onAttendantCashReceived = customerVm::onAttendantCashReceived,
        modifier = Modifier.fillMaxSize(),
    ) {
        CustomerStateHost(
            uiState = uiState,
            onStartTransaction = customerVm::onStartTransaction,
            onSelectPrePay = customerVm::onSelectPrePay,
            onSelectFillUp = customerVm::onSelectFillUp,
            onPrepayAmountChosen = customerVm::onPrepayAmountChosen,
            onPrepayMethodChosen = customerVm::onPrepayMethodChosen,
            onCashFixedAuthorise = customerVm::onCashFixedAuthorise,
            onFillupPayCash = customerVm::onFillupPayCash,
            onFillupPayDigital = customerVm::onFillupPayDigital,
            onShareReceipt = customerVm::onShareReceipt,
            onDismissComplete = customerVm::onDismissComplete,
            onCancel = customerVm::onCancel,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
