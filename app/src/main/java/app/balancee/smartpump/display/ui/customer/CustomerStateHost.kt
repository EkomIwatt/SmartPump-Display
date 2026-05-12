// Dispatches the right customer-side screen for the current [TransactionState].
// Phase 3b wired Flow 1 (Fixed Pre-pay Digital); Phase 3c adds Flow 4 (Cash Fixed).
// Fill-up + USSD states still fall through to NotYetImplementedScreen until 3d–3f.
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
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionFlow
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
import app.balancee.smartpump.display.ui.theme.WarningRed
import app.balancee.smartpump.display.ui.theme.borderColor

@Composable
fun CustomerStateHost(
    uiState: CustomerUiState,
    onStartTransaction: () -> Unit,
    onSelectPrePay: () -> Unit,
    onSelectFillUp: () -> Unit,
    onPrepayAmountChosen: (Int) -> Unit,
    onPrepayMethodChosen: (PaymentMethod) -> Unit,
    onAttendantCashFixed: () -> Unit,
    onCashFixedAuthorise: (Int) -> Unit,
    onShareReceipt: () -> Unit,
    onDismissComplete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val state = uiState.state) {
        is TransactionState.Idle -> IdleScreen(
            onStartTransaction = onStartTransaction,
            onAttendantCashFixed = onAttendantCashFixed,
            modifier = modifier,
        )

        is TransactionState.ModeSelect -> ModeSelectScreen(
            onSelectPrePay = onSelectPrePay,
            onSelectFillUp = onSelectFillUp,
            onCancel = onCancel,
            modifier = modifier,
        )

        is TransactionState.PrepayAmountSelect -> PrepayAmountSelectScreen(
            onAmountChosen = onPrepayAmountChosen,
            onBack = onCancel,
            modifier = modifier,
        )

        is TransactionState.PrepayMethodSelect -> PrepayMethodSelectScreen(
            amountNaira = state.amountNaira,
            onMethodChosen = onPrepayMethodChosen,
            onBack = onCancel,
            modifier = modifier,
        )

        is TransactionState.PrepayAwaitingPayment -> PrepayAwaitingPaymentScreen(
            amountNaira = state.amountNaira,
            method = state.method,
            txnId = state.txnId,
            pricePerLitre = state.pricePerLitre,
            expiresInSeconds = uiState.prepayExpiresInSeconds,
            onCancel = onCancel,
            modifier = modifier,
        )

        is TransactionState.FixedDispensing -> FixedDispensingScreen(
            flow = state.flow,
            txnId = state.txnId,
            pricePerLitre = state.pricePerLitre,
            amountNaira = state.amountNaira,
            litresAuthorised = state.litresAuthorised,
            litresSoFar = state.litresSoFar,
            modifier = modifier,
        )

        is TransactionState.CashFixedAmountEntry -> CashFixedAmountEntryScreen(
            pricePerLitre = uiState.pricePerLitre,
            onAuthorise = onCashFixedAuthorise,
            onCancel = onCancel,
            modifier = modifier,
        )

        is TransactionState.CashFixedDispensing -> FixedDispensingScreen(
            flow = TransactionFlow.CASH_FIXED,
            txnId = state.txnId,
            pricePerLitre = state.pricePerLitre,
            amountNaira = state.cashAmountNaira,
            litresAuthorised = state.litresCutoff,
            litresSoFar = state.litresSoFar,
            modifier = modifier,
        )

        is TransactionState.Complete -> CompleteScreen(
            flow = state.flow,
            txnId = state.txnId,
            litres = state.litres,
            amountNaira = state.amountNaira,
            method = state.method,
            pricePerLitre = pricePerLitreFromState(state),
            onShareReceipt = onShareReceipt,
            onDismiss = onDismissComplete,
            modifier = modifier,
        )

        is TransactionState.Error -> ErrorScreen(
            message = state.message,
            onDismiss = onCancel,
            modifier = modifier,
        )

        else -> NotYetImplementedScreen(
            state = state,
            onCancel = onCancel,
            modifier = modifier,
        )
    }
}

private fun pricePerLitreFromState(state: TransactionState.Complete): Int =
    if (state.amountNaira > 0 && state.litres > 0) {
        (state.amountNaira / state.litres).toInt()
    } else 0

@Composable
private fun ErrorScreen(
    message: String,
    onDismiss: () -> Unit,
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
            borderColor = WarningRed,
            modifier = Modifier.sizeIn(maxWidth = 520.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LabelText(text = "Error", color = WarningRed)
                Text(
                    text = message,
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                )
                BalanceeButton(
                    label = "Back to idle",
                    onClick = onDismiss,
                )
            }
        }
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
            uiState = CustomerUiState(state = TransactionState.Idle, pricePerLitre = 870),
            onStartTransaction = {},
            onSelectPrePay = {},
            onSelectFillUp = {},
            onPrepayAmountChosen = {},
            onPrepayMethodChosen = {},
            onAttendantCashFixed = {},
            onCashFixedAuthorise = {},
            onShareReceipt = {},
            onDismissComplete = {},
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
private fun CustomerStateHostPrepayAmountPreview() {
    SmartPumpDisplayTheme {
        CustomerStateHost(
            uiState = CustomerUiState(state = TransactionState.PrepayAmountSelect),
            onStartTransaction = {},
            onSelectPrePay = {},
            onSelectFillUp = {},
            onPrepayAmountChosen = {},
            onPrepayMethodChosen = {},
            onAttendantCashFixed = {},
            onCashFixedAuthorise = {},
            onShareReceipt = {},
            onDismissComplete = {},
            onCancel = {},
        )
    }
}
