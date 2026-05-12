// Maps a [TransactionState] to its border / chip colour per the state→border table
// in docs/design-system.md. Single source of truth so screens never re-derive it.
package app.balancee.smartpump.display.ui.theme

import androidx.compose.ui.graphics.Color
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.model.TransactionState

fun TransactionState.borderColor(): Color = when (this) {
    is TransactionState.Idle,
    is TransactionState.ModeSelect,
    is TransactionState.PrepayAmountSelect,
    is TransactionState.PrepayMethodSelect,
    is TransactionState.FillupAwaitingAttendantAuth,
    is TransactionState.CashFixedAmountEntry -> BorderSubtle

    is TransactionState.PrepayAwaitingPayment,
    is TransactionState.UssdAwaitingSms,
    is TransactionState.FillupTankFull,
    is TransactionState.FillupAwaitingCashConfirm -> PrimaryAmber

    is TransactionState.FillupDispensing -> ActiveCyan

    is TransactionState.CashFixedDispensing -> PrimaryAmber

    is TransactionState.FixedDispensing -> when (flow) {
        TransactionFlow.CASH_FIXED -> PrimaryAmber
        else -> SuccessGreen
    }

    is TransactionState.FillupDigitalAwaitingPayment,
    is TransactionState.Complete -> SuccessGreen

    is TransactionState.Error -> WarningRed
}
