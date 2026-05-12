// Renders the right customer-side screen for the current [TransactionState].
// Phase 3a only wires Idle + ModeSelect; every other state shows a placeholder
// that subsequent 3b–3f sub-phases will replace. The host has no business logic
// of its own — it just dispatches on state.
package app.balancee.smartpump.display.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.domain.model.TransactionState
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary
import app.balancee.smartpump.display.ui.theme.borderColor

@Composable
fun CustomerStateHost(
    state: TransactionState,
    onStartTransaction: () -> Unit,
    onSelectPrePay: () -> Unit,
    onSelectFillUp: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is TransactionState.Idle -> IdleScreen(
            onStartTransaction = onStartTransaction,
            modifier = modifier,
        )
        is TransactionState.ModeSelect -> ModeSelectScreen(
            onSelectPrePay = onSelectPrePay,
            onSelectFillUp = onSelectFillUp,
            onCancel = onCancel,
            modifier = modifier,
        )
        else -> NotYetImplementedScreen(
            state = state,
            onCancel = onCancel,
            modifier = modifier,
        )
    }
}

@Composable
private fun NotYetImplementedScreen(
    state: TransactionState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(Dimensions.screenPadding),
        contentAlignment = Alignment.Center,
    ) {
        BalanceeCard(
            borderColor = state.borderColor(),
            modifier = Modifier.sizeIn(maxWidth = 520.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LabelText(text = "Phase 3 — wiring in progress")
                Text(
                    text = state::class.simpleName ?: "Unknown",
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary,
                )
                Text(
                    text = "Screen for this state lands in a later sub-phase.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                )
                BalanceeButton(
                    label = "Back to idle",
                    onClick = onCancel,
                    variant = BalanceeButtonVariant.Secondary,
                )
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    backgroundColor = 0xFF0A0A0F,
    widthDp = 1024,
    heightDp = 600,
)
@Composable
private fun CustomerStateHostIdlePreview() {
    SmartPumpDisplayTheme {
        CustomerStateHost(
            state = TransactionState.Idle,
            onStartTransaction = {},
            onSelectPrePay = {},
            onSelectFillUp = {},
            onCancel = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    backgroundColor = 0xFF0A0A0F,
    widthDp = 1024,
    heightDp = 600,
)
@Composable
private fun CustomerStateHostModeSelectPreview() {
    SmartPumpDisplayTheme {
        CustomerStateHost(
            state = TransactionState.ModeSelect,
            onStartTransaction = {},
            onSelectPrePay = {},
            onSelectFillUp = {},
            onCancel = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    backgroundColor = 0xFF0A0A0F,
    widthDp = 1024,
    heightDp = 600,
)
@Composable
private fun CustomerStateHostPlaceholderPreview() {
    SmartPumpDisplayTheme {
        CustomerStateHost(
            state = TransactionState.PrepayAmountSelect,
            onStartTransaction = {},
            onSelectPrePay = {},
            onSelectFillUp = {},
            onCancel = {},
        )
    }
}
