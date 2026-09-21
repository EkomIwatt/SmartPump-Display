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

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.balancee.smartpump.display.BuildConfig
import app.balancee.smartpump.display.ui.attendant.AttendantOverlayHost
import app.balancee.smartpump.display.ui.customer.CustomerStateHost
import app.balancee.smartpump.display.ui.customer.CustomerViewModel
import app.balancee.smartpump.display.ui.debug.DebugScreen
import app.balancee.smartpump.display.ui.operator.OperatorConfigScreen
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
    val uiState by customerVm.ui.collectAsStateWithLifecycle()
    val gateState by gateVm.state.collectAsStateWithLifecycle()
    val pinBypass by gateVm.pinBypassEnabled.collectAsStateWithLifecycle()
    var debugVisible by rememberSaveable { mutableStateOf(false) }
    // Survives rotation/process death: an operator mid-configuration should not be dropped back
    // to the customer screen. Re-entry still costs a PIN, since the overlay is closed by then.
    var settingsVisible by rememberSaveable { mutableStateOf(false) }

    if (debugVisible) {
        DebugScreen(
            onClose = { debugVisible = false },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    if (settingsVisible) {
        OperatorConfigScreen(
            onClose = { settingsVisible = false },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    // Receipt sharing (OQ #14 — the Android system share sheet, no bespoke channel). Collected
    // here rather than in CompleteScreen: firing an intent needs the Activity context, and a
    // one-shot event must not live inside a screen that recomposes. The ViewModel builds the text;
    // this only carries it to the OS.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        customerVm.shareReceipt.collect { receipt ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, receipt)
            }
            context.startActivity(Intent.createChooser(send, null))
        }
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
                    onAttendantEndSaleEarly = customerVm::onAttendantEndSaleEarly,
                    onOpenSettings = { settingsVisible = true },
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
                        onFillupDigitalCancel = customerVm::onFillupDigitalCancel,
                        onShareReceipt = customerVm::onShareReceipt,
                        onDismissComplete = customerVm::onDismissComplete,
                        onCancel = customerVm::onCancel,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Engineering long-press hotspot — top-left 40dp square. DEBUG builds only: the
        // debug screen exposes payment force-resolve and live device-config editing (price,
        // virtual account), so it must never be reachable in a release/production build.
        // Not visible to attendants or customers; testers reach the debug screen from here.
        if (BuildConfig.DEBUG) {
            DebugLongPressHotspot(
                onOpenDebug = { debugVisible = true },
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
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
