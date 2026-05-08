// Bottom-anchored attendant overlay. Customer screens own the full window; the overlay
// floats over them with a swipe-up handle. Expanded panel shows 4 actions:
//   FILL UP AUTHORISE   — only enabled while in FillUpConfirm.
//   CASH RECEIVED       — only enabled while in AwaitingCashConfirm.
//   CANCEL TRANSACTION  — enabled whenever a transaction is in flight.
//   DEBUG               — opens the mock-controls debug screen.
//
// Tap the handle or swipe vertically to toggle between collapsed (handle only) and
// expanded (full panel). The panel never blocks the entire screen so customer info
// remains visible above it.
package app.balancee.smartpump.display.ui.screens.attendant

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.domain.model.TransactionState
import app.balancee.smartpump.display.ui.components.BalanceeButtonPrimary
import app.balancee.smartpump.display.ui.components.BalanceeButtonSecondary
import app.balancee.smartpump.display.ui.theme.Dimensions

private val HandleHeight = 32.dp

@Composable
fun AttendantOverlay(
    state: TransactionState,
    onAuthoriseFillUp: () -> Unit,
    onConfirmCash: () -> Unit,
    onCancelTransaction: () -> Unit,
    onOpenDebug: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val height by animateDpAsState(
        targetValue = if (expanded) Dimensions.attendantOverlayHeight else HandleHeight,
        label = "attendantOverlayHeight",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dy ->
                    if (dy < -DragThreshold) expanded = true
                    else if (dy > DragThreshold) expanded = false
                }
            },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 8.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DragHandle(onClick = { expanded = !expanded })
            if (expanded) {
                AttendantActions(
                    state = state,
                    onAuthoriseFillUp = { expanded = false; onAuthoriseFillUp() },
                    onConfirmCash = { expanded = false; onConfirmCash() },
                    onCancelTransaction = { expanded = false; onCancelTransaction() },
                    onOpenDebug = { expanded = false; onOpenDebug() },
                )
            }
        }
    }
}

@Composable
private fun DragHandle(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HandleHeight)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}

@Composable
private fun AttendantActions(
    state: TransactionState,
    onAuthoriseFillUp: () -> Unit,
    onConfirmCash: () -> Unit,
    onCancelTransaction: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    val canAuthorise = state is TransactionState.FillUpConfirm && !state.authorisedByAttendant
    val canConfirmCash = state is TransactionState.AwaitingCashConfirm
    val canCancel = state !is TransactionState.Idle && state !is TransactionState.Complete

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "ATTENDANT",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stateSummary(state),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BalanceeButtonPrimary(
                text = "FILL UP AUTHORISE",
                onClick = onAuthoriseFillUp,
                enabled = canAuthorise,
                modifier = Modifier.weight(1f),
            )
            BalanceeButtonPrimary(
                text = "CASH RECEIVED",
                onClick = onConfirmCash,
                enabled = canConfirmCash,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BalanceeButtonSecondary(
                text = "CANCEL TRANSACTION",
                onClick = onCancelTransaction,
                enabled = canCancel,
                modifier = Modifier.weight(1f),
            )
            BalanceeButtonSecondary(
                text = "DEBUG",
                onClick = onOpenDebug,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun stateSummary(state: TransactionState): String = when (state) {
    TransactionState.Idle -> "No active transaction"
    TransactionState.ModeSelect -> "Customer choosing mode"
    is TransactionState.AmountSelect -> "Customer entering amount"
    is TransactionState.PaymentMethodSelect -> "Customer choosing payment"
    is TransactionState.AwaitingPayment -> "Awaiting payment · ${state.method.name}"
    is TransactionState.FillUpConfirm ->
        if (state.authorisedByAttendant) "Fill-up authorised — fueling" else "Fill-up — awaiting your authorisation"
    is TransactionState.Dispensing ->
        if (state.amountKobo == null) "Dispensing (fill-up)" else "Dispensing (pre-pay)"
    is TransactionState.AwaitingCashConfirm -> "Awaiting cash confirmation"
    is TransactionState.Complete -> "Transaction complete"
    is TransactionState.Error -> "Error · ${state.message}"
}

private const val DragThreshold = 6f
