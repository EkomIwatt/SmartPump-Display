// Customer-side state router. Switches the visible screen based on TransactionState.
// Phase 5 hosts this inside MainActivity with the attendant overlay layered on top.
package app.balancee.smartpump.display.ui.screens.customer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.balancee.smartpump.display.domain.model.TransactionState

@Composable
fun CustomerStateHost(
    ui: CustomerUiState,
    callbacks: CustomerCallbacks,
    modifier: Modifier = Modifier,
) {
    when (val state = ui.state) {
        TransactionState.Idle -> IdleScreen(
            config = ui.config,
            onStart = callbacks.onStart,
            modifier = modifier,
        )
        TransactionState.ModeSelect -> ModeSelectScreen(
            config = ui.config,
            onSelectMode = callbacks.onSelectMode,
            onBack = callbacks.onBack,
            modifier = modifier,
        )
        is TransactionState.AmountSelect -> AmountSelectScreen(
            config = ui.config,
            onConfirm = callbacks.onConfirmAmount,
            onBack = callbacks.onBack,
            modifier = modifier,
        )
        is TransactionState.PaymentMethodSelect -> PaymentMethodSelectScreen(
            config = ui.config,
            amountKobo = state.amountKobo,
            onSelectMethod = callbacks.onSelectMethod,
            onBack = callbacks.onBack,
            modifier = modifier,
        )
        is TransactionState.AwaitingPayment -> AwaitingPaymentScreen(
            config = ui.config,
            amountKobo = state.amountKobo,
            method = state.method,
            transactionRef = ui.transactionRef ?: "—",
            qrPayload = ui.qrPayload,
            onCancel = callbacks.onCancel,
            modifier = modifier,
        )
        is TransactionState.FillUpConfirm -> FillUpConfirmScreen(
            config = ui.config,
            authorisedByAttendant = state.authorisedByAttendant,
            onCancel = callbacks.onCancel,
            modifier = modifier,
        )
        is TransactionState.Dispensing -> DispensingScreen(
            config = ui.config,
            litresDispensed = ui.liveLitres,
            amountDispensedKobo = ui.liveAmountKobo,
            amountAuthorisedKobo = state.amountKobo,
            litresAuthorised = state.litresAuthorised,
            modifier = modifier,
        )
        is TransactionState.AwaitingCashConfirm -> AwaitingCashConfirmScreen(
            config = ui.config,
            litresDispensed = state.litresDispensed,
            amountDueKobo = state.amountDueKobo,
            modifier = modifier,
        )
        is TransactionState.Complete -> CompleteScreen(
            config = ui.config,
            transactionId = state.transactionId,
            litres = state.litres,
            amountKobo = state.amountKobo,
            onDone = callbacks.onComplete,
            modifier = modifier,
        )
        is TransactionState.Error -> ErrorScreen(
            config = ui.config,
            message = state.message,
            recoverable = state.recoverable,
            onRetry = callbacks.onRetry,
            onDismiss = callbacks.onDismiss,
            modifier = modifier,
        )
    }
}
