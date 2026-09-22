// Dispatches the right customer-side screen for the current [TransactionState].
// Phase 3b–3f wired all five customer flows; Phase 4 lifted the attendant actions out of
// the customer state host — they now sit in the swipe-up [AttendantOverlayHost] which
// wraps this composable from outside. The host below is therefore customer-only:
// no FILL UP AUTHORISE / AUTHORISE CASH / CASH RECEIVED callbacks reach down to a screen.
//
// Money: prices/amounts arrive as kobo (Long) on the state and the ui-state; screens render
// them via ui/util/formatNaira. Customer-typed entry callbacks (amount tiles, cash keypad)
// stay in whole naira (Int) — the VM converts those to kobo.
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
import app.balancee.smartpump.display.ui.theme.PrimaryGold
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
    onCashFixedAuthorise: (Long) -> Unit,
    onFillupSelectIntent: (PostFillIntent) -> Unit,
    onFillupPayCash: () -> Unit,
    onFillupPayDigital: () -> Unit,
    onFillupDigitalCancel: () -> Unit,
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
            priceKoboPerLitre = uiState.priceKoboPerLitre,
            onModeTileTap = onModeTileTap,
            onAmountTileTap = onAmountTileTap,
            onMethodTileTap = onMethodTileTap,
            onConfirm = onModeConfirm,
            onCancel = onCancel,
            modifier = modifier,
        )

        is TransactionState.PrepayAwaitingPayment -> PrepayAwaitingPaymentScreen(
            amountKobo = state.amountKobo,
            method = state.method,
            txnId = state.txnId,
            priceKoboPerLitre = state.priceKoboPerLitre,
            expiresInSeconds = uiState.prepayExpiresInSeconds,
            checkoutUrl = state.checkoutUrl,
            onCancel = onCancel,
            modifier = modifier,
        )

        is TransactionState.UssdAwaitingSms -> UssdAwaitingSmsScreen(
            amountKobo = state.amountKobo,
            txnRef = state.txnRef,
            txnId = state.txnId,
            priceKoboPerLitre = state.priceKoboPerLitre,
            expiresInSeconds = uiState.ussdExpiresInSeconds,
            onCancel = onCancel,
            modifier = modifier,
        )

        is TransactionState.FixedDispensing -> FixedDispensingScreen(
            flow = state.flow,
            txnId = state.txnId,
            priceKoboPerLitre = state.priceKoboPerLitre,
            amountKobo = state.amountKobo,
            litresAuthorised = state.litresAuthorised,
            litresSoFar = state.litresSoFar,
            stationName = identity?.displayName.orEmpty(),
            modifier = modifier,
        )

        is TransactionState.CashFixedAmountEntry -> CashFixedAmountEntryScreen(
            priceKoboPerLitre = uiState.priceKoboPerLitre,
            onAuthorise = onCashFixedAuthorise,
            onCancel = onCancel,
            modifier = modifier,
        )

        is TransactionState.CashFixedDispensing -> FixedDispensingScreen(
            flow = TransactionFlow.CASH_FIXED,
            txnId = state.txnId,
            priceKoboPerLitre = state.priceKoboPerLitre,
            amountKobo = state.cashAmountKobo,
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
            priceKoboPerLitre = state.priceKoboPerLitre,
            litresSoFar = state.litresSoFar,
            stationName = identity?.displayName.orEmpty(),
            modifier = modifier,
        )

        is TransactionState.FillupTankFull -> FillupTankFullScreen(
            txnId = state.txnId,
            priceKoboPerLitre = state.priceKoboPerLitre,
            verifiedLitres = state.verifiedLitres,
            amountDueKobo = state.amountDueKobo,
            onPayCash = onFillupPayCash,
            onPayDigital = onFillupPayDigital,
            digitalEnabled = true,
            preparingQr = uiState.preparingQr,
            modifier = modifier,
        )

        is TransactionState.FillupDigitalAwaitingPayment -> FillupDigitalAwaitingPaymentScreen(
            txnId = state.txnId,
            verifiedLitres = state.verifiedLitres,
            amountDueKobo = state.amountDueKobo,
            priceKoboPerLitre = uiState.priceKoboPerLitre,
            qrContent = state.qrContent,
            expiresInSeconds = uiState.fillupDigitalExpiresInSeconds,
            // Not the generic cancel: the fuel is in the tank, so this changes how it is paid for
            // and never ends the sale (#R13).
            onCollectCashInstead = onFillupDigitalCancel,
            modifier = modifier,
        )

        is TransactionState.FillupAwaitingCashConfirm -> FillupAwaitingCashConfirmScreen(
            txnId = state.txnId,
            verifiedLitres = state.verifiedLitres,
            amountDueKobo = state.amountDueKobo,
            priceKoboPerLitre = uiState.priceKoboPerLitre,
            modifier = modifier,
        )

        is TransactionState.Complete -> CompleteScreen(
            flow = state.flow,
            txnId = state.txnId,
            litres = state.litres,
            amountKobo = state.amountKobo,
            method = state.method,
            // The price the sale was struck at — the same value the audit row and the shared
            // receipt carry. Not amount ÷ litres: those come apart on an overshoot or a recovered
            // pulse gap, and the quotient is then a price the station never charged (TODO #37).
            priceKoboPerLitre = uiState.priceKoboPerLitre,
            onShareReceipt = onShareReceipt,
            onDismiss = onDismissComplete,
            modifier = modifier,
            litresTarget = state.litresTarget,
        )

        is TransactionState.Error -> ErrorScreen(
            message = state.message,
            recoverable = state.recoverable,
            onDismiss = onCancel,
            modifier = modifier,
        )
    }
}

/**
 * Customer-facing failure card. [message] is the one plain line the customer gets; the diagnostic
 * half lives in the attendant panel and never appears here (OQ #17).
 *
 * [recoverable] changes the presentation, which it did not until 2026-09-12 — the flag was carried
 * on every error and read by nothing, so "try again" and "this pump cannot sell" looked identical
 * to whoever was standing at the pump. Gold reads as "something to do" everywhere else in this app
 * (it is the authorise/cash colour), red as a stop, so the distinction reuses that vocabulary
 * rather than inventing one. The button does the same thing either way: there is no retry in the
 * state machine, and inventing one here would be a behaviour change, not copy.
 *
 * No design screen covers this; see the flag at the top of docs/journal/closed/ERROR_COPY_DRAFT.md.
 */
@Composable
private fun ErrorScreen(
    message: String,
    recoverable: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (recoverable) PrimaryGold else WarningRed
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(Dimensions.screenPadding),
        contentAlignment = Alignment.Center,
    ) {
        BalanceeCard(
            borderColor = accent,
            modifier = Modifier.sizeIn(maxWidth = 520.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LabelText(
                    text = if (recoverable) "Please try again" else "Cannot continue",
                    color = accent,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                )
                BalanceeButton(
                    label = if (recoverable) "Start over" else "Back to idle",
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
            uiState = CustomerUiState(state = TransactionState.Idle, priceKoboPerLitre = 87_000),
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
            onFillupDigitalCancel = {},
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
                state = TransactionState.ModeSelect(mode = TransactionMode.PRE_PAY, amountKobo = 500_000),
                priceKoboPerLitre = 87_000,
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
            onFillupDigitalCancel = {},
            onShareReceipt = {},
            onDismissComplete = {},
            onCancel = {},
        )
    }
}
