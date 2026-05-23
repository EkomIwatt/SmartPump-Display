// Maps a [TransactionState] to its border / chip colour per the state→border table
// in docs/design-system.md. Single source of truth so screens never re-derive it.
package app.balancee.smartpump.display.ui.theme

import androidx.compose.ui.graphics.Color
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.model.TransactionState

fun TransactionState.borderColor(): Color = when (this) {
    is TransactionState.Idle,
    is TransactionState.ModeSelect,
    is TransactionState.FillupAwaitingAttendantAuth,
    is TransactionState.CashFixedAmountEntry -> BorderSubtle

    is TransactionState.PrepayAwaitingPayment,
    is TransactionState.UssdAwaitingSms,
    is TransactionState.FillupTankFull,
    is TransactionState.FillupAwaitingCashConfirm,
    is TransactionState.FillupDigitalAwaitingPayment -> PrimaryGold

    is TransactionState.FillupDispensing -> ActiveCyan

    is TransactionState.CashFixedDispensing -> PrimaryGold

    is TransactionState.FixedDispensing -> when (flow) {
        TransactionFlow.CASH_FIXED -> PrimaryGold
        else -> SuccessGreen
    }

    // Per strict-design Flow 1 (Screenshot 224956) the digital-pre-pay receipt
    // is gold-bordered; cash, fill-up, and USSD completions stay green.
    is TransactionState.Complete -> when (flow) {
        TransactionFlow.FIXED_PREPAY_DIGITAL -> PrimaryGold
        else -> SuccessGreen
    }

    is TransactionState.Error -> WarningRed
}
