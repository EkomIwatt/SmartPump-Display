// Wraps the customer state host with the swipe-up attendant overlay.
//
// Discoverability + gesture:
//  - A subtle pill handle pinned to the bottom edge. Tap to open; swipe up to open.
//  - A scrim above the customer surface darkens the screen while the panel is open;
//    tapping the scrim or swiping the panel back down dismisses it.
//
// PIN gating (boss edit 2026-05-26): the PIN is demanded ONCE, to open the panel — not
// per action. The handle triggers a PIN modal; on success the panel slides up and every
// action (FILL UP AUTHORISE / AUTHORISE CASH / CASH RECEIVED) fires directly with no
// further prompt. Cancelling the PIN keeps the panel closed; each fresh open re-asks.
// Debug builds skip the gate via pinBypassEnabled.
//
// Animation: 250ms ease-out slide-up / ease-in slide-down — matches design-system.md.
package app.balancee.smartpump.display.ui.attendant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.domain.model.TransactionState
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.PrimaryGold

private const val OVERLAY_ANIM_MS = 250
private const val SWIPE_UP_THRESHOLD_DP = 32  // net drag in dp to count as a swipe-up open
private const val SWIPE_DOWN_THRESHOLD_DP = 48 // net drag in dp on the panel to dismiss

@Composable
fun AttendantOverlayHost(
    state: TransactionState,
    onAttendantFillUp: () -> Unit,
    onAttendantCashFixed: () -> Unit,
    onAttendantCashReceived: () -> Unit,
    onAttendantEndFillup: () -> Unit,
    onOpenSettings: () -> Unit,
    pinBypassEnabled: Boolean,
    verifyPin: suspend (String) -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    // Whether the PIN modal is up, gating the panel open. Local (not saveable): a process
    // death mid-PIN simply re-shows the closed handle, which is the correct safe default.
    var pinGateVisible by remember { mutableStateOf(false) }

    // The handle was activated. Debug builds open straight away; otherwise demand the PIN.
    fun requestOpen() {
        if (pinBypassEnabled) visible = true else pinGateVisible = true
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Base customer-facing layer.
        content()

        // Bottom-edge swipe-up handle (always present, very low-vis until interacted with).
        // Sits above the content but only consumes the small pill region so it doesn't steal
        // taps from screen content (BalanceeButtons are well above this strip).
        if (!visible) {
            SwipeUpHandle(
                modifier = Modifier.align(Alignment.BottomCenter),
                onActivate = { requestOpen() },
            )
        }

        // Scrim — darkens content while the panel is open, dismisses on tap.
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(OVERLAY_ANIM_MS)),
            exit = fadeOut(tween(OVERLAY_ANIM_MS)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { visible = false },
                    ),
            )
        }

        // Sliding panel.
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                animationSpec = tween(OVERLAY_ANIM_MS),
                initialOffsetY = { fullHeight -> fullHeight },
            ),
            exit = slideOutVertically(
                animationSpec = tween(OVERLAY_ANIM_MS),
                targetOffsetY = { fullHeight -> fullHeight },
            ),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            DismissableOnDragDown(
                onDismiss = { visible = false },
                modifier = Modifier.fillMaxWidth(),
            ) {
                // The panel only opens after the PIN gate clears, so every action fires
                // directly and then closes the panel — no per-action prompt.
                AttendantPanel(
                    state = state,
                    onFillUpAuthorise = {
                        onAttendantFillUp()
                        visible = false
                    },
                    onAuthoriseCash = {
                        onAttendantCashFixed()
                        visible = false
                    },
                    onCashReceived = {
                        onAttendantCashReceived()
                        visible = false
                    },
                    onEndFillup = {
                        onAttendantEndFillup()
                        visible = false
                    },
                    // Closing the panel first means settings opens over a clean screen, and the
                    // PIN that opened the panel is the same one gating settings — no second prompt.
                    onOpenSettings = {
                        visible = false
                        onOpenSettings()
                    },
                    onDismiss = { visible = false },
                )
            }
        }

        // PIN gate — demanded once to open the panel. Sits on top of the (still-closed) panel
        // + scrim. On success the panel slides up; cancelling leaves everything closed.
        if (pinGateVisible) {
            PinEntryModal(
                title = "Unlock attendant",
                onVerify = verifyPin,
                onSuccess = {
                    pinGateVisible = false
                    visible = true
                },
                onCancel = { pinGateVisible = false },
            )
        }
    }
}

@Composable
private fun SwipeUpHandle(
    modifier: Modifier,
    onActivate: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                val thresholdPx = SWIPE_UP_THRESHOLD_DP.dp.toPx()
                var dragSum = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragSum = 0f },
                    onDragEnd = { if (dragSum < -thresholdPx) onActivate() },
                    onDragCancel = { dragSum = 0f },
                ) { _, delta -> dragSum += delta }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onActivate,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(BorderSubtle),
        )
        // Subtle gold tick above the handle so attendants can see it in daylight.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp)
                .width(8.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(PrimaryGold.copy(alpha = 0.4f)),
        )
    }
}

@Composable
private fun DismissableOnDragDown(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val thresholdPx = with(LocalDensity.current) { SWIPE_DOWN_THRESHOLD_DP.dp.toPx() }
    var dragSum by remember { mutableStateOf(0f) }
    Box(
        modifier = modifier.draggable(
            orientation = Orientation.Vertical,
            state = rememberDraggableState { delta -> dragSum += delta },
            onDragStarted = { dragSum = 0f },
            onDragStopped = {
                // Positive delta on Vertical orientation = down. Past the threshold dismisses.
                if (dragSum > thresholdPx) onDismiss()
                dragSum = 0f
            },
        ),
    ) {
        content()
    }
}
