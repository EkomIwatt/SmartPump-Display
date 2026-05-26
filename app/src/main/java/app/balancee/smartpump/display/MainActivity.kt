// Single-activity host. The customer state host renders the screen for the current
// TransactionState. The attendant overlay (Phase 4a) wraps it from outside: a bottom-edge
// swipe-up affordance exposes the three attendant actions (FILL UP AUTHORISE, AUTHORISE
// CASH ₦…, CASH RECEIVED) — each state-gated against the underlying TransactionState.
// Each action goes through a 4-digit PIN modal (Phase 5c) before firing; debug builds can
// bypass the modal via the debug-screen toggle.
//
// Phase 5c also gates the whole activity on station provisioning. Until the operator
// finishes onboarding the device is locked into OnboardingScreen; the debug long-press
// hotspot is the only escape.
package app.balancee.smartpump.display

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import app.balancee.smartpump.display.ui.onboarding.GateState
import app.balancee.smartpump.display.ui.onboarding.IdentityGateViewModel
import app.balancee.smartpump.display.ui.onboarding.OnboardingScreen
import app.balancee.smartpump.display.ui.theme.Background
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
    gateVm: IdentityGateViewModel = hiltViewModel(),
) {
    val uiState by customerVm.ui.collectAsState()
    val gateState by gateVm.state.collectAsState()
    val pinBypass by gateVm.pinBypassEnabled.collectAsState()
    var debugVisible by rememberSaveable { mutableStateOf(false) }

    if (debugVisible) {
        DebugScreen(
            onClose = { debugVisible = false },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val gate = gateState) {
            GateState.Loading -> {
                // Brief — first-frame while we read the identity row. A blank background
                // is fine; the gate flips within a single coroutine tick on warm starts.
                Box(modifier = Modifier.fillMaxSize().background(Background))
            }

            GateState.NotProvisioned -> {
                OnboardingScreen(modifier = Modifier.fillMaxSize())
            }

            is GateState.Provisioned -> {
                AttendantOverlayHost(
                    state = uiState.state,
                    onAttendantFillUp = customerVm::onAttendantFillUpAuthorise,
                    onAttendantCashFixed = customerVm::onAttendantCashFixed,
                    onAttendantCashReceived = customerVm::onAttendantCashReceived,
                    onAttendantEndFillup = customerVm::onSimulateNozzleShutoff,
                    pinBypassEnabled = pinBypass,
                    verifyPin = gateVm::verifyPin,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    CustomerStateHost(
                        uiState = uiState,
                        identity = gate.identity,
                        onStartTransaction = customerVm::onStartTransaction,
                        onModeTileTap = customerVm::onModeTileTap,
                        onAmountTileTap = customerVm::onAmountTileTap,
                        onMethodTileTap = customerVm::onMethodTileTap,
                        onModeConfirm = customerVm::onModeConfirm,
                        onCashFixedAuthorise = customerVm::onCashFixedAuthorise,
                        onFillupSelectIntent = customerVm::onFillupSelectIntent,
                        onFillupPayCash = customerVm::onFillupPayCash,
                        onFillupPayDigital = customerVm::onFillupPayDigital,
                        onShareReceipt = customerVm::onShareReceipt,
                        onDismissComplete = customerVm::onDismissComplete,
                        onCancel = customerVm::onCancel,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Engineering long-press hotspot — top-left 40dp square. Not visible to attendants
        // or customers; testers reach the debug screen from here. Sits on top of every
        // gate state including onboarding, so a tester can reset the device without
        // having to finish a half-broken install.
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
