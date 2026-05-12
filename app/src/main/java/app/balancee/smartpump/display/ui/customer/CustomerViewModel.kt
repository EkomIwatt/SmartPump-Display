// Customer-side state machine for Phase 3b — Flow 1 (Fixed Pre-pay Digital) is wired
// end-to-end. Idle → ModeSelect → PrepayAmountSelect → PrepayMethodSelect →
// PrepayAwaitingPayment → FixedDispensing → Complete.
//
// Wiring notes:
//  - Price guard (CanStartTransactionUseCase) blocks any new transaction when koboPerLitre is unset.
//    A debug-build default config is seeded on first launch so the flow is playable; real
//    operator-push lands in Phase 4 (debug screen) / Phase 6 (backend).
//  - PaymentProcessor.process emits Pending then Success/Failed. Success drives Pre-pay → Dispensing.
//  - 5-min QR expiry (spec) is enforced by a per-state countdown coroutine.
//  - Pulse counts come from the injected PulseSource; the mock generates ~50 pps when the
//    relay is open. Litres are derived at 100 pulses/L (matches the prior mock; see
//    OPEN_QUESTIONS #1 for production confirmation).
//  - Persistence (PulseRepository) plumbing is intentionally NOT wired here — Phase 5 handles
//    boot-time resume so we keep the customer VM small until then.
//
// Other flows (Fill-up cash/digital, Cash fixed, USSD) still drop into the placeholder
// in CustomerStateHost; those land in 3c–3f.
package app.balancee.smartpump.display.ui.customer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.balancee.smartpump.display.domain.hardware.PulseSource
import app.balancee.smartpump.display.domain.hardware.RelayController
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.PaymentResult
import app.balancee.smartpump.display.domain.model.PulseMessage
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.model.TransactionState
import app.balancee.smartpump.display.domain.payment.PaymentProcessor
import app.balancee.smartpump.display.domain.repository.DeviceConfigRepository
import app.balancee.smartpump.display.domain.usecase.CanStartTransactionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val PULSES_PER_LITRE = 100
private const val PREPAY_EXPIRY_SECONDS = 5 * 60

/** Wraps the canonical [TransactionState] with view-only timer fields the host screens read. */
data class CustomerUiState(
    val state: TransactionState = TransactionState.Idle,
    val prepayExpiresInSeconds: Int = 0,
)

@HiltViewModel
class CustomerViewModel @Inject constructor(
    private val canStartTransaction: CanStartTransactionUseCase,
    private val deviceConfigRepository: DeviceConfigRepository,
    private val paymentProcessor: PaymentProcessor,
    private val pulseSource: PulseSource,
    private val relay: RelayController,
) : ViewModel() {

    private val _ui = MutableStateFlow(CustomerUiState())
    val ui: StateFlow<CustomerUiState> = _ui.asStateFlow()

    private var paymentJob: Job? = null
    private var expiryJob: Job? = null
    private var dispenseJob: Job? = null
    private var pricePerLitre: Int = 0

    init {
        viewModelScope.launch { seedDefaultConfigIfMissing() }
    }

    // ---- Idle / ModeSelect ----

    fun onStartTransaction() {
        if (currentState() !is TransactionState.Idle) return
        viewModelScope.launch {
            when (val result = canStartTransaction()) {
                is CanStartTransactionUseCase.Result.Allowed -> {
                    pricePerLitre = (result.config.koboPerLitre / 100).toInt()
                    setState(TransactionState.ModeSelect)
                }
                CanStartTransactionUseCase.Result.PriceNotSet -> {
                    setState(
                        TransactionState.Error(
                            message = "Price not set — contact operator.",
                            recoverable = true,
                        )
                    )
                }
            }
        }
    }

    fun onSelectPrePay() {
        if (currentState() is TransactionState.ModeSelect) {
            setState(TransactionState.PrepayAmountSelect)
        }
    }

    fun onSelectFillUp() {
        if (currentState() is TransactionState.ModeSelect) {
            setState(TransactionState.FillupAwaitingAttendantAuth)
        }
    }

    // ---- Pre-pay flow ----

    fun onPrepayAmountChosen(amountNaira: Int) {
        if (currentState() is TransactionState.PrepayAmountSelect) {
            setState(TransactionState.PrepayMethodSelect(amountNaira = amountNaira))
        }
    }

    fun onPrepayMethodChosen(method: PaymentMethod) {
        val current = currentState() as? TransactionState.PrepayMethodSelect ?: return
        if (method == PaymentMethod.CASH_SEE_ATTENDANT) {
            // Cash routes through the attendant (overlay lands in Phase 4). For now,
            // return to Idle so the customer is told to talk to the attendant verbally.
            onCancel()
            return
        }
        startPrepayPayment(amountNaira = current.amountNaira, method = method)
    }

    private fun startPrepayPayment(amountNaira: Int, method: PaymentMethod) {
        cancelInFlightJobs()
        val amountKobo = amountNaira.toLong() * 100
        paymentJob = viewModelScope.launch {
            paymentProcessor.process(method, amountKobo).collect { result ->
                when (result) {
                    is PaymentResult.Pending -> onPaymentPending(amountNaira, method, result)
                    is PaymentResult.Success -> onPaymentSuccess(amountNaira, method, result)
                    is PaymentResult.Failed -> onPaymentFailed(result)
                }
            }
        }
    }

    private fun onPaymentPending(
        amountNaira: Int,
        method: PaymentMethod,
        pending: PaymentResult.Pending,
    ) {
        setState(
            TransactionState.PrepayAwaitingPayment(
                flow = TransactionFlow.FIXED_PREPAY_DIGITAL,
                amountNaira = amountNaira,
                method = method,
                txnId = pending.transactionRef,
                pricePerLitre = pricePerLitre,
            )
        )
        startExpiryCountdown()
    }

    private suspend fun onPaymentSuccess(
        amountNaira: Int,
        method: PaymentMethod,
        success: PaymentResult.Success,
    ) {
        expiryJob?.cancel()
        val litresAuthorised = deviceConfig()?.litresCutoff(success.amountKobo)
            ?: ((amountNaira.toDouble() / pricePerLitre).coerceAtLeast(0.0))

        setState(
            TransactionState.FixedDispensing(
                flow = TransactionFlow.FIXED_PREPAY_DIGITAL,
                txnId = success.transactionRef,
                pricePerLitre = pricePerLitre,
                amountNaira = amountNaira,
                litresAuthorised = litresAuthorised,
                litresSoFar = 0.0,
            )
        )
        startDispensing(litresAuthorised, method)
    }

    private fun onPaymentFailed(failed: PaymentResult.Failed) {
        cancelInFlightJobs()
        setState(
            TransactionState.Error(
                message = "Payment failed — ${failed.reason}.",
                recoverable = true,
            )
        )
    }

    private fun startExpiryCountdown() {
        expiryJob?.cancel()
        expiryJob = viewModelScope.launch {
            var remaining = PREPAY_EXPIRY_SECONDS
            _ui.update { it.copy(prepayExpiresInSeconds = remaining) }
            while (remaining > 0 && currentState() is TransactionState.PrepayAwaitingPayment) {
                delay(1_000L)
                remaining -= 1
                _ui.update { it.copy(prepayExpiresInSeconds = remaining) }
            }
            if (remaining <= 0 && currentState() is TransactionState.PrepayAwaitingPayment) {
                cancelInFlightJobs()
                setState(TransactionState.Idle)
            }
        }
    }

    private fun startDispensing(litresAuthorised: Double, method: PaymentMethod) {
        dispenseJob?.cancel()
        dispenseJob = viewModelScope.launch {
            relay.open()
            try {
                pulseSource.observe().collect { msg ->
                    if (msg !is PulseMessage.Pulse) return@collect
                    val current = currentState() as? TransactionState.FixedDispensing
                        ?: return@collect
                    val litres = msg.count.toDouble() / PULSES_PER_LITRE
                    if (litres >= litresAuthorised) {
                        relay.close()
                        setState(
                            TransactionState.Complete(
                                flow = current.flow,
                                txnId = current.txnId,
                                litres = litresAuthorised,
                                amountNaira = current.amountNaira,
                                method = method,
                            )
                        )
                        return@collect
                    }
                    setState(current.copy(litresSoFar = litres))
                }
            } finally {
                relay.close()
            }
        }
    }

    // ---- Cancel / dismiss ----

    fun onCancel() {
        cancelInFlightJobs()
        viewModelScope.launch { relay.close() }
        setState(TransactionState.Idle)
        _ui.update { it.copy(prepayExpiresInSeconds = 0) }
    }

    fun onShareReceipt() {
        // Wired to a real share sheet in Phase 6. Logged-only for now so the button isn't dead.
        // Intentionally no state change.
    }

    fun onDismissComplete() {
        if (currentState() is TransactionState.Complete) onCancel()
    }

    // ---- Helpers ----

    private fun currentState(): TransactionState = _ui.value.state

    private fun setState(state: TransactionState) {
        _ui.update { it.copy(state = state) }
    }

    private fun cancelInFlightJobs() {
        paymentJob?.cancel()
        expiryJob?.cancel()
        dispenseJob?.cancel()
        paymentJob = null
        expiryJob = null
        dispenseJob = null
    }

    private suspend fun seedDefaultConfigIfMissing() {
        if (deviceConfigRepository.getConfig() == null) {
            deviceConfigRepository.saveConfig(
                DeviceConfig(
                    pumpId = "PUMP 1",
                    stationName = "Total Lekki Ph2",
                    koboPerLitre = DEFAULT_KOBO_PER_LITRE,
                    virtualAccountNumber = "0123456789",
                )
            )
        }
    }

    private suspend fun deviceConfig(): DeviceConfig? = deviceConfigRepository.getConfig()

    private companion object {
        // Stop-gap default until the operator-push channel lands (Phase 6). Matches the spec example
        // (₦870/L ⇒ 87_000 kobo/L). The Phase 4 debug screen will let testers override this live.
        const val DEFAULT_KOBO_PER_LITRE = 87_000L
    }
}
