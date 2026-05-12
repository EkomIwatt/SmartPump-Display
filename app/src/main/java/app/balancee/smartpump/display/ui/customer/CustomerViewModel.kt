// Customer-side state machine. Phase 3b wired Flow 1 (Fixed Pre-pay Digital);
// Phase 3c adds Flow 4 (Cash Fixed): Idle → CashFixedAmountEntry → CashFixedDispensing → Complete.
// The cash-fixed entry stands in for the Phase 4 attendant swipe-up overlay.
//
// Wiring notes:
//  - Price guard (CanStartTransactionUseCase) blocks any new transaction when koboPerLitre is unset.
//    A debug-build default config is seeded on first launch so flows are playable; real
//    operator-push lands in Phase 4 (debug screen) / Phase 6 (backend). pricePerLitre is also
//    surfaced on CustomerUiState so the cash-fixed entry screen can render it pre-guard.
//  - PaymentProcessor.process emits Pending then Success/Failed. Success drives Pre-pay → Dispensing.
//  - 5-min QR expiry (spec) is enforced by a per-state countdown coroutine.
//  - Cash-fixed cutoff is computed via DeviceConfig.litresCutoff(amountKobo) — floors to 0.01L
//    per the state-machine invariant ("never dispense more than was paid").
//  - Pulse counts come from the injected PulseSource; the mock generates ~50 pps when the
//    relay is open. Litres are derived at 100 pulses/L (matches the prior mock; see
//    OPEN_QUESTIONS #1 for production confirmation).
//  - Persistence (PulseRepository) plumbing is intentionally NOT wired here — Phase 5 handles
//    boot-time resume so we keep the customer VM small until then.
//
// Fill-up cash/digital + USSD still fall through to NotYetImplementedScreen in CustomerStateHost;
// they land in 3d–3f.
package app.balancee.smartpump.display.ui.customer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.balancee.smartpump.display.domain.hardware.PulseSource
import app.balancee.smartpump.display.domain.hardware.RelayController
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.PaymentResult
import app.balancee.smartpump.display.domain.model.PulseMessage
import app.balancee.smartpump.display.domain.model.Transaction
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.model.TransactionState
import app.balancee.smartpump.display.domain.payment.PaymentProcessor
import app.balancee.smartpump.display.domain.repository.DeviceConfigRepository
import app.balancee.smartpump.display.domain.repository.TransactionRepository
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

/** Wraps the canonical [TransactionState] with view-only fields the host screens read. */
data class CustomerUiState(
    val state: TransactionState = TransactionState.Idle,
    val prepayExpiresInSeconds: Int = 0,
    val pricePerLitre: Int = 0,
)

@HiltViewModel
class CustomerViewModel @Inject constructor(
    private val canStartTransaction: CanStartTransactionUseCase,
    private val deviceConfigRepository: DeviceConfigRepository,
    private val paymentProcessor: PaymentProcessor,
    private val pulseSource: PulseSource,
    private val relay: RelayController,
    private val transactions: TransactionRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(CustomerUiState())
    val ui: StateFlow<CustomerUiState> = _ui.asStateFlow()

    private var paymentJob: Job? = null
    private var expiryJob: Job? = null
    private var dispenseJob: Job? = null
    private var pricePerLitre: Int = 0

    init {
        viewModelScope.launch {
            seedDefaultConfigIfMissing()
            // Surface the seeded price so the cash-fixed entry screen can show it
            // before the customer-side price guard runs.
            deviceConfigRepository.getConfig()?.let { config ->
                pricePerLitre = (config.koboPerLitre / 100).toInt()
                _ui.update { it.copy(pricePerLitre = pricePerLitre) }
            }
        }
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

    // ---- Cash fixed (Flow 4) ----
    //
    // Entry point stands in for the Phase 4 attendant swipe-up overlay. The keypad
    // screen carries the same price-guard contract as customer-initiated flows.

    fun onAttendantCashFixed() {
        if (currentState() !is TransactionState.Idle) return
        viewModelScope.launch {
            when (val result = canStartTransaction()) {
                is CanStartTransactionUseCase.Result.Allowed -> {
                    pricePerLitre = (result.config.koboPerLitre / 100).toInt()
                    _ui.update { it.copy(pricePerLitre = pricePerLitre) }
                    setState(TransactionState.CashFixedAmountEntry)
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

    fun onCashFixedAuthorise(cashAmountNaira: Int) {
        if (currentState() !is TransactionState.CashFixedAmountEntry) return
        if (pricePerLitre <= 0) {
            setState(
                TransactionState.Error(
                    message = "Price not set — contact operator.",
                    recoverable = true,
                )
            )
            return
        }
        val amountKobo = cashAmountNaira.toLong() * 100
        viewModelScope.launch {
            val cutoff = deviceConfig()?.litresCutoff(amountKobo)
                ?: (Math.floor((cashAmountNaira.toDouble() / pricePerLitre) * 100.0) / 100.0)
            if (cutoff <= 0.0) {
                setState(
                    TransactionState.Error(
                        message = "Amount is below the minimum dispense (₦${pricePerLitre / 100}).",
                        recoverable = true,
                    )
                )
                return@launch
            }
            cancelInFlightJobs()
            val txnId = generateCashTxnId()
            setState(
                TransactionState.CashFixedDispensing(
                    txnId = txnId,
                    pricePerLitre = pricePerLitre,
                    cashAmountNaira = cashAmountNaira,
                    litresCutoff = cutoff,
                    litresSoFar = 0.0,
                )
            )
            startCashFixedDispensing(cutoff, cashAmountNaira, txnId)
        }
    }

    private fun startCashFixedDispensing(
        litresCutoff: Double,
        cashAmountNaira: Int,
        txnId: String,
    ) {
        dispenseJob?.cancel()
        dispenseJob = viewModelScope.launch {
            relay.startFuelFlow()
            try {
                pulseSource.observe().collect { msg ->
                    if (msg !is PulseMessage.Pulse) return@collect
                    val current = currentState() as? TransactionState.CashFixedDispensing
                        ?: return@collect
                    val litres = msg.count.toDouble() / PULSES_PER_LITRE
                    if (litres >= litresCutoff) {
                        relay.stopFuelFlow()
                        completeAndRecord(
                            TransactionState.Complete(
                                flow = TransactionFlow.CASH_FIXED,
                                txnId = txnId,
                                litres = litresCutoff,
                                amountNaira = cashAmountNaira,
                                method = null,
                            )
                        )
                        return@collect
                    }
                    setState(current.copy(litresSoFar = litres))
                }
            } finally {
                relay.stopFuelFlow()
            }
        }
    }

    private fun generateCashTxnId(): String =
        "BLC-${System.currentTimeMillis().toString().takeLast(5)}"

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
            relay.startFuelFlow()
            try {
                pulseSource.observe().collect { msg ->
                    if (msg !is PulseMessage.Pulse) return@collect
                    val current = currentState() as? TransactionState.FixedDispensing
                        ?: return@collect
                    val litres = msg.count.toDouble() / PULSES_PER_LITRE
                    if (litres >= litresAuthorised) {
                        relay.stopFuelFlow()
                        completeAndRecord(
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
                relay.stopFuelFlow()
            }
        }
    }

    // ---- Cancel / dismiss ----

    fun onCancel() {
        cancelInFlightJobs()
        viewModelScope.launch { relay.stopFuelFlow() }
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

    /**
     * Show the Complete screen and persist the audit row. The audit write is best-effort —
     * the customer already received fuel, so a disk-write failure must not block the UI.
     * WorkManager-driven backend sync (Phase 6) will reconcile if it ever sees drift.
     */
    private suspend fun completeAndRecord(complete: TransactionState.Complete) {
        setState(complete)
        try {
            transactions.saveTransaction(complete.toAuditRecord(pricePerLitre))
        } catch (t: Throwable) {
            android.util.Log.e("CustomerVM", "Failed to persist transaction ${complete.txnId}", t)
        }
    }

    private fun TransactionState.Complete.toAuditRecord(pricePerLitre: Int): Transaction =
        Transaction(
            id = txnId,
            flow = flow,
            paymentMethod = method,
            litresDispensed = litres,
            amountKobo = amountNaira.toLong() * 100,
            priceKoboPerLitre = pricePerLitre.toLong() * 100,
            transactionRef = txnId,
            attendantId = attendantId,
            attendantNote = null,
        )

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
