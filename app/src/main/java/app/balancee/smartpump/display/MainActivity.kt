// Single-activity host. The customer state host renders the screen for the current
// TransactionState. The attendant overlay (Phase 4a) wraps it from outside: a bottom-edge
// swipe-up affordance exposes the three attendant actions (FILL UP AUTHORISE, AUTHORISE
// CASH ₦…, CASH RECEIVED) — each state-gated against the underlying TransactionState.
// The debug screen (Phase 4b) is reachable via a long-press on a hidden top-left hotspot.
package app.balancee.smartpump.display

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import app.balancee.smartpump.display.ui.attendant.AttendantOverlayHost
import app.balancee.smartpump.display.ui.customer.CustomerStateHost
import app.balancee.smartpump.display.ui.customer.CustomerViewModel
import app.balancee.smartpump.display.ui.debug.DebugScreen
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Kiosk mode — full immersive (hide status + nav bars), keep the screen on, and
        // swallow the hardware back so a customer can't accidentally exit the pump app.
        // Lock Task Mode (device-owner pinning) is the real anti-escape; that's a
        // deployment-time step. These three flags cover the typical kiosk session.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // No-op: kiosk app must not exit on back press. Cancellation lives on
                    // the in-screen "Cancel transaction" / attendant overlay actions.
                }
            },
        )
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
    var debugVisible by rememberSaveable { mutableStateOf(false) }

    if (debugVisible) {
        DebugScreen(
            onClose = { debugVisible = false },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
        // Engineering long-press hotspot — top-left 40dp square. Not visible to attendants
        // or customers; testers reach the debug screen from here. Sits on top of the
        // attendant overlay layer so it's reachable in any state.
        DebugLongPressHotspot(
            onOpenDebug = { debugVisible = true },
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

@Composable
private fun DebugLongPressHotspot(
    onOpenDebug: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onOpenLatest by rememberUpdatedState(onOpenDebug)
    Box(
        modifier = modifier
            .size(40.dp)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onOpenLatest() })
            },
    )
}
