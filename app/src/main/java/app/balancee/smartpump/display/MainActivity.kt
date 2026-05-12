// Single-activity host. Bottom layer is the customer state host driven by [CustomerViewModel].
// The attendant overlay + debug screen are wired back in during Phase 4.
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
    CustomerStateHost(
        uiState = uiState,
        onStartTransaction = customerVm::onStartTransaction,
        onSelectPrePay = customerVm::onSelectPrePay,
        onSelectFillUp = customerVm::onSelectFillUp,
        onPrepayAmountChosen = customerVm::onPrepayAmountChosen,
        onPrepayMethodChosen = customerVm::onPrepayMethodChosen,
        onAttendantCashFixed = customerVm::onAttendantCashFixed,
        onCashFixedAuthorise = customerVm::onCashFixedAuthorise,
        onAttendantFillUp = customerVm::onAttendantFillUpAuthorise,
        onFillupPayCash = customerVm::onFillupPayCash,
        onFillupPayDigital = customerVm::onFillupPayDigital,
        onAttendantCashReceived = customerVm::onAttendantCashReceived,
        onShareReceipt = customerVm::onShareReceipt,
        onDismissComplete = customerVm::onDismissComplete,
        onCancel = customerVm::onCancel,
        modifier = Modifier.fillMaxSize(),
    )
}
