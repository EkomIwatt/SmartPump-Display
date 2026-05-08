// Central state machine for the customer-side flow. Single source of truth driving
// CustomerStateHost. Owns:
//  - the TransactionState transitions for the entire customer journey
//  - relay open/close on dispense start/end
//  - PaymentProcessor lifecycle (started in AwaitingPayment, cancelled if customer cancels)
//  - live pulse → litres/amount conversion during Dispensing
//  - FILL_UP nozzle-shutoff detection (no pulses for >3 s after at least one was seen)
//  - state persistence on every transition so a power cut mid-transaction can recover
package app.balancee.smartpump.display.ui.screens.customer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.balancee.smartpump.display.domain.hardware.PulseSource
import app.balancee.smartpump.display.domain.hardware.RelayController
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.PaymentResult
import app.balancee.smartpump.display.domain.model.PulseMessage
import app.balancee.smartpump.display.domain.model.Transaction
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import app.balancee.smartpump.display.domain.payment.PaymentProcessor
import app.balancee.smartpump.display.domain.repository.DeviceConfigRepository
import app.balancee.smartpump.display.domain.repository.PulseRepository
import app.balancee.smartpump.display.domain.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltViewModel
class CustomerViewModel @Inject constructor(
    private val deviceConfigRepository: DeviceConfigRepository,
    private val pulseRepository: PulseRepository,
    private val transactionRepository: TransactionRepository,
    pulseSource: PulseSource,
    private val relay: RelayController,
    private val paymentProcessor: PaymentProcessor,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        CustomerUiState(state = TransactionState.Idle, config = FALLBACK_CONFIG),
    )
    val ui: StateFlow<CustomerUiState> = _ui.asStateFlow()

    private var paymentJob: Job? = null
    private var shutoffJob: Job? = null
    private var lastPulseAtMs: Long = 0L
    private var lastSelectedMethod: PaymentMethod? = null

    // Used to label cash-flow transactions which never see a PaymentProcessor ref.
    private val cashRefCounter = AtomicInteger((System.currentTimeMillis() / 1000L).toInt())

    val callbacks = CustomerCallbacks(
        onStart = { goTo(TransactionState.ModeSelect) },
        onSelectMode = ::onSelectMode,
        onConfirmAmount = ::onConfirmAmount,
        onSelectMethod = ::onSelectMethod,
        onCancel = ::cancelTransaction,
        onRetry = { goTo(TransactionState.Idle) },
        onDismiss = { goTo(TransactionState.Idle) },
        onComplete = { goTo(TransactionState.Idle) },
        onBack = ::onBack,
    )

    init {
        // Live config updates from the operator app.
        viewModelScope.launch {
            deviceConfigRepository.observeConfig().collect { cfg ->
                _ui.update { it.copy(config = cfg ?: FALLBACK_CONFIG) }
            }
        }
        // Restore last persisted state and ref so a mid-transaction power cut recovers.
        viewModelScope.launch {
            val restored = pulseRepository.restoreTransactionState()
            val ref = pulseRepository.getActiveTransactionRef()
            _ui.update { it.copy(state = restored, transactionRef = ref) }
            if (restored is TransactionState.Dispensing) {
                relay.open()
                if (restored.mode == TransactionMode.FILL_UP) startShutoffWatcher()
            }
        }
        // Always-on pulse subscription. The handler filters by current state.
        viewModelScope.launch {
            pulseSource.observe().collect(::handlePulse)
        }
    }

    // — Customer transitions ——————————————————————————————————————————————

    private fun onSelectMode(mode: TransactionMode) {
        when (mode) {
            TransactionMode.PRE_PAY -> goTo(TransactionState.AmountSelect(mode = mode))
            TransactionMode.FILL_UP -> goTo(TransactionState.FillUpConfirm(authorisedByAttendant = false))
        }
    }

    private fun onConfirmAmount(amountKobo: Long) {
        if (amountKobo <= 0L) return
        goTo(TransactionState.PaymentMethodSelect(mode = TransactionMode.PRE_PAY, amountKobo = amountKobo))
    }

    private fun onSelectMethod(method: PaymentMethod) {
        val current = _ui.value.state as? TransactionState.PaymentMethodSelect ?: return
        startPayment(mode = current.mode, amountKobo = current.amountKobo, method = method)
    }

    private fun onBack() {
        val state = _ui.value.state
        val target = when (state) {
            is TransactionState.AmountSelect -> TransactionState.ModeSelect
            is TransactionState.PaymentMethodSelect -> TransactionState.AmountSelect(mode = state.mode)
            is TransactionState.ModeSelect -> TransactionState.Idle
            else -> return
        }
        goTo(target)
    }

    private fun cancelTransaction() {
        paymentJob?.cancel()
        paymentJob = null
        shutoffJob?.cancel()
        shutoffJob = null
        viewModelScope.launch { relay.close() }
        _ui.update { it.copy(liveLitres = 0.0, liveAmountKobo = 0L, transactionRef = null, qrPayload = null) }
        goTo(TransactionState.Idle)
    }

    // — Payment lifecycle ——————————————————————————————————————————————————

    private fun startPayment(mode: TransactionMode, amountKobo: Long, method: PaymentMethod) {
        lastSelectedMethod = method
        goTo(TransactionState.AwaitingPayment(mode = mode, amountKobo = amountKobo, method = method))
        paymentJob?.cancel()
        paymentJob = viewModelScope.launch {
            paymentProcessor.process(method, amountKobo).collect { result ->
                when (result) {
                    is PaymentResult.Pending -> _ui.update {
                        it.copy(
                            transactionRef = result.transactionRef,
                            qrPayload = qrPayloadFor(method, result.transactionRef, amountKobo, _ui.value.config),
                        )
                    }
                    is PaymentResult.Success -> onPaymentSuccess(mode, result)
                    is PaymentResult.Failed -> goTo(
                        TransactionState.Error(message = result.reason, recoverable = true),
                    )
                }
            }
        }
    }

    private fun onPaymentSuccess(mode: TransactionMode, result: PaymentResult.Success) {
        val cfg = _ui.value.config
        val litresAuthorised = cfg.litresCutoff(result.amountKobo)
        val next = TransactionState.Dispensing(
            mode = mode,
            amountKobo = result.amountKobo,
            litresAuthorised = litresAuthorised,
        )
        _ui.update { it.copy(transactionRef = result.transactionRef, liveLitres = 0.0, liveAmountKobo = 0L) }
        goTo(next)
        viewModelScope.launch { relay.open() }
        lastPulseAtMs = System.currentTimeMillis()
    }

    // — Pulse handling —————————————————————————————————————————————————————

    private fun handlePulse(msg: PulseMessage) {
        when (msg) {
            is PulseMessage.Pulse -> onPulse(msg)
            is PulseMessage.Heartbeat -> Unit
            is PulseMessage.ParseError -> Unit
            PulseMessage.Disconnected -> {
                if (_ui.value.state is TransactionState.Dispensing) {
                    viewModelScope.launch { relay.close() }
                    goTo(
                        TransactionState.Error(
                            message = "Pulse adapter disconnected — tell attendant",
                            recoverable = true,
                        ),
                    )
                }
            }
        }
    }

    private fun onPulse(pulse: PulseMessage.Pulse) {
        // First pulse during an authorised fill-up promotes the state to Dispensing and
        // arms the shutoff watcher. Subsequent pulses just update the running totals.
        val current = _ui.value.state
        if (current is TransactionState.FillUpConfirm && current.authorisedByAttendant) {
            goTo(
                TransactionState.Dispensing(
                    mode = TransactionMode.FILL_UP,
                    amountKobo = null,
                    litresAuthorised = null,
                ),
            )
            startShutoffWatcher()
        }

        val state = _ui.value.state as? TransactionState.Dispensing ?: return
        val cfg = _ui.value.config
        val litres = pulse.count * LITRES_PER_PULSE
        val amount = (litres * cfg.koboPerLitre).toLong()
        lastPulseAtMs = pulse.timestampMs
        _ui.update { it.copy(liveLitres = litres, liveAmountKobo = amount) }
        viewModelScope.launch { pulseRepository.savePulseCount(pulse.count, pulse.timestampMs) }

        if (state.mode == TransactionMode.PRE_PAY) {
            val cutoff = state.litresAuthorised
            if (cutoff != null && litres >= cutoff) completePrePay(state, litres, amount)
        }
    }

    private fun completePrePay(state: TransactionState.Dispensing, litres: Double, amountKobo: Long) {
        viewModelScope.launch { relay.close() }
        val ref = _ui.value.transactionRef ?: nextCashRef()
        val txn = Transaction(
            id = ref,
            mode = state.mode,
            paymentMethod = lastSelectedMethod ?: PaymentMethod.BALANCEE_APP,
            litresDispensed = litres,
            amountKobo = state.amountKobo ?: amountKobo,
            priceKoboPerLitre = _ui.value.config.koboPerLitre,
            transactionRef = ref,
        )
        viewModelScope.launch { transactionRepository.saveTransaction(txn) }
        goTo(
            TransactionState.Complete(
                transactionId = ref,
                litres = litres,
                amountKobo = state.amountKobo ?: amountKobo,
            ),
        )
    }

    // — Attendant actions ——————————————————————————————————————————————————

    /**
     * Attendant taps FILL UP AUTHORISE on the overlay. Opens the relay and flips
     * FillUpConfirm.authorisedByAttendant true so the customer screen shows "Ready,
     * lift the nozzle". The first incoming pulse transitions to Dispensing (handled
     * in [onPulse]).
     */
    fun onAttendantAuthoriseFillUp() {
        val state = _ui.value.state as? TransactionState.FillUpConfirm ?: return
        if (state.authorisedByAttendant) return
        goTo(TransactionState.FillUpConfirm(authorisedByAttendant = true))
        viewModelScope.launch { relay.open() }
        lastPulseAtMs = System.currentTimeMillis()
    }

    /** Attendant taps CASH RECEIVED. Saves the completed transaction. */
    fun onAttendantConfirmCash() {
        val state = _ui.value.state as? TransactionState.AwaitingCashConfirm ?: return
        val ref = _ui.value.transactionRef ?: nextCashRef()
        val txn = Transaction(
            id = ref,
            mode = TransactionMode.FILL_UP,
            paymentMethod = PaymentMethod.CASH,
            litresDispensed = state.litresDispensed,
            amountKobo = state.amountDueKobo,
            priceKoboPerLitre = _ui.value.config.koboPerLitre,
            transactionRef = ref,
        )
        viewModelScope.launch { transactionRepository.saveTransaction(txn) }
        goTo(
            TransactionState.Complete(
                transactionId = ref,
                litres = state.litresDispensed,
                amountKobo = state.amountDueKobo,
            ),
        )
    }

    /** Attendant force-cancels whatever is in flight. */
    fun onAttendantCancel() = cancelTransaction()

    // — FILL_UP shutoff watchdog ——————————————————————————————————————————

    private fun startShutoffWatcher() {
        shutoffJob?.cancel()
        shutoffJob = viewModelScope.launch {
            var sawAnyPulse = false
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                val state = _ui.value.state
                if (state !is TransactionState.Dispensing || state.mode != TransactionMode.FILL_UP) return@launch
                if (_ui.value.liveLitres > 0.0) sawAnyPulse = true
                val idleMs = System.currentTimeMillis() - lastPulseAtMs
                if (sawAnyPulse && idleMs >= SHUTOFF_THRESHOLD_MS) {
                    onFillUpShutoff()
                    return@launch
                }
            }
        }
    }

    private fun onFillUpShutoff() {
        val cfg = _ui.value.config
        val litres = _ui.value.liveLitres
        val amount = (litres * cfg.koboPerLitre).toLong()
        viewModelScope.launch { relay.close() }
        if (_ui.value.transactionRef == null) {
            _ui.update { it.copy(transactionRef = nextCashRef()) }
        }
        goTo(TransactionState.AwaitingCashConfirm(litresDispensed = litres, amountDueKobo = amount))
    }

    // — Helpers ——————————————————————————————————————————————————————————

    private fun goTo(next: TransactionState) {
        val ref = _ui.value.transactionRef
        _ui.update { it.copy(state = next) }
        viewModelScope.launch { pulseRepository.saveTransactionState(next, ref) }
    }

    private fun nextCashRef(): String = "BLC-%05d".format(cashRefCounter.incrementAndGet() % 100_000)

    private fun qrPayloadFor(
        method: PaymentMethod,
        ref: String,
        amountKobo: Long,
        cfg: DeviceConfig,
    ): String? = when (method) {
        PaymentMethod.BALANCEE_APP -> "balancee://pay?ref=$ref&amount=$amountKobo"
        PaymentMethod.BANK_QR -> "NIP|${cfg.virtualAccountNumber ?: "0000000000"}|$amountKobo|$ref"
        else -> null
    }

    private companion object {
        // 100 pulses per litre — typical fuel-meter tap. Matches the MockPulseSource defaults.
        const val LITRES_PER_PULSE = 0.01

        // FILL_UP shutoff fires after this many ms with no new pulses.
        const val SHUTOFF_THRESHOLD_MS = 3_000L
        const val WATCHDOG_TICK_MS = 500L

        val FALLBACK_CONFIG = DeviceConfig(koboPerLitre = 87_000L)
    }
}
