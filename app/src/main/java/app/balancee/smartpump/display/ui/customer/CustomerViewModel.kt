// Customer-side state machine. Phases 3b–3f wired the five flows; Phase 4 lifted the
// attendant actions into the swipe-up overlay; Phase 5 adds persistence + boot resume.
//
// Money note: prices and amounts are carried as KOBO (Long) end-to-end so a sub-naira
// fuel price (e.g. 87_050 = ₦870.50/L) is never truncated. Customer-typed entry stays in
// whole naira at the screen→VM boundary (amount tiles, cash keypad) and is multiplied to
// kobo here; everything stored on TransactionState and the audit row is kobo. Render with
// ui/util/formatNaira(kobo).
//
// Persistence rules (matching docs/state-machine.md):
//  - Every state transition writes the new state to Room via PulseRepository. Writes are
//    funnelled through a CONFLATED channel + a single writer coroutine so rapid transitions
//    can never persist out of order. The body of the dispensing loop also throttles a
//    pulse-count write every PULSE_PERSIST_EVERY_N pulses so a power-cut mid-fill can
//    reconstruct litresSoFar.
//  - On VM construction we first force RelayController.stopFuelFlow() — the spec invariant
//    "relay defaults OPEN on boot" must hold before we re-derive from state. Then we read
//    the persisted state and dispatch:
//      • Terminal (Complete / non-recoverable Error) → reset to Idle + clear.
//      • Pure-UI states (pickers, FillupTankFull, FillupAwaitingCashConfirm, etc.)
//        → just dispatch; no side-effect jobs needed.
//      • Waiting states (Prepay/Ussd/FillupDigital awaiting) → restart the countdown
//        and the payment listener using the persisted state's amount + method.
//      • Dispensing states → restore pulseBaseline from disk, dispatch, and restart the
//        relay + pulse collector. The collector's cumulative count is `pulseBaseline +
//        mockMsg.count`, so a clean reboot or a fresh transaction both work.
//
// Wiring notes (carried from earlier phases):
//  - Price guard (CanStartTransactionUseCase) blocks any new transaction when koboPerLitre
//    is unset. A debug-build default config is seeded on first launch so flows are playable;
//    the Phase 4b debug screen now exposes live overrides for testing.
//  - PaymentProcessor.process emits Pending then Success/Failed; Success drives Pre-pay →
//    Dispensing. Phase 4b added an "SMS arrived" injector that bypasses the pending delay.
//  - Cash-fixed cutoff is computed via DeviceConfig.litresCutoff(amountKobo) — floored to
//    0.01L per state-machine invariant ("never dispense more than was paid").
//  - Pulse counts come from the injected PulseSource; the mock generates ~50 pps when the
//    relay is open. Litres are derived at 100 pulses/L (see OPEN_QUESTIONS #1).
package app.balancee.smartpump.display.ui.customer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.balancee.smartpump.display.BuildConfig
import app.balancee.smartpump.display.domain.hardware.PulseSource
import app.balancee.smartpump.display.domain.hardware.RelayController
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.FuelType
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.PaymentResult
import app.balancee.smartpump.display.domain.model.PostFillIntent
import app.balancee.smartpump.display.domain.model.PulseMessage
import app.balancee.smartpump.display.domain.model.Transaction
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import app.balancee.smartpump.display.domain.payment.PaymentProcessor
import app.balancee.smartpump.display.domain.repository.DeviceConfigRepository
import app.balancee.smartpump.display.domain.repository.PulseRepository
import app.balancee.smartpump.display.domain.repository.TransactionRepository
import app.balancee.smartpump.display.domain.usecase.CanStartTransactionUseCase
import app.balancee.smartpump.display.ui.util.formatNaira
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val PULSES_PER_LITRE = 100
private const val PREPAY_EXPIRY_SECONDS = 5 * 60
private const val FILLUP_DIGITAL_EXPIRY_SECONDS = 5 * 60
private const val USSD_SMS_TIMEOUT_SECONDS = 5 * 60
private const val FILLUP_SHUTOFF_TIMEOUT_MS = 3_000L
private const val FILLUP_WATCHDOG_POLL_MS = 500L

/**
 * Persist pulse count every N pulses during a dispense — at 100 pulses/L this is one
 * write every 0.25L (~25 writes for a full 10L pre-pay). Frequent enough that a
 * power-cut resume reconstructs litresSoFar within ±0.25L, cheap enough not to thrash
 * the SD card on the kiosk.
 */
private const val PULSE_PERSIST_EVERY_N = 25

/** Wraps the canonical [TransactionState] with view-only fields the host screens read. */
data class CustomerUiState(
    val state: TransactionState = TransactionState.Idle,
    val prepayExpiresInSeconds: Int = 0,
    val fillupDigitalExpiresInSeconds: Int = 0,
    val ussdExpiresInSeconds: Int = 0,
    val priceKoboPerLitre: Long = 0L,
)

@HiltViewModel
class CustomerViewModel @Inject constructor(
    private val canStartTransaction: CanStartTransactionUseCase,
    private val deviceConfigRepository: DeviceConfigRepository,
    private val paymentProcessor: PaymentProcessor,
    private val pulseSource: PulseSource,
    private val pulseRepository: PulseRepository,
    private val relay: RelayController,
    private val transactions: TransactionRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(CustomerUiState())
    val ui: StateFlow<CustomerUiState> = _ui.asStateFlow()

    private var paymentJob: Job? = null
    private var expiryJob: Job? = null
    private var dispenseJob: Job? = null
    private var fillupWatchdogJob: Job? = null
    private var priceKoboPerLitre: Long = 0L

    /**
     * Cumulative pulses persisted from the prior session, applied as a baseline to
     * resumed dispensing flows. The mock pulse source resets its own count to 0 each
     * time the relay re-opens, so the cumulative for litres = baseline + msg.count.
     * Zero on a fresh dispense; non-zero only on power-cut resume.
     */
    private var pulseBaseline: Int = 0

    /**
     * CONFLATED state-write channel — only the latest pending state survives queuing, so
     * rapid transitions can't race the writer into out-of-order disk writes.
     */
    private val stateWriteChannel = Channel<TransactionState>(capacity = Channel.CONFLATED)

    init {
        // Serial writer coroutine — every setState() funnels its state in here.
        viewModelScope.launch {
            for (state in stateWriteChannel) {
                runCatching {
                    pulseRepository.saveTransactionState(state, txnRefFor(state))
                }.onFailure {
                    android.util.Log.e("CustomerVM", "Failed to persist state", it)
                }
            }
        }
        // Boot sequence: relay-open invariant, config seed, then state resume.
        viewModelScope.launch {
            // Spec invariant: relay must default OPEN on boot — assert it before re-deriving.
            relay.stopFuelFlow()
            seedDefaultConfigIfMissing()
            deviceConfigRepository.getConfig()?.let { config ->
                priceKoboPerLitre = config.koboPerLitre
                _ui.update { it.copy(priceKoboPerLitre = priceKoboPerLitre) }
            }
            bootResume()
        }
    }

    // ---- Boot resume ---------------------------------------------------------------

    private suspend fun bootResume() {
        val restored = pulseRepository.restoreTransactionState()
        val restoredPulses = pulseRepository.restorePulseCount()
        when (restored) {
            is TransactionState.Idle,
            is TransactionState.ModeSelect,
            is TransactionState.FillupAwaitingAttendantAuth,
            is TransactionState.FillupTankFull,
            is TransactionState.FillupAwaitingCashConfirm,
            is TransactionState.CashFixedAmountEntry -> {
                // Pure-UI: just dispatch. No side-effect coroutines needed.
                setState(restored)
            }

            is TransactionState.Error -> {
                if (restored.recoverable) setState(restored)
                else resetToIdle(clearPulses = true)
            }

            is TransactionState.Complete -> {
                // Customer never tapped "Done" before the power cut. Treat as terminal:
                // reset to Idle. The audit row was already written when Complete first set.
                resetToIdle(clearPulses = true)
            }

            is TransactionState.PrepayAwaitingPayment -> {
                setState(restored)
                startExpiryCountdown()
                resumePrepayPaymentListener(restored)
            }

            is TransactionState.UssdAwaitingSms -> {
                setState(restored)
                startUssdExpiry()
                startUssdSmsListener(
                    amountKobo = restored.amountKobo,
                    txnId = restored.txnId,
                )
            }

            is TransactionState.FillupDigitalAwaitingPayment -> {
                setState(restored)
                // Reconstruct the FillupTankFull source the digital handlers close over.
                // priceKoboPerLitre persisted via DeviceConfig is preferred — but for fidelity
                // to the snapshot, derive from amountDueKobo / verifiedLitres which were
                // locked at TankFull time and survive any subsequent price change.
                val derivedPriceKobo = if (restored.verifiedLitres > 0) {
                    Math.round(restored.amountDueKobo / restored.verifiedLitres)
                } else priceKoboPerLitre
                val source = TransactionState.FillupTankFull(
                    txnId = restored.txnId,
                    priceKoboPerLitre = derivedPriceKobo,
                    verifiedLitres = restored.verifiedLitres,
                    amountDueKobo = restored.amountDueKobo,
                )
                startFillupDigitalExpiry(source)
                startFillupDigitalPayment(source)
            }

            is TransactionState.FixedDispensing -> {
                pulseBaseline = restoredPulses
                setState(restored)
                val method = restored.method ?: deriveMethodForFlow(restored.flow)
                startDispensing(restored.litresAuthorised, method)
            }

            is TransactionState.CashFixedDispensing -> {
                pulseBaseline = restoredPulses
                setState(restored)
                startCashFixedDispensing(
                    litresCutoff = restored.litresCutoff,
                    cashAmountKobo = restored.cashAmountKobo,
                    txnId = restored.txnId,
                )
            }

            is TransactionState.FillupDispensing -> {
                pulseBaseline = restoredPulses
                setState(restored)
                startFillupDispensing(restored.txnId)
            }
        }
    }

    private fun resetToIdle(clearPulses: Boolean) {
        setState(TransactionState.Idle)
        if (clearPulses) {
            viewModelScope.launch {
                runCatching { pulseRepository.savePulseCount(0, 0L) }
            }
        }
        pulseBaseline = 0
    }

    private fun deriveMethodForFlow(flow: TransactionFlow): PaymentMethod? = when (flow) {
        // Resume fidelity: when the original method choice is lost (older persisted blob
        // without the FixedDispensing.method field), fall back to the most likely channel
        // per flow. Audit row may show BANK_QR_TRANSFER for what was actually BALANCEE_APP;
        // backend reconciliation in Phase 7 corrects via the webhook trail.
        TransactionFlow.FIXED_PREPAY_DIGITAL -> PaymentMethod.BANK_QR_TRANSFER
        TransactionFlow.USSD_OFFLINE -> PaymentMethod.USSD
        TransactionFlow.CASH_FIXED -> null
        TransactionFlow.FILLUP_CASH -> null
        TransactionFlow.FILLUP_DIGITAL -> PaymentMethod.BANK_QR_TRANSFER
    }

    /** Returns the txn ref (BLC-NNNNN) embedded in the state, or null for stateless variants. */
    private fun txnRefFor(state: TransactionState): String? = when (state) {
        is TransactionState.PrepayAwaitingPayment -> state.txnId
        is TransactionState.UssdAwaitingSms -> state.txnId
        is TransactionState.FixedDispensing -> state.txnId
        is TransactionState.CashFixedDispensing -> state.txnId
        is TransactionState.FillupDispensing -> state.txnId
        is TransactionState.FillupTankFull -> state.txnId
        is TransactionState.FillupDigitalAwaitingPayment -> state.txnId
        is TransactionState.FillupAwaitingCashConfirm -> state.txnId
        is TransactionState.Complete -> state.txnId
        else -> null
    }

    // ---- Idle / ModeSelect ---------------------------------------------------------

    fun onStartTransaction() {
        if (currentState() !is TransactionState.Idle) return
        viewModelScope.launch {
            when (val result = canStartTransaction()) {
                is CanStartTransactionUseCase.Result.Allowed -> {
                    priceKoboPerLitre = result.config.koboPerLitre
                    setState(TransactionState.ModeSelect())
                }
                is CanStartTransactionUseCase.Result.NotConfigured -> {
                    setState(
                        TransactionState.Error(
                            message = CanStartTransactionUseCase.CUSTOMER_MESSAGE,
                            recoverable = true,
                        )
                    )
                }
            }
        }
    }

    /**
     * Tile-tap handlers on the unified ModeSelect screen. Each one updates the
     * current ModeSelect state in place; on switching to FILL_UP we clear any
     * lingering amount/method choices so the visible UI matches the new mode.
     */
    fun onModeTileTap(mode: TransactionMode) {
        val current = currentState() as? TransactionState.ModeSelect ?: return
        setState(
            current.copy(
                mode = mode,
                // FILL_UP doesn't take a customer-side amount or method — clear them.
                amountKobo = if (mode == TransactionMode.FILL_UP) null else current.amountKobo,
                method = if (mode == TransactionMode.FILL_UP) null else current.method,
            )
        )
    }

    /** Amount tiles are whole-naira; store as kobo. */
    fun onAmountTileTap(amountNaira: Int) {
        val current = currentState() as? TransactionState.ModeSelect ?: return
        if (current.mode != TransactionMode.PRE_PAY) return
        setState(current.copy(amountKobo = amountNaira.toLong() * 100))
    }

    fun onMethodTileTap(method: PaymentMethod) {
        val current = currentState() as? TransactionState.ModeSelect ?: return
        if (current.mode != TransactionMode.PRE_PAY) return
        setState(current.copy(method = method))
    }

    /**
     * Commit the ModeSelect choices. PRE_PAY routes through the same payment paths the
     * old PrepayMethodSelect screen used (USSD → USSD flow; cash → cancel back to Idle;
     * everything else → digital pre-pay). FILL_UP transitions straight to
     * FillupAwaitingAttendantAuth.
     */
    fun onModeConfirm() {
        val current = currentState() as? TransactionState.ModeSelect ?: return
        when (current.mode) {
            TransactionMode.FILL_UP ->
                setState(TransactionState.FillupAwaitingAttendantAuth())

            TransactionMode.PRE_PAY -> {
                val amountKobo = current.amountKobo ?: return
                val method = current.method ?: return
                when (method) {
                    PaymentMethod.CASH_SEE_ATTENDANT -> onCancel()
                    PaymentMethod.USSD -> startUssdFlow(amountKobo = amountKobo)
                    else -> startPrepayPayment(amountKobo = amountKobo, method = method)
                }
            }

            null -> Unit
        }
    }

    // ---- Fill-up cash (Flow 2) -----------------------------------------------------

    /**
     * Phase 6d — customer pre-declares their post-shutoff payment intent on the
     * FILL UP confirm screen. Advisory only: the choice is captured on the state so
     * boot-resume preserves it, but the actual routing still happens at FillupTankFull.
     */
    fun onFillupSelectIntent(intent: PostFillIntent) {
        val current = currentState() as? TransactionState.FillupAwaitingAttendantAuth ?: return
        setState(current.copy(intent = intent))
    }

    fun onAttendantFillUpAuthorise() {
        val current = currentState()
        if (current !is TransactionState.Idle && current !is TransactionState.FillupAwaitingAttendantAuth) {
            return
        }
        viewModelScope.launch {
            when (val result = canStartTransaction()) {
                is CanStartTransactionUseCase.Result.Allowed -> {
                    priceKoboPerLitre = result.config.koboPerLitre
                    _ui.update { it.copy(priceKoboPerLitre = priceKoboPerLitre) }
                    val txnId = generateCashTxnId()
                    cancelInFlightJobs()
                    pulseBaseline = 0
                    setState(
                        TransactionState.FillupDispensing(
                            txnId = txnId,
                            priceKoboPerLitre = priceKoboPerLitre,
                            litresSoFar = 0.0,
                        )
                    )
                    startFillupDispensing(txnId)
                }
                is CanStartTransactionUseCase.Result.NotConfigured -> {
                    setState(
                        TransactionState.Error(
                            message = CanStartTransactionUseCase.CUSTOMER_MESSAGE,
                            recoverable = true,
                        )
                    )
                }
            }
        }
    }

    private fun startFillupDispensing(txnId: String) {
        dispenseJob?.cancel()
        fillupWatchdogJob?.cancel()
        // viewModelScope is Main-confined, so a local Long mutated by both the pulse
        // coroutine and the watchdog coroutine is safe without synchronisation.
        var lastPulseMs = 0L
        var lastPersistAtPulses = pulseBaseline

        dispenseJob = viewModelScope.launch {
            relay.startFuelFlow()
            try {
                pulseSource.observe().collect { msg ->
                    if (msg !is PulseMessage.Pulse) return@collect
                    lastPulseMs = msg.timestampMs
                    val current = currentState() as? TransactionState.FillupDispensing
                        ?: return@collect
                    val cumulativePulses = pulseBaseline + msg.count
                    val litres = cumulativePulses.toDouble() / PULSES_PER_LITRE
                    setState(current.copy(litresSoFar = litres))
                    if (cumulativePulses - lastPersistAtPulses >= PULSE_PERSIST_EVERY_N) {
                        lastPersistAtPulses = cumulativePulses
                        runCatching {
                            pulseRepository.savePulseCount(cumulativePulses, msg.timestampMs)
                        }
                    }
                }
            } finally {
                relay.stopFuelFlow()
            }
        }

        fillupWatchdogJob = viewModelScope.launch {
            while (true) {
                delay(FILLUP_WATCHDOG_POLL_MS)
                val current = currentState() as? TransactionState.FillupDispensing ?: break
                val now = System.currentTimeMillis()
                if (lastPulseMs > 0L && (now - lastPulseMs) > FILLUP_SHUTOFF_TIMEOUT_MS) {
                    fillupShutoff(current)
                    break
                }
            }
        }
    }

    /**
     * Locks the verified litre count and moves FillupDispensing → FillupTankFull. Called by the
     * 3-second pulse-timeout watchdog when the real nozzle shuts, and by [onSimulateNozzleShutoff]
     * for demos on the mock stack. Cancels the pulse collector; the relay is de-energised first.
     */
    private suspend fun fillupShutoff(current: TransactionState.FillupDispensing) {
        relay.stopFuelFlow()
        val verifiedLitres = current.litresSoFar
        val amountDueKobo = Math.round(verifiedLitres * current.priceKoboPerLitre)
        setState(
            TransactionState.FillupTankFull(
                txnId = current.txnId,
                priceKoboPerLitre = current.priceKoboPerLitre,
                verifiedLitres = verifiedLitres,
                amountDueKobo = amountDueKobo,
            )
        )
        dispenseJob?.cancel()
        dispenseJob = null
    }

    /**
     * Manual nozzle-shutoff — the attendant ends an open-ended fill-up on demand from the
     * swipe-up overlay. On real hardware the nozzle auto-shuts and the watchdog catches the
     * flow gap; on the mock stack there is no physical nozzle, so a fill-up would otherwise run
     * until the simulated ~60 L tank fills. This triggers the same FillupTankFull transition the
     * watchdog does — whatever litres have flowed become the verified, billable amount.
     */
    fun onSimulateNozzleShutoff() {
        val current = currentState() as? TransactionState.FillupDispensing ?: return
        viewModelScope.launch {
            fillupWatchdogJob?.cancel()
            fillupWatchdogJob = null
            fillupShutoff(current)
        }
    }

    fun onFillupPayCash() {
        val current = currentState() as? TransactionState.FillupTankFull ?: return
        setState(
            TransactionState.FillupAwaitingCashConfirm(
                txnId = current.txnId,
                verifiedLitres = current.verifiedLitres,
                amountDueKobo = current.amountDueKobo,
            )
        )
    }

    fun onFillupPayDigital() {
        val current = currentState() as? TransactionState.FillupTankFull ?: return
        viewModelScope.launch {
            val account = deviceConfig()?.virtualAccountNumber
                ?: DEFAULT_VIRTUAL_ACCOUNT
            val qrContent = buildNipTransferQr(
                account = account,
                amountKobo = current.amountDueKobo,
                txnId = current.txnId,
            )
            cancelInFlightJobs()
            setState(
                TransactionState.FillupDigitalAwaitingPayment(
                    txnId = current.txnId,
                    verifiedLitres = current.verifiedLitres,
                    amountDueKobo = current.amountDueKobo,
                    qrContent = qrContent,
                )
            )
            startFillupDigitalExpiry(current)
            startFillupDigitalPayment(current)
        }
    }

    private fun startFillupDigitalPayment(source: TransactionState.FillupTankFull) {
        paymentJob?.cancel()
        val amountKobo = source.amountDueKobo
        paymentJob = viewModelScope.launch {
            paymentProcessor.process(PaymentMethod.BANK_QR_TRANSFER, amountKobo).collect { result ->
                when (result) {
                    is PaymentResult.Pending -> Unit
                    is PaymentResult.Success -> onFillupDigitalSuccess(source)
                    is PaymentResult.Failed -> onFillupDigitalFailed(source, result.reason)
                }
            }
        }
    }

    private suspend fun onFillupDigitalSuccess(source: TransactionState.FillupTankFull) {
        if (currentState() !is TransactionState.FillupDigitalAwaitingPayment) return
        expiryJob?.cancel()
        completeAndRecord(
            TransactionState.Complete(
                flow = TransactionFlow.FILLUP_DIGITAL,
                txnId = source.txnId,
                litres = source.verifiedLitres,
                amountKobo = source.amountDueKobo,
                method = PaymentMethod.BANK_QR_TRANSFER,
            )
        )
    }

    private fun onFillupDigitalFailed(source: TransactionState.FillupTankFull, reason: String) {
        if (currentState() !is TransactionState.FillupDigitalAwaitingPayment) return
        expiryJob?.cancel()
        android.util.Log.w("CustomerVM", "Fill-up digital payment failed: $reason")
        setState(
            TransactionState.FillupAwaitingCashConfirm(
                txnId = source.txnId,
                verifiedLitres = source.verifiedLitres,
                amountDueKobo = source.amountDueKobo,
            )
        )
    }

    private fun startFillupDigitalExpiry(source: TransactionState.FillupTankFull) {
        expiryJob?.cancel()
        expiryJob = viewModelScope.launch {
            var remaining = FILLUP_DIGITAL_EXPIRY_SECONDS
            _ui.update { it.copy(fillupDigitalExpiresInSeconds = remaining) }
            while (remaining > 0 && currentState() is TransactionState.FillupDigitalAwaitingPayment) {
                delay(1_000L)
                remaining -= 1
                _ui.update { it.copy(fillupDigitalExpiresInSeconds = remaining) }
            }
            if (remaining <= 0 && currentState() is TransactionState.FillupDigitalAwaitingPayment) {
                paymentJob?.cancel()
                setState(
                    TransactionState.FillupAwaitingCashConfirm(
                        txnId = source.txnId,
                        verifiedLitres = source.verifiedLitres,
                        amountDueKobo = source.amountDueKobo,
                    )
                )
            }
        }
    }

    // NIP transfer amounts are in naira (major units) with 2 dp, derived losslessly from kobo.
    private fun buildNipTransferQr(account: String, amountKobo: Long, txnId: String): String =
        "nip://transfer?account=$account&amount=${"%.2f".format(amountKobo / 100.0)}&ref=$txnId"

    fun onAttendantCashReceived() {
        val current = currentState() as? TransactionState.FillupAwaitingCashConfirm ?: return
        viewModelScope.launch {
            completeAndRecord(
                TransactionState.Complete(
                    flow = TransactionFlow.FILLUP_CASH,
                    txnId = current.txnId,
                    litres = current.verifiedLitres,
                    amountKobo = current.amountDueKobo,
                    method = null,
                )
            )
        }
    }

    // ---- Cash fixed (Flow 4) -------------------------------------------------------

    fun onAttendantCashFixed() {
        if (currentState() !is TransactionState.Idle) return
        viewModelScope.launch {
            when (val result = canStartTransaction()) {
                is CanStartTransactionUseCase.Result.Allowed -> {
                    priceKoboPerLitre = result.config.koboPerLitre
                    _ui.update { it.copy(priceKoboPerLitre = priceKoboPerLitre) }
                    setState(TransactionState.CashFixedAmountEntry)
                }
                is CanStartTransactionUseCase.Result.NotConfigured -> {
                    setState(
                        TransactionState.Error(
                            message = CanStartTransactionUseCase.CUSTOMER_MESSAGE,
                            recoverable = true,
                        )
                    )
                }
            }
        }
    }

    fun onCashFixedAuthorise(cashAmountKobo: Long) {
        if (currentState() !is TransactionState.CashFixedAmountEntry) return
        if (priceKoboPerLitre <= 0L) {
            setState(
                TransactionState.Error(
                    message = "Price not set — contact operator.",
                    recoverable = true,
                )
            )
            return
        }
        viewModelScope.launch {
            val cutoff = deviceConfig()?.litresCutoff(cashAmountKobo)
                ?: (Math.floor((cashAmountKobo.toDouble() / priceKoboPerLitre) * 100.0) / 100.0)
            if (cutoff <= 0.0) {
                // Smallest dispensable step is 0.01 L, i.e. priceKoboPerLitre / 100 kobo.
                setState(
                    TransactionState.Error(
                        message = "Amount is below the minimum dispense (${formatNaira(priceKoboPerLitre / 100)}).",
                        recoverable = true,
                    )
                )
                return@launch
            }
            cancelInFlightJobs()
            pulseBaseline = 0
            val txnId = generateCashTxnId()
            setState(
                TransactionState.CashFixedDispensing(
                    txnId = txnId,
                    priceKoboPerLitre = priceKoboPerLitre,
                    cashAmountKobo = cashAmountKobo,
                    litresCutoff = cutoff,
                    litresSoFar = 0.0,
                )
            )
            startCashFixedDispensing(cutoff, cashAmountKobo, txnId)
        }
    }

    private fun startCashFixedDispensing(
        litresCutoff: Double,
        cashAmountKobo: Long,
        txnId: String,
    ) {
        dispenseJob?.cancel()
        var lastPersistAtPulses = pulseBaseline
        dispenseJob = viewModelScope.launch {
            relay.startFuelFlow()
            try {
                pulseSource.observe().collect { msg ->
                    when (msg) {
                        is PulseMessage.Pulse -> {
                            val current = currentState() as? TransactionState.CashFixedDispensing
                                ?: return@collect
                            val cumulativePulses = pulseBaseline + msg.count
                            val litres = cumulativePulses.toDouble() / PULSES_PER_LITRE
                            if (litres >= litresCutoff) {
                                relay.stopFuelFlow()
                                completeAndRecord(
                                    TransactionState.Complete(
                                        flow = TransactionFlow.CASH_FIXED,
                                        txnId = txnId,
                                        litres = litresCutoff,
                                        amountKobo = cashAmountKobo,
                                        method = null,
                                    )
                                )
                                return@collect
                            }
                            setState(current.copy(litresSoFar = litres))
                            if (cumulativePulses - lastPersistAtPulses >= PULSE_PERSIST_EVERY_N) {
                                lastPersistAtPulses = cumulativePulses
                                runCatching {
                                    pulseRepository.savePulseCount(cumulativePulses, msg.timestampMs)
                                }
                            }
                        }

                        // The USB cable is fixed in the kiosk, so the app no longer models a
                        // disconnect/pause state. Comms-loss safety still holds on the adapter's own
                        // dead-man watchdog (relay fails closed when the PING heartbeat stops); on a
                        // genuine transient the relay controller re-asserts RLY:1 and counting resumes.
                        is PulseMessage.Heartbeat,
                        is PulseMessage.Disconnected,
                        is PulseMessage.ParseError -> Unit
                    }
                }
            } finally {
                relay.stopFuelFlow()
            }
        }
    }

    private fun generateCashTxnId(): String =
        "BLC-${System.currentTimeMillis().toString().takeLast(5)}"

    // ---- USSD offline (Flow 5) ------------------------------------------------------

    private fun startUssdFlow(amountKobo: Long) {
        cancelInFlightJobs()
        val txnRef = generateUssdRef()
        val txnId = generateCashTxnId()
        setState(
            TransactionState.UssdAwaitingSms(
                amountKobo = amountKobo,
                txnRef = txnRef,
                txnId = txnId,
                priceKoboPerLitre = priceKoboPerLitre,
            )
        )
        startUssdExpiry()
        startUssdSmsListener(amountKobo = amountKobo, txnId = txnId)
    }

    private fun startUssdSmsListener(amountKobo: Long, txnId: String) {
        paymentJob?.cancel()
        paymentJob = viewModelScope.launch {
            paymentProcessor.process(PaymentMethod.USSD, amountKobo).collect { result ->
                when (result) {
                    is PaymentResult.Pending -> Unit
                    is PaymentResult.Success -> onUssdSmsConfirmed(amountKobo, txnId)
                    is PaymentResult.Failed -> onUssdFailed(result.reason)
                }
            }
        }
    }

    private suspend fun onUssdSmsConfirmed(amountKobo: Long, txnId: String) {
        if (currentState() !is TransactionState.UssdAwaitingSms) return
        expiryJob?.cancel()
        val litresAuthorised = deviceConfig()?.litresCutoff(amountKobo)
            ?: ((amountKobo.toDouble() / priceKoboPerLitre).coerceAtLeast(0.0))
        pulseBaseline = 0
        setState(
            TransactionState.FixedDispensing(
                flow = TransactionFlow.USSD_OFFLINE,
                txnId = txnId,
                priceKoboPerLitre = priceKoboPerLitre,
                amountKobo = amountKobo,
                litresAuthorised = litresAuthorised,
                litresSoFar = 0.0,
                method = PaymentMethod.USSD,
            )
        )
        startDispensing(litresAuthorised, PaymentMethod.USSD)
    }

    private fun onUssdFailed(reason: String) {
        if (currentState() !is TransactionState.UssdAwaitingSms) return
        expiryJob?.cancel()
        setState(
            TransactionState.Error(
                message = "USSD payment failed — $reason.",
                recoverable = true,
            )
        )
    }

    private fun startUssdExpiry() {
        expiryJob?.cancel()
        expiryJob = viewModelScope.launch {
            var remaining = USSD_SMS_TIMEOUT_SECONDS
            _ui.update { it.copy(ussdExpiresInSeconds = remaining) }
            while (remaining > 0 && currentState() is TransactionState.UssdAwaitingSms) {
                delay(1_000L)
                remaining -= 1
                _ui.update { it.copy(ussdExpiresInSeconds = remaining) }
            }
            if (remaining <= 0 && currentState() is TransactionState.UssdAwaitingSms) {
                paymentJob?.cancel()
                setState(TransactionState.Idle)
                _ui.update { it.copy(ussdExpiresInSeconds = 0) }
            }
        }
    }

    private fun generateUssdRef(): String =
        kotlin.random.Random.nextInt(100, 1000).toString()

    private fun startPrepayPayment(amountKobo: Long, method: PaymentMethod) {
        cancelInFlightJobs()
        paymentJob = viewModelScope.launch {
            paymentProcessor.process(method, amountKobo).collect { result ->
                when (result) {
                    is PaymentResult.Pending -> onPaymentPending(amountKobo, method, result)
                    is PaymentResult.Success -> onPaymentSuccess(amountKobo, method, result)
                    is PaymentResult.Failed -> onPaymentFailed(result)
                }
            }
        }
    }

    /**
     * Restart the prepay payment listener for a state restored from disk. The original
     * Pending event is gone — we go straight back into a fresh [paymentProcessor.process]
     * call carrying the same amount + method and treat its Success as the resumed webhook.
     * The transactionRef on the resumed state stays the in-memory one the customer is
     * looking at; the new Pending event arrives with a fresh backend ref that we ignore.
     */
    private fun resumePrepayPaymentListener(restored: TransactionState.PrepayAwaitingPayment) {
        paymentJob?.cancel()
        val amountKobo = restored.amountKobo
        paymentJob = viewModelScope.launch {
            paymentProcessor.process(restored.method, amountKobo).collect { result ->
                when (result) {
                    is PaymentResult.Pending -> Unit
                    is PaymentResult.Success -> onPaymentSuccess(amountKobo, restored.method, result)
                    is PaymentResult.Failed -> onPaymentFailed(result)
                }
            }
        }
    }

    private fun onPaymentPending(
        amountKobo: Long,
        method: PaymentMethod,
        pending: PaymentResult.Pending,
    ) {
        setState(
            TransactionState.PrepayAwaitingPayment(
                flow = TransactionFlow.FIXED_PREPAY_DIGITAL,
                amountKobo = amountKobo,
                method = method,
                txnId = pending.transactionRef,
                priceKoboPerLitre = priceKoboPerLitre,
            )
        )
        startExpiryCountdown()
    }

    private suspend fun onPaymentSuccess(
        amountKobo: Long,
        method: PaymentMethod,
        success: PaymentResult.Success,
    ) {
        expiryJob?.cancel()
        val litresAuthorised = deviceConfig()?.litresCutoff(success.amountKobo)
            ?: ((amountKobo.toDouble() / priceKoboPerLitre).coerceAtLeast(0.0))

        pulseBaseline = 0
        setState(
            TransactionState.FixedDispensing(
                flow = TransactionFlow.FIXED_PREPAY_DIGITAL,
                txnId = success.transactionRef,
                priceKoboPerLitre = priceKoboPerLitre,
                amountKobo = amountKobo,
                litresAuthorised = litresAuthorised,
                litresSoFar = 0.0,
                method = method,
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

    private fun startDispensing(litresAuthorised: Double, method: PaymentMethod?) {
        dispenseJob?.cancel()
        var lastPersistAtPulses = pulseBaseline
        dispenseJob = viewModelScope.launch {
            relay.startFuelFlow()
            try {
                pulseSource.observe().collect { msg ->
                    when (msg) {
                        is PulseMessage.Pulse -> {
                            val current = currentState() as? TransactionState.FixedDispensing
                                ?: return@collect
                            val cumulativePulses = pulseBaseline + msg.count
                            val litres = cumulativePulses.toDouble() / PULSES_PER_LITRE
                            if (litres >= litresAuthorised) {
                                relay.stopFuelFlow()
                                completeAndRecord(
                                    TransactionState.Complete(
                                        flow = current.flow,
                                        txnId = current.txnId,
                                        litres = litresAuthorised,
                                        amountKobo = current.amountKobo,
                                        method = method,
                                    )
                                )
                                return@collect
                            }
                            setState(current.copy(litresSoFar = litres))
                            if (cumulativePulses - lastPersistAtPulses >= PULSE_PERSIST_EVERY_N) {
                                lastPersistAtPulses = cumulativePulses
                                runCatching {
                                    pulseRepository.savePulseCount(cumulativePulses, msg.timestampMs)
                                }
                            }
                        }

                        // The USB cable is fixed in the kiosk, so the app no longer models a
                        // disconnect/pause state. Comms-loss safety still holds on the adapter's own
                        // dead-man watchdog (relay fails closed when the PING heartbeat stops); on a
                        // genuine transient the relay controller re-asserts RLY:1 and counting resumes.
                        is PulseMessage.Heartbeat,
                        is PulseMessage.Disconnected,
                        is PulseMessage.ParseError -> Unit
                    }
                }
            } finally {
                relay.stopFuelFlow()
            }
        }
    }

    // ---- Cancel / dismiss ----------------------------------------------------------

    fun onCancel() {
        cancelInFlightJobs()
        viewModelScope.launch { relay.stopFuelFlow() }
        resetToIdle(clearPulses = true)
        _ui.update {
            it.copy(
                prepayExpiresInSeconds = 0,
                fillupDigitalExpiresInSeconds = 0,
                ussdExpiresInSeconds = 0,
            )
        }
    }

    fun onShareReceipt() {
        // Wired to a real share sheet in Phase 7. Logged-only for now so the button isn't dead.
    }

    fun onDismissComplete() {
        if (currentState() is TransactionState.Complete) onCancel()
    }

    // ---- Helpers -------------------------------------------------------------------

    private fun currentState(): TransactionState = _ui.value.state

    private fun setState(state: TransactionState) {
        _ui.update { it.copy(state = state) }
        stateWriteChannel.trySend(state)
    }

    private suspend fun completeAndRecord(complete: TransactionState.Complete) {
        setState(complete)
        try {
            transactions.saveTransaction(complete.toAuditRecord(priceKoboPerLitre))
        } catch (t: Throwable) {
            android.util.Log.e("CustomerVM", "Failed to persist transaction ${complete.txnId}", t)
        }
    }

    private fun TransactionState.Complete.toAuditRecord(priceKoboPerLitre: Long): Transaction =
        Transaction(
            id = txnId,
            flow = flow,
            paymentMethod = method,
            litresDispensed = litres,
            amountKobo = amountKobo,
            priceKoboPerLitre = priceKoboPerLitre,
            transactionRef = txnId,
            attendantId = attendantId,
            attendantNote = null,
        )

    private fun cancelInFlightJobs() {
        paymentJob?.cancel()
        expiryJob?.cancel()
        dispenseJob?.cancel()
        fillupWatchdogJob?.cancel()
        paymentJob = null
        expiryJob = null
        dispenseJob = null
        fillupWatchdogJob = null
    }

    private suspend fun seedDefaultConfigIfMissing() {
        if (deviceConfigRepository.getConfig() == null) {
            deviceConfigRepository.saveConfig(
                DeviceConfig(
                    pumpLabel = "PUMP 1",
                    stationName = "Total Lekki Ph2",
                    koboPerLitre = DEFAULT_KOBO_PER_LITRE,
                    // Debug only. Seeding a fuel type in release would make every pump silently
                    // claim PETROL and authorise a diesel sale against the wrong fuel — the guess
                    // DeviceConfig.fuelType exists to prevent. Release leaves it null so the
                    // operator config screen must set it; debug keeps the simulator demo working
                    // out of the box.
                    fuelType = if (BuildConfig.DEBUG) FuelType.PETROL else null,
                    virtualAccountNumber = "0123456789",
                )
            )
        }
    }

    private suspend fun deviceConfig(): DeviceConfig? = deviceConfigRepository.getConfig()

    private companion object {
        const val DEFAULT_KOBO_PER_LITRE = 87_000L
        const val DEFAULT_VIRTUAL_ACCOUNT = "0123456789"
    }
}
