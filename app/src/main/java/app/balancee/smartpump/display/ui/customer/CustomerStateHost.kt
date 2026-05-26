// Dispatches the right customer-side screen for the current [TransactionState].
// Phase 3b–3f wired all five customer flows; Phase 4 lifted the attendant actions out of
// the customer state host — they now sit in the swipe-up [AttendantOverlayHost] which
// wraps this composable from outside. The host below is therefore customer-only:
// no FILL UP AUTHORISE / AUTHORISE CASH / CASH RECEIVED callbacks reach down to a screen.
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
import app.balancee.smartpump.display.domain.model.PostFillIntent
import app.balancee.smartpump.display.domain.model.StationIdentity
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.WarningRed

@Composable
fun CustomerStateHost(
    uiState: CustomerUiState,
    identity: StationIdentity?,
    onStartTransaction: () -> Unit,
    onModeTileTap: (TransactionMode) -> Unit,
    onAmountTileTap: (Int) -> Unit,
    onMethodTileTap: (PaymentMethod) -> Unit,
    onModeConfirm: () -> Unit,
    onCashFixedAuthorise: (Int) -> Unit,
    onFillupSelectIntent: (PostFillIntent) -> Unit,
    onFillupPayCash: () -> Unit,
    onFillupPayDigital: () -> Unit,
    onShareReceipt: () -> Unit,
    onDismissComplete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val state = uiState.state) {
        is TransactionState.Idle -> IdleScreen(
            onStartTransaction = onStartTransaction,
            displayName = identity?.displayName.orEmpty(),
            logoBytes = identity?.logoBytes,
            modifier = modifier,
        )

        is TransactionState.ModeSelect -> ModeSelectScreen(
            state = state,
            displayName = identity?.displayName.orEmpty(),
            logoBytes = identity?.logoBytes,
            pricePerLitre = uiState.pricePerLitre,
            onModeTileTap = onModeTileTap,
            onAmountTileTap = onAmountTileTap,
            onMethodTileTap = onMethodTileTap,
            onConfirm = onModeConfirm,
            onCancel = onCancel,
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

        is TransactionState.UssdAwaitingSms -> UssdAwaitingSmsScreen(
            amountNaira = state.amountNaira,
            txnRef = state.txnRef,
            txnId = state.txnId,
            pricePerLitre = state.pricePerLitre,
            expiresInSeconds = uiState.ussdExpiresInSeconds,
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
            stationName = identity?.displayName.orEmpty(),
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
            stationName = identity?.displayName.orEmpty(),
            modifier = modifier,
        )

        is TransactionState.FillupAwaitingAttendantAuth -> FillupAwaitingAttendantAuthScreen(
            intent = state.intent,
            displayName = identity?.displayName.orEmpty(),
            logoBytes = identity?.logoBytes,
            onSelectIntent = onFillupSelectIntent,
            onCancel = onCancel,
            modifier = modifier,
        )

        is TransactionState.FillupDispensing -> FillupDispensingScreen(
            txnId = state.txnId,
            pricePerLitre = state.pricePerLitre,
            litresSoFar = state.litresSoFar,
            stationName = identity?.displayName.orEmpty(),
            modifier = modifier,
        )

        is TransactionState.FillupTankFull -> FillupTankFullScreen(
            txnId = state.txnId,
            pricePerLitre = state.pricePerLitre,
            verifiedLitres = state.verifiedLitres,
            amountDueNaira = state.amountDueNaira,
            onPayCash = onFillupPayCash,
            onPayDigital = onFillupPayDigital,
            digitalEnabled = true,
            modifier = modifier,
        )

        is TransactionState.FillupDigitalAwaitingPayment -> FillupDigitalAwaitingPaymentScreen(
            txnId = state.txnId,
            verifiedLitres = state.verifiedLitres,
            amountDueNaira = state.amountDueNaira,
            pricePerLitre = uiState.pricePerLitre,
            qrContent = state.qrContent,
            expiresInSeconds = uiState.fillupDigitalExpiresInSeconds,
            onCancel = onCancel,
            modifier = modifier,
        )

        is TransactionState.FillupAwaitingCashConfirm -> FillupAwaitingCashConfirmScreen(
            txnId = state.txnId,
            verifiedLitres = state.verifiedLitres,
            amountDueNaira = state.amountDueNaira,
            pricePerLitre = uiState.pricePerLitre,
            onCancel = onCancel,
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

@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    backgroundColor = 0xFF0B0B0A,
    widthDp = 1024,
    heightDp = 600,
)
@Composable
private fun CustomerStateHostIdlePreview() {
    SmartPumpDisplayTheme {
        CustomerStateHost(
            uiState = CustomerUiState(state = TransactionState.Idle, pricePerLitre = 870),
            identity = null,
            onStartTransaction = {},
            onModeTileTap = {},
            onAmountTileTap = {},
            onMethodTileTap = {},
            onModeConfirm = {},
            onCashFixedAuthorise = {},
            onFillupSelectIntent = {},
            onFillupPayCash = {},
            onFillupPayDigital = {},
            onShareReceipt = {},
            onDismissComplete = {},
            onCancel = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    backgroundColor = 0xFF0B0B0A,
    widthDp = 1024,
    heightDp = 600,
)
@Composable
private fun CustomerStateHostModeSelectPreview() {
    SmartPumpDisplayTheme {
        CustomerStateHost(
            uiState = CustomerUiState(
                state = TransactionState.ModeSelect(mode = TransactionMode.PRE_PAY, amountNaira = 5000),
                pricePerLitre = 870,
            ),
            identity = null,
            onStartTransaction = {},
            onModeTileTap = {},
            onAmountTileTap = {},
            onMethodTileTap = {},
            onModeConfirm = {},
            onCashFixedAuthorise = {},
            onFillupSelectIntent = {},
            onFillupPayCash = {},
            onFillupPayDigital = {},
            onShareReceipt = {},
            onDismissComplete = {},
            onCancel = {},
        )
    }
}
