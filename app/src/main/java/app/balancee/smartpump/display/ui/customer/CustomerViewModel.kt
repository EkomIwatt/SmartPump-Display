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
//    relay is open. Litres are derived via PULSES_PER_LITRE, whose value is still a
//    placeholder pending calibration task T-01 (see OPEN_QUESTIONS #1).
package app.balancee.smartpump.display.ui.customer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.balancee.smartpump.display.BuildConfig
import app.balancee.smartpump.display.domain.config.DeviceConfigSync
import app.balancee.smartpump.display.domain.hardware.PULSES_PER_LITRE
import app.balancee.smartpump.display.domain.hardware.PulseSource
import app.balancee.smartpump.display.domain.hardware.RelayController
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.EventType
import app.balancee.smartpump.display.domain.model.FailureCopy
import app.balancee.smartpump.display.domain.model.FuelType
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.PaymentRequest
import app.balancee.smartpump.display.domain.model.PaymentResult
import app.balancee.smartpump.display.domain.model.PostFillIntent
import app.balancee.smartpump.display.domain.model.PulseMessage
import app.balancee.smartpump.display.domain.model.SaleBasis
import app.balancee.smartpump.display.domain.model.Transaction
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import app.balancee.smartpump.display.domain.model.toErrorState
import app.balancee.smartpump.display.domain.payment.PaymentProcessor
import app.balancee.smartpump.display.domain.repository.DeviceConfigRepository
import app.balancee.smartpump.display.domain.repository.EventRepository
import app.balancee.smartpump.display.domain.repository.PulseRepository
import app.balancee.smartpump.display.domain.repository.TransactionRepository
import app.balancee.smartpump.display.domain.usecase.CanStartTransactionUseCase
import app.balancee.smartpump.display.domain.usecase.ReconcilePulseGapUseCase
import app.balancee.smartpump.display.ui.util.buildReceiptText
import app.balancee.smartpump.display.ui.util.formatNaira
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.inject.Inject

/**
 * Fallback only, since 10c. The real window is the `expiresAt` the server issues with each
 * authorise — **20 minutes** on production, measured six times (TODO #43). This applies when a
 * response carried no expiry at all, which no observed response has.
 */
private const val PREPAY_EXPIRY_SECONDS = 5 * 60
private const val FILLUP_DIGITAL_EXPIRY_SECONDS = 5 * 60
private const val USSD_SMS_TIMEOUT_SECONDS = 5 * 60
private const val FILLUP_SHUTOFF_TIMEOUT_MS = 3_000L
private const val FILLUP_WATCHDOG_POLL_MS = 500L

/**
 * Persist pulse count every N pulses during a dispense. Frequent enough that a power-cut
 * resume reconstructs litresSoFar closely, cheap enough not to thrash the SD card on the
 * kiosk. NOTE the resulting resolution is in *pulses*, so the litre precision it buys
 * moves with PULSES_PER_LITRE — at the current placeholder 100 pulses/L it is one write
 * per 0.25L (~25 writes for a full 10L pre-pay); a higher K-factor makes it finer.
 *
 * ALSO a term in ReconcilePulseGapUseCase.MAX_PLAUSIBLE_GAP_PULSES: the anchor written here is
 * stale by up to this many pulses before anything goes wrong, so the gap ceiling includes it.
 * Change one and change the other.
 */
private const val PULSE_PERSIST_EVERY_N = 25

/**
 * How long boot resume waits for the adapter to volunteer its free-running count before giving up
 * and treating it as unknown.
 *
 * The board sends that count in its ~2 s HB keep-alive, so the answer normally arrives well inside
 * this; the allowance is for the link still coming up (enumeration, a USB permission grant). It is
 * deliberately short because the relay is held closed for the whole wait and, on a resumed
 * dispense, a customer is standing at the pump watching nothing happen. Timing out costs a logged
 * "adapter silent" event, not a wrong number.
 */
private const val ADAPTER_COUNT_TIMEOUT_MS = 3_000L

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
    private val deviceConfigSync: DeviceConfigSync,
    private val events: EventRepository,
    private val paymentProcessor: PaymentProcessor,
    private val pulseSource: PulseSource,
    private val pulseRepository: PulseRepository,
    private val reconcilePulseGap: ReconcilePulseGapUseCase,
    private val relay: RelayController,
    private val transactions: TransactionRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(CustomerUiState())
    val ui: StateFlow<CustomerUiState> = _ui.asStateFlow()

    /**
     * One-shot receipt text for the host to put through the system share sheet.
     *
     * A Channel rather than UI state: launching the share sheet is an event, and parking the text
     * in [CustomerUiState] would re-fire it on every recomposition and again on a rotation. Nothing
     * else in this ViewModel needs one, so the seam stays this single stream.
     */
    private val _shareReceipt = Channel<String>(Channel.BUFFERED)
    val shareReceipt: Flow<String> = _shareReceipt.receiveAsFlow()

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
     * Litres folded into [pulseBaseline] by pulse-gap recovery on the last boot resume — fuel the
     * adapter counted while this app was not running. Carried from resume all the way to the audit
     * row so a sale whose litre count jumped can say why; zero for every transaction that was not
     * resumed. Cleared wherever [pulseBaseline] is.
     */
    private var recoveredLitres: Double = 0.0

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
        // Price sync (10c-bis), on its own coroutine on purpose: it is a network call, and the boot
        // sequence above holds the relay-open invariant and a possibly-resumed live sale. Nothing
        // that safety-critical waits on a server that may be unreachable.
        viewModelScope.launch { syncPriceOnBoot() }
    }

    /**
     * Pull the operator's current price down at start-up.
     *
     * Every transaction start already re-reads [DeviceConfig] through [canStartTransaction], so a
     * price that lands here is picked up by the next sale without anything else observing it. What
     * this adds is the **idle screen**, which would otherwise keep showing the boot-time figure
     * until someone bought fuel.
     *
     * Applied to the display only when the pump is idle. A resumed dispense has already struck its
     * price and its litre target; moving the figure under a customer mid-sale would make the screen
     * disagree with the sale they are watching, which is worse than a stale idle price.
     */
    private suspend fun syncPriceOnBoot() {
        deviceConfigSync.refresh()
        if (_ui.value.state !is TransactionState.Idle) return
        deviceConfigRepository.getConfig()?.let { config ->
            priceKoboPerLitre = config.koboPerLitre
            _ui.update { it.copy(priceKoboPerLitre = priceKoboPerLitre) }
        }
    }

    // ---- Boot resume ---------------------------------------------------------------

    private suspend fun bootResume() {
        val restored = pulseRepository.restoreTransactionState()
        val persistedPulses = pulseRepository.restorePulseCount()
        val restoredPulses = persistedPulses + reconcileGapOnResume(restored, persistedPulses)
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
                // The server's window keeps running through a restart, so resume against the
                // persisted deadline rather than granting a fresh one. Starting the clock again
                // here would keep a QR on screen after the server had stopped honouring it.
                startExpiryCountdown(restored.expiresAtEpochMs?.let(Instant::ofEpochMilli))
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
                resumeFillupDigitalPayment(source, restored)
            }

            is TransactionState.FixedDispensing -> {
                pulseBaseline = restoredPulses
                val method = restored.method ?: deriveMethodForFlow(restored.flow)
                if (targetAlreadyMet(restored.litresAuthorised)) {
                    completeAndRecord(
                        TransactionState.Complete(
                            flow = restored.flow,
                            txnId = restored.txnId,
                            litres = litresFromBaseline(),
                            amountKobo = restored.amountKobo,
                            method = method,
                        )
                    )
                } else {
                    setState(restored.copy(litresSoFar = resumedLitres(restored.litresSoFar)))
                    startDispensing(restored.litresAuthorised, method)
                }
            }

            is TransactionState.CashFixedDispensing -> {
                pulseBaseline = restoredPulses
                if (targetAlreadyMet(restored.litresCutoff)) {
                    completeAndRecord(
                        TransactionState.Complete(
                            flow = TransactionFlow.CASH_FIXED,
                            txnId = restored.txnId,
                            litres = litresFromBaseline(),
                            amountKobo = restored.cashAmountKobo,
                            method = null,
                        )
                    )
                } else {
                    setState(restored.copy(litresSoFar = resumedLitres(restored.litresSoFar)))
                    startCashFixedDispensing(
                        litresCutoff = restored.litresCutoff,
                        cashAmountKobo = restored.cashAmountKobo,
                        txnId = restored.txnId,
                    )
                }
            }

            is TransactionState.FillupDispensing -> {
                pulseBaseline = restoredPulses
                // No target to overshoot on an open-ended fill-up, so there is no completion
                // branch here — recovery only ever corrects the running figure.
                setState(restored.copy(litresSoFar = resumedLitres(restored.litresSoFar)))
                startFillupDispensing(restored.txnId)
            }
        }
    }

    /**
     * Phase 7h — work out how much fuel the adapter counted while this app was not running, and
     * return the pulses that may be added to the resumed transaction (0 when none may be).
     *
     * Runs BEFORE the restored state is dispatched, so the dispensing collector starts from the
     * true figure rather than correcting itself afterwards. The cost is that a resumed screen can
     * be up to ADAPTER_COUNT_TIMEOUT_MS late; the alternative — dispatch first, adjust after — has
     * the collector briefly counting against a baseline it is about to be told is wrong, which is
     * the class of bug this phase exists to remove.
     *
     * Everything unattributable is recorded rather than discarded. See OPEN_QUESTIONS #25.
     */
    private suspend fun reconcileGapOnResume(restored: TransactionState, persistedPulses: Int): Int {
        val dispensing = restored is TransactionState.FixedDispensing ||
            restored is TransactionState.CashFixedDispensing ||
            restored is TransactionState.FillupDispensing

        val anchor = pulseRepository.restoreAdapterAnchor()
        // Ordinary cold start: no sale was in flight and no anchor was left behind, so there is
        // nothing that could have been missed. Skipping here also keeps every idle boot free of
        // both the adapter wait and a meaningless "no anchor" event on every single launch.
        if (anchor == null && !dispensing) return 0

        val adapterCountNow = pulseSource.awaitAdapterCount(ADAPTER_COUNT_TIMEOUT_MS)
        val ref = pulseRepository.getActiveTransactionRef()

        return when (val gap = reconcilePulseGap(anchor, adapterCountNow, dispensing)) {
            is ReconcilePulseGapUseCase.Result.NoGap -> 0

            is ReconcilePulseGapUseCase.Result.Recovered -> {
                recoveredLitres = gap.pulses / PULSES_PER_LITRE
                // Commit the corrected count and re-anchor to the reading it was measured from,
                // BEFORE the event is written and before dispensing restarts. Until this lands,
                // the recovered pulses exist only in memory and the stored anchor still points at
                // the last checkpoint, so a second death inside the next 25 pulses would recover
                // this same fuel again and log a second, overlapping row. adapterCountNow is
                // non-null here: Recovered is unreachable when it is not.
                runCatching {
                    pulseRepository.saveReconciledCount(
                        count = persistedPulses + gap.pulses,
                        adapterCount = adapterCountNow!!,
                    )
                }
                runCatching {
                    events.record(
                        type = EventType.PULSE_GAP_RECOVERED,
                        pulses = gap.pulses,
                        transactionRef = ref,
                        detail = "Added to the transaction in flight on resume.",
                    )
                }
                gap.pulses
            }

            is ReconcilePulseGapUseCase.Result.Unexplained -> {
                runCatching {
                    events.record(
                        type = EventType.PULSE_GAP_UNEXPLAINED,
                        pulses = gap.pulses,
                        transactionRef = ref,
                        detail = unexplainedDetail(gap.reason),
                    )
                }
                0
            }
        }
    }

    private fun litresFromBaseline(): Double = pulseBaseline / PULSES_PER_LITRE

    /**
     * The litre figure a resumed dispensing screen should show.
     *
     * Only overrides [persisted] when recovery actually added pulses, and the asymmetry is
     * deliberate. The two sources are stale in opposite ways: the persisted STATE carries
     * litresSoFar from the last conflated write, so it is fresh; the persisted pulse COUNT is only
     * written every PULSE_PERSIST_EVERY_N pulses, so it lags. Normally the state is the better
     * number and is left alone. But the anchor is written in the same breath as the count, so a
     * recovered gap spans exactly that lag as well as the outage — which makes the baseline the
     * authoritative figure precisely when there is something to recover, and never smaller than
     * the one it replaces.
     */
    private fun resumedLitres(persisted: Double): Double =
        if (recoveredLitres > 0.0) litresFromBaseline() else persisted

    /**
     * True when the fuel already delivered meets or exceeds what the customer paid for — only
     * reachable once recovery has folded a gap into the baseline, since the live collector stops
     * at the target itself.
     *
     * The relay must NOT reopen in that case. State-machine invariant #4 ("never dispense more
     * than was paid") does not stop applying because the extra fuel left the pump while the app
     * was blind; reopening would pour a second helping on top of one already delivered. The sale
     * completes recording what ACTUALLY flowed, which can exceed what was charged — the audit row
     * carries litres and amount independently, and recoveredLitres says how the two came apart.
     * The station absorbs the difference, as it does for every other under-count in this path.
     */
    private fun targetAlreadyMet(targetLitres: Double): Boolean =
        recoveredLitres > 0.0 && litresFromBaseline() >= targetLitres

    /** Plain-language reason for the operator view. Deliberately says what to do about it. */
    private fun unexplainedDetail(reason: ReconcilePulseGapUseCase.Reason): String = when (reason) {
        ReconcilePulseGapUseCase.Reason.ADAPTER_SILENT ->
            "The pulse adapter did not respond, so fuel delivered during the outage cannot be measured. Check the cable."
        ReconcilePulseGapUseCase.Reason.NO_ANCHOR ->
            "No reference reading was stored before the outage, so the amount cannot be worked out."
        ReconcilePulseGapUseCase.Reason.ADAPTER_RESTARTED ->
            "The pulse adapter lost power too and its counter restarted, so the amount is unrecoverable."
        ReconcilePulseGapUseCase.Reason.IMPLAUSIBLE_SIZE ->
            "More fuel was counted than one interrupted sale could explain. Not charged to a customer."
        ReconcilePulseGapUseCase.Reason.NO_TRANSACTION ->
            "Fuel was counted with no sale in progress."
    }

    private fun resetToIdle(clearPulses: Boolean) {
        setState(TransactionState.Idle)
        if (clearPulses) {
            viewModelScope.launch {
                // Null anchor is deliberate: with no transaction in flight there is nothing to
                // anchor, and a stale one would let the next start invent a gap out of fuel that
                // was never part of a sale.
                runCatching { pulseRepository.savePulseCount(0, 0L, null) }
            }
        }
        pulseBaseline = 0
        recoveredLitres = 0.0
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
                    // Keep the UI copy in step: the completion screen reads it, and the pre-pay
                    // path never passes the other two refreshes (TODO #37).
                    _ui.update { it.copy(priceKoboPerLitre = priceKoboPerLitre) }
                    setState(TransactionState.ModeSelect())
                }
                is CanStartTransactionUseCase.Result.NotConfigured -> {
                    setState(
                        TransactionState.Error(
                            message = CanStartTransactionUseCase.CUSTOMER_MESSAGE,
                            recoverable = true,
                            attendantDetail = CanStartTransactionUseCase.attendantDetail(result.missing),
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
                    recoveredLitres = 0.0
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
                            attendantDetail = CanStartTransactionUseCase.attendantDetail(result.missing),
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
                            // Anchor the adapter's own free-running count to this write. A restart then
                            // measures the gap as (count now - count then); null here means the link was
                            // down, which reads as "unknown" rather than zero.
                            pulseRepository.savePulseCount(
                                cumulativePulses, msg.timestampMs, pulseSource.adapterCount.value,
                            )
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

    /**
     * The attendant ends a fixed sale (pre-pay, USSD, cash-fixed) before it reaches its target —
     * OQ #22, Option 1, decided 2026-09-15.
     *
     * Without this a fixed sale had no exit but its target. A customer whose tank filled first, or
     * a link that dropped and stayed down, left the screen in dispensing for good, and a power
     * cycle only restored the same stuck sale. Fill-up does not need it: its flow-gap watchdog
     * already ends an open-ended sale on what flowed.
     *
     * The record states what happened rather than tidying it: litres are what actually flowed,
     * the amount is what the customer paid, and the audit note carries the target. Settling the
     * difference is the attendant's (cash) or the backend's (digital, OQ #7) — not the pump's.
     *
     * Fuel stops before the count is read, and the collector is cancelled before the state is
     * re-checked, so a pulse in flight either lands in the record or not at all — and a sale that
     * hit its target in that window completes normally instead of being ended twice.
     */
    fun onAttendantEndSaleEarly() {
        val state = currentState()
        if (state !is TransactionState.FixedDispensing && state !is TransactionState.CashFixedDispensing) return
        viewModelScope.launch {
            relay.stopFuelFlow()
            dispenseJob?.cancel()
            dispenseJob = null
            val ended = when (val current = currentState()) {
                is TransactionState.FixedDispensing -> TransactionState.Complete(
                    flow = current.flow,
                    txnId = current.txnId,
                    litres = current.litresSoFar,
                    amountKobo = current.amountKobo,
                    method = current.method ?: deriveMethodForFlow(current.flow),
                    litresTarget = current.litresAuthorised,
                )
                is TransactionState.CashFixedDispensing -> TransactionState.Complete(
                    flow = TransactionFlow.CASH_FIXED,
                    txnId = current.txnId,
                    litres = current.litresSoFar,
                    amountKobo = current.cashAmountKobo,
                    method = null,
                    litresTarget = current.litresCutoff,
                )
                else -> return@launch
            }
            completeAndRecord(ended)
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

    /**
     * Flow 3: the customer chose to pay digitally for fuel already in the tank.
     *
     * **The screen no longer moves until the processor has something payable to show.** It used to
     * transition immediately, rendering a `nip://transfer?…` payload this app invented against the
     * operator's virtual account — a QR no scanner resolves and no bank honours, which is the same
     * defect 10c fixed for pre-pay and left standing here (OQ #6 retired the virtual account with
     * the move to Paystack; only this call site kept it alive). Now the checkout URL comes off the
     * processor's `Pending`, exactly as Flow 1's does, and a failure before then falls back to cash
     * with the tank's figure intact.
     *
     * Holding on `FillupTankFull` for the round trip mirrors Flow 1, where the state does not
     * advance until `Pending` arrives either. A QR-shaped hole with a customer standing at it is
     * worse than a second of the total they are already reading.
     */
    fun onFillupPayDigital() {
        val current = currentState() as? TransactionState.FillupTankFull ?: return
        cancelInFlightJobs()
        startFillupDigitalPayment(current)
    }

    private fun startFillupDigitalPayment(source: TransactionState.FillupTankFull) {
        paymentJob?.cancel()
        paymentJob = viewModelScope.launch {
            paymentProcessor.process(fillupDigitalRequest(source)).collect { result ->
                when (result) {
                    is PaymentResult.Pending -> onFillupDigitalPending(source, result)
                    is PaymentResult.Success -> onFillupDigitalSuccess(source)
                    is PaymentResult.Failed -> onFillupDigitalFailed(source, result.failure)
                }
            }
        }
    }

    /**
     * Re-attach to a fill-up payment restored from disk. The other half of 10d's boot-resume trap:
     * this path also called [PaymentProcessor.process], which against the real backend authorises a
     * second sale for fuel that was already dispensed and may already have been paid for.
     */
    private fun resumeFillupDigitalPayment(
        source: TransactionState.FillupTankFull,
        restored: TransactionState.FillupDigitalAwaitingPayment,
    ) {
        paymentJob?.cancel()
        paymentJob = viewModelScope.launch {
            paymentProcessor
                .resume(
                    transactionRef = restored.txnId,
                    request = fillupDigitalRequest(source),
                    deadline = restored.expiresAtEpochMs?.let(Instant::ofEpochMilli),
                )
                .collect { result ->
                    when (result) {
                        is PaymentResult.Pending -> Unit
                        is PaymentResult.Success -> onFillupDigitalSuccess(source)
                        is PaymentResult.Failed -> onFillupDigitalFailed(source, result.failure)
                    }
                }
        }
    }

    private fun fillupDigitalRequest(source: TransactionState.FillupTankFull) = PaymentRequest(
        method = PaymentMethod.BANK_QR_TRANSFER,
        amountKobo = source.amountDueKobo,
        // The tank is already full: this is the metered figure, not one derived from price.
        expectedLitres = source.verifiedLitres,
        basis = SaleBasis.Dispensed,
    )

    private fun onFillupDigitalPending(
        source: TransactionState.FillupTankFull,
        pending: PaymentResult.Pending,
    ) {
        if (currentState() !is TransactionState.FillupTankFull) return
        setState(
            TransactionState.FillupDigitalAwaitingPayment(
                txnId = pending.transactionRef,
                verifiedLitres = source.verifiedLitres,
                // The processor's figure. The tank's litres are fixed, so a re-price moves the
                // money — and the amount on screen has to be the one the checkout page charges.
                amountDueKobo = pending.amountKobo,
                qrContent = pending.checkoutUrl.orEmpty(),
                expiresAtEpochMs = pending.expiresAt?.toEpochMilli(),
            )
        )
        startFillupDigitalExpiry(source)
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

    private fun onFillupDigitalFailed(
        source: TransactionState.FillupTankFull,
        failure: FailureCopy,
    ) {
        if (currentState() !is TransactionState.FillupDigitalAwaitingPayment) return
        expiryJob?.cancel()
        // No Error state here: the fuel is already in the tank, so the flow falls back to cash
        // rather than to a card the customer can only dismiss. The diagnostic half still has to go
        // somewhere, and this is the one failure path with no attendant banner to put it on.
        android.util.Log.w(
            "CustomerVM",
            "Fill-up digital payment failed: " + (failure.attendantDetail ?: failure.customerMessage),
        )
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
                            attendantDetail = CanStartTransactionUseCase.attendantDetail(result.missing),
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
                // Was "Price not set — contact operator." — operator language on a
                // customer-facing display, and a second wording for the condition the guard
                // already has copy for. One condition, one sentence (OQ #17, approved 2026-09-12).
                TransactionState.Error(
                    message = CanStartTransactionUseCase.CUSTOMER_MESSAGE,
                    recoverable = true,
                    attendantDetail = CanStartTransactionUseCase.attendantDetail(
                        setOf(CanStartTransactionUseCase.Missing.PRICE),
                    ),
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
                    // The attendant typed this amount, so the actionable number is theirs: it goes
                    // to the panel, not onto the customer card (OQ #17).
                    TransactionState.Error(
                        // Shared with the processor's own below-minimum refusal: one condition,
                        // one sentence, which is the defect OQ #17 started from.
                        message = FailureCopy.AMOUNT_TOO_SMALL,
                        recoverable = true,
                        attendantDetail = "Below the smallest dispensable step — the minimum at " +
                            "this price is ${formatNaira(priceKoboPerLitre / 100)} for 0.01 L.",
                    )
                )
                return@launch
            }
            cancelInFlightJobs()
            pulseBaseline = 0
            recoveredLitres = 0.0
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
                                    // Anchor the adapter's own free-running count to this write. A restart then
                                    // measures the gap as (count now - count then); null here means the link was
                                    // down, which reads as "unknown" rather than zero.
                                    pulseRepository.savePulseCount(
                                        cumulativePulses, msg.timestampMs, pulseSource.adapterCount.value,
                                    )
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
            val request = PaymentRequest(
                method = PaymentMethod.USSD,
                amountKobo = amountKobo,
                expectedLitres = litresFor(amountKobo),
                basis = SaleBasis.Tender,
            )
            paymentProcessor.process(request).collect { result ->
                when (result) {
                    is PaymentResult.Pending -> Unit
                    is PaymentResult.Success -> onUssdSmsConfirmed(amountKobo, txnId)
                    is PaymentResult.Failed -> onUssdFailed(result.failure)
                }
            }
        }
    }

    private suspend fun onUssdSmsConfirmed(amountKobo: Long, txnId: String) {
        if (currentState() !is TransactionState.UssdAwaitingSms) return
        expiryJob?.cancel()
        val litresAuthorised = litresFor(amountKobo)
        pulseBaseline = 0
        recoveredLitres = 0.0
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

    private fun onUssdFailed(failure: FailureCopy) {
        if (currentState() !is TransactionState.UssdAwaitingSms) return
        expiryJob?.cancel()
        setState(
            // The split arrives already decided (10e). All this path adds is which payment method
            // it was, because the attendant's next move differs: a USSD failure is a bank's SMS
            // that did not arrive, not a QR nobody scanned.
            failure.toErrorState().let {
                it.copy(attendantDetail = it.attendantDetail?.let { d -> "USSD — $d" })
            }
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
            val request = PaymentRequest(
                method = method,
                amountKobo = amountKobo,
                expectedLitres = litresFor(amountKobo),
                basis = SaleBasis.Tender,
            )
            paymentProcessor.process(request).collect { result ->
                when (result) {
                    is PaymentResult.Pending -> onPaymentPending(amountKobo, method, result)
                    is PaymentResult.Success -> onPaymentSuccess(result)
                    is PaymentResult.Failed -> onPaymentFailed(result)
                }
            }
        }
    }

    /**
     * Re-attach to a prepay payment restored from disk — **without starting a second one**.
     *
     * This used to call `process` again. Against the mock that was free, which is why it survived
     * this long; against the real backend it POSTs a second `/authorise` and creates a second sale
     * for a customer who may already have paid for the first. The id is ours and it was persisted,
     * so `resume` asks about the sale that exists (Phase 10d).
     *
     * The amount and litres come off the restored state because they are the **authorised** figures
     * — what the checkout page quoted, not what the customer tendered — and nothing is re-priced on
     * this path.
     */
    private fun resumePrepayPaymentListener(restored: TransactionState.PrepayAwaitingPayment) {
        paymentJob?.cancel()
        val amountKobo = restored.amountKobo
        paymentJob = viewModelScope.launch {
            val request = PaymentRequest(
                method = restored.method,
                amountKobo = amountKobo,
                expectedLitres = restored.litresAuthorised ?: litresFor(amountKobo),
                basis = SaleBasis.Tender,
            )
            paymentProcessor
                .resume(
                    transactionRef = restored.txnId,
                    request = request,
                    deadline = restored.expiresAtEpochMs?.let(Instant::ofEpochMilli),
                )
                .collect { result ->
                    when (result) {
                        is PaymentResult.Pending -> Unit
                        is PaymentResult.Success -> onPaymentSuccess(result)
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
                // The processor's figure, not the tendered [amountKobo] the caller asked for. The
                // two differ whenever the tender is not an exact number of payable litres, and the
                // one on screen has to be the one the checkout page will charge (10d).
                amountKobo = pending.amountKobo,
                method = method,
                txnId = pending.transactionRef,
                priceKoboPerLitre = priceKoboPerLitre,
                checkoutUrl = pending.checkoutUrl,
                expiresAtEpochMs = pending.expiresAt?.toEpochMilli(),
                litresAuthorised = pending.litres,
            )
        )
        startExpiryCountdown(pending.expiresAt)
    }

    /**
     * [success] is now the only source: its amount, litres and method are what the server
     * authorised, and the caller's own copies were the pre-quote request. Passing those in
     * alongside is how the two came apart in the first place.
     */
    private suspend fun onPaymentSuccess(success: PaymentResult.Success) {
        val method = success.method
        expiryJob?.cancel()
        // **10d answers the question 10c left here.** The server's own figure wins when there is
        // one: re-deriving litres from the amount gives a DIFFERENT number, because the quote lands
        // on a payable litre step while litresCutoff floors to 2 dp — at ₦1,490 a ₦5,000 tender
        // authorises 3.355 L and re-derivation gives 3.35, stopping the pump 5 ml short of what was
        // paid for on the same figure 10f will reconcile against the server's record. The two
        // fallbacks below are for processors that authorise nothing.
        val litresAuthorised = success.litresAuthorised
            ?: deviceConfig()?.litresCutoff(success.amountKobo)
            ?: ((success.amountKobo.toDouble() / priceKoboPerLitre).coerceAtLeast(0.0))

        pulseBaseline = 0
        recoveredLitres = 0.0
        setState(
            TransactionState.FixedDispensing(
                flow = TransactionFlow.FIXED_PREPAY_DIGITAL,
                txnId = success.transactionRef,
                priceKoboPerLitre = priceKoboPerLitre,
                // What was collected, not what was asked for — this is the figure the audit row and
                // the receipt carry.
                amountKobo = success.amountKobo,
                litresAuthorised = litresAuthorised,
                litresSoFar = 0.0,
                method = method,
            )
        )
        startDispensing(litresAuthorised, method)
    }

    /**
     * The customer-facing half of 10e. Both lines and the recoverable flag are the processor's —
     * it is the only thing that knows whether the server refused the sale, declined the card, or
     * simply has not seen the money land yet, and until 10e all three read "Payment was not
     * completed." to the customer and "Payment failed — …" to the attendant.
     */
    private fun onPaymentFailed(failed: PaymentResult.Failed) {
        cancelInFlightJobs()
        setState(failed.failure.toErrorState())
    }

    /**
     * TODO **#43**. [serverExpiry] is the `expiresAt` the server issued with the authorise, and it is
     * what the countdown runs on whenever there is one — measured at **twenty minutes** on
     * production against the five this app assumed. The constant is the fallback for a response that
     * carried no expiry, not the default.
     *
     * Clamped to at least one second: a server expiry already in the past (a long restart, a clock
     * well behind) would otherwise run the countdown negative rather than ending the sale.
     */
    private fun startExpiryCountdown(serverExpiry: Instant? = null) {
        expiryJob?.cancel()
        val window = serverExpiry
            ?.let { Duration.between(Instant.now(), it).seconds.toInt() }
            ?.coerceAtLeast(1)
            ?: PREPAY_EXPIRY_SECONDS
        expiryJob = viewModelScope.launch {
            var remaining = window
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
                                    // Anchor the adapter's own free-running count to this write. A restart then
                                    // measures the gap as (count now - count then); null here means the link was
                                    // down, which reads as "unknown" rather than zero.
                                    pulseRepository.savePulseCount(
                                        cumulativePulses, msg.timestampMs, pulseSource.adapterCount.value,
                                    )
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

    /**
     * Build the receipt and hand it to the UI to put through the system share sheet (OQ #14).
     *
     * The record is re-read from the audit log rather than rendered from the on-screen state,
     * because the state does not carry a completion time and a screen restored after a power cut
     * would otherwise be dated "now". If the row is missing — `saveTransaction` is best-effort, so
     * that is possible — the screen state is used as the fallback: a receipt with the right money
     * and litres beats no receipt, and the customer is standing there either way.
     */
    fun onShareReceipt() {
        val complete = currentState() as? TransactionState.Complete ?: return
        viewModelScope.launch {
            val config = runCatching { deviceConfigRepository.getConfig() }.getOrNull()
            val record = runCatching { transactions.getTransaction(complete.txnId) }.getOrNull()
                ?: complete.asFallbackRecord()
            _shareReceipt.send(buildReceiptText(record, config))
        }
    }

    /**
     * The completion state as a [Transaction], for when the saved row cannot be read. `createdAt`
     * defaults to now, which is only right because this path is reached seconds after the dispense
     * — the saved row exists precisely so the normal path does not depend on that.
     */
    private fun TransactionState.Complete.asFallbackRecord() = Transaction(
        id = txnId,
        flow = flow,
        paymentMethod = method,
        litresDispensed = litres,
        amountKobo = amountKobo,
        priceKoboPerLitre = priceKoboPerLitre,
        transactionRef = txnId,
        attendantId = attendantId,
    )

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
            attendantNote = litresTarget?.let { target ->
                String.format(Locale.UK, "Ended by attendant at %.2f of %.2f L", litres, target)
            },
            recoveredLitres = recoveredLitres,
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

    /**
     * Seeds a playable demo config on first launch — **debug builds only**.
     *
     * This used to run in every build type, so a fresh release install silently gave itself a
     * ₦870/L price and the station name "Total Lekki Ph2". The price guard could therefore never
     * fire in production, and an operator opening the settings screen would find a plausible price
     * already filled in — which does not ask to be read. (The header comment above has always
     * described this as a debug-build seed; the code simply did not honour it.)
     *
     * A real pump now starts genuinely unconfigured: the guard blocks, the customer screen says so,
     * and the operator screen opens with empty fields that have to be filled deliberately.
     */
    private suspend fun seedDefaultConfigIfMissing() {
        if (!BuildConfig.DEBUG) return
        if (deviceConfigRepository.getConfig() == null) {
            deviceConfigRepository.saveConfig(
                DeviceConfig(
                    pumpLabel = "PUMP 1",
                    stationName = "Total Lekki Ph2",
                    koboPerLitre = DEFAULT_KOBO_PER_LITRE,
                    fuelType = FuelType.PETROL,
                    virtualAccountNumber = "0123456789",
                )
            )
        }
    }

    private suspend fun deviceConfig(): DeviceConfig? = deviceConfigRepository.getConfig()

    /**
     * Litres a fixed amount buys. Extracted in Phase 10a because this number now has two consumers
     * that must not disagree: the cutoff the pump enforces, and the `expectedLitres` sent to
     * `/authorise`. The server checks `amount == expectedLitres x pricePerUnit` **exactly**, so if
     * the figure we quote it were derived any differently from the figure we stop at, the sale would
     * either be refused outright or authorise a different quantity from the one dispensed.
     *
     * The expression is unchanged from the two places it was duplicated in: floored to 2dp by
     * [DeviceConfig.litresCutoff] so the pump never gives away more than was paid for, with a
     * price-only fallback for the (guard-blocked) case of no config at all.
     */
    private suspend fun litresFor(amountKobo: Long): Double =
        deviceConfig()?.litresCutoff(amountKobo)
            ?: ((amountKobo.toDouble() / priceKoboPerLitre).coerceAtLeast(0.0))

    private companion object {
        const val DEFAULT_KOBO_PER_LITRE = 87_000L
    }
}
