// Handles an incoming PaymentResult and drives the state machine forward.
// On Success → transition to Dispensing and signal relay to close.
// On Failed  → transition to Error (recoverable).
package app.balancee.smartpump.display.domain.usecase

import app.balancee.smartpump.display.domain.model.PaymentResult
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import app.balancee.smartpump.display.domain.repository.PulseRepository
import app.balancee.smartpump.display.domain.repository.TransactionRepository
import javax.inject.Inject

class HandlePaymentResultUseCase @Inject constructor(
    private val pulseRepository: PulseRepository,
    private val transactionRepository: TransactionRepository,
) {
    /**
     * Process [result] from the payment layer and return the next [TransactionState].
     * TODO Phase 3: also signal RelayController to close on Success
     */
    suspend fun execute(
        result: PaymentResult,
        currentState: TransactionState.AwaitingPayment,
    ): TransactionState = when (result) {
        is PaymentResult.Success -> {
            val next = TransactionState.Dispensing(
                mode = currentState.mode,
                amountKobo = currentState.amountKobo,
                litresAuthorised = null, // computed from DeviceConfig in Phase 3
            )
            pulseRepository.saveTransactionState(next, result.transactionRef)
            next
        }
        is PaymentResult.Failed -> {
            TransactionState.Error(
                message = result.reason,
                recoverable = true,
            )
        }
        is PaymentResult.Pending -> currentState // stay in AwaitingPayment
    }
}
