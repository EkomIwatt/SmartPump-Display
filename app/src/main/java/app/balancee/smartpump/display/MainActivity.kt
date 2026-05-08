// Single-activity host. Renders CustomerStateHost as the bottom layer with the attendant
// overlay floating at the bottom edge. The DEBUG action on the overlay swaps the customer
// view for the debug controls until CLOSE is tapped.
package app.balancee.smartpump.display

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import app.balancee.smartpump.display.ui.screens.attendant.AttendantOverlay
import app.balancee.smartpump.display.ui.screens.customer.CustomerStateHost
import app.balancee.smartpump.display.ui.screens.customer.CustomerViewModel
import app.balancee.smartpump.display.ui.screens.debug.DebugScreen
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmartPumpDisplayTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SmartPumpRoot(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun SmartPumpRoot(
    modifier: Modifier = Modifier,
    customerVm: CustomerViewModel = hiltViewModel(),
) {
    val ui by customerVm.ui.collectAsState()
    var showDebug by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        if (showDebug) {
            DebugScreen(
                currentState = ui.state,
                onClose = { showDebug = false },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CustomerStateHost(
                ui = ui,
                callbacks = customerVm.callbacks,
                modifier = Modifier.fillMaxSize(),
            )
            AttendantOverlay(
                state = ui.state,
                onAuthoriseFillUp = customerVm::onAttendantAuthoriseFillUp,
                onConfirmCash = customerVm::onAttendantConfirmCash,
                onCancelTransaction = customerVm::onAttendantCancel,
                onOpenDebug = { showDebug = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
