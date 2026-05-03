// Core state machine for every pump transaction. Persisted to Room on every transition
// so state survives power cuts mid-transaction. Matches spec exactly.
package app.balancee.smartpump.display.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Immutable
sealed class TransactionState {

    @Serializable @SerialName("idle")
    object Idle : TransactionState()

    @Serializable @SerialName("mode_select")
    object ModeSelect : TransactionState()

    @Serializable @SerialName("amount_select")
    data class AmountSelect(val mode: TransactionMode) : TransactionState()

    @Serializable @SerialName("payment_method_select")
    data class PaymentMethodSelect(
        val mode: TransactionMode,
        val amountKobo: Long,
    ) : TransactionState()

    @Serializable @SerialName("awaiting_payment")
    data class AwaitingPayment(
        val mode: TransactionMode,
        val amountKobo: Long,
        val method: PaymentMethod,
    ) : TransactionState()

    /** Fill-up flow — waiting for attendant to swipe up and tap FILL UP AUTHORISE. */
    @Serializable @SerialName("fill_up_confirm")
    data class FillUpConfirm(val authorisedByAttendant: Boolean) : TransactionState()

    /**
     * Relay closed, pulses counting.
     * [amountKobo] and [litresAuthorised] are null for open-ended fill-up mode.
     */
    @Serializable @SerialName("dispensing")
    data class Dispensing(
        val mode: TransactionMode,
        val amountKobo: Long?,
        val litresAuthorised: Double?,
    ) : TransactionState()

    /** Fill-up nozzle shutoff detected. Final count locked. Attendant must tap CASH RECEIVED. */
    @Serializable @SerialName("awaiting_cash_confirm")
    data class AwaitingCashConfirm(
        val litresDispensed: Double,
        val amountDueKobo: Long,
    ) : TransactionState()

    @Serializable @SerialName("complete")
    data class Complete(
        val transactionId: String,
        val litres: Double,
        val amountKobo: Long,
    ) : TransactionState()

    @Serializable @SerialName("error")
    data class Error(val message: String, val recoverable: Boolean) : TransactionState()
}
