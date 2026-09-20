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
import app.balancee.smartpump.display.domain.sync.TransactionUploadScheduler
import app.balancee.smartpump.display.domain.usecase.CanStartTransactionUseCase
import app.balancee.smartpump.display.domain.usecase.ReconcilePulseGapUseCase
import app.balancee.smartpump.display.domain.util.runCatchingCancellable
import app.balancee.smartpump.display.ui.util.buildReceiptText
import app.balancee.smartpump.display.ui.util.formatNaira
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
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
    private val uploadScheduler: TransactionUploadScheduler,
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

    /**
     * The payment start that has been asked for and has not yet produced a first result — the one
     * window in which the screen still shows the button that started it (review #5).
     *
     * **Two flows deliberately do not move their state until `Pending` arrives**: pre-pay, which
     * holds `ModeSelect`, and fill-up, which holds `FillupTankFull`. That decision is right and is
     * argued at [onFillupPayDigital] — a QR-shaped hole with a customer standing at it is worse
     * than a second of the total they are already reading. What it costs is that the `as?` state
     * check those handlers open with, which is mutual exclusion everywhere else in this class,
     * guards nothing here: the state a second tap is checked against is the same one the first tap
     * left in place. So the tap cancelled a `process` mid-`/authorise` and started another, and
     * the server does not un-create a transaction because we stopped listening — two
     * `PENDING_PAYMENT` for one tank, the first orphaned with a live checkout URL for a customer
     * who may well scan it.
     *
     * A [Job] rather than a boolean because cancellation then clears it for free: a cancelled or
     * completed job is not `isActive`, so no exit path has to remember to reset anything, and
     * there is no window in which a stale flag can wedge the pump shut.
     *
     * **The check has to precede any `cancel`**, which is why it lives in the two handlers rather
     * than in the two `start…` functions — see [authoriseInFlight].
     */
    private var authoriseJob: Job? = null

    /**
     * Whether an `/authorise` this pump has already sent is still outstanding. See [authoriseJob].
     *
     * Callers ignore the tap rather than reporting it: the customer is looking at the screen they
     * tapped on and the QR is about to replace it, so there is nothing to say and nothing to fix.
     */
    private fun authoriseInFlight(): Boolean = authoriseJob?.isActive == true
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

    /**
     * Completes once [bootResume] has dispatched whatever this tablet restored to (review #6).
     *
     * [syncPriceOnBoot] decides whether it may move the displayed price by asking what state the
     * app is in. That question has no answer until the resume has dispatched one: `_ui.value.state`
     * is `Idle` from construction, and `bootResume` reaches its first `setState` only after
     * `reconcileGapOnResume`, which waits on the adapter for up to
     * [ADAPTER_COUNT_TIMEOUT_MS]. A `/config` answering in a few hundred milliseconds therefore
     * asked the guard its question before the guard could be right, got `Idle`, and moved the price
     * under a pump that was about to restore a struck sale.
     *
     * **What is sequenced is applying the answer, not asking the server.** The fetch still goes out
     * concurrently and overlaps the adapter wait, so the boot sequence — which holds the relay-open
     * invariant and a possibly-live sale — still waits on nothing that a network can delay. That
     * was the reason these were separate coroutines in the first place, and it is preserved.
     */
    private val bootResumed = CompletableDeferred<Unit>()

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
        //
        // **Nothing in here may escape**, and the reason is #R6's rather than this coroutine's own
        // history: a bare `viewModelScope.launch` in a constructor turns any throw into an
        // uncaught one on *every* boot, so a pump whose database has gone bad cannot open the app
        // — and a forecourt tablet that cannot open the app cannot take cash either. The failure
        // this guards is a Room read, which is exactly what `syncPriceOnBoot` beneath it guards;
        // they are one defect in two coroutines and were found by one test.
        viewModelScope.launch {
            try {
                // Spec invariant: relay must default OPEN on boot — assert it before re-deriving.
                // Guarded separately and logged in its own words: a boot that cannot put the relay
                // open is a safety event and should not read as a database problem. Crashing would
                // not open it either, and the firmware's dead-man watchdog is the real backstop.
                runCatchingCancellable { relay.stopFuelFlow() }.onFailure {
                    android.util.Log.e("CustomerVM", "Could not assert the relay-open invariant on boot", it)
                }
                runCatchingCancellable {
                    seedDefaultConfigIfMissing()
                    deviceConfigRepository.getConfig()?.let { config ->
                        priceKoboPerLitre = config.koboPerLitre
                        _ui.update { it.copy(priceKoboPerLitre = priceKoboPerLitre) }
                    }
                    bootResume()
                }.onFailure {
                    // Idle with the relay open is the safe resting place, and it is where the
                    // state already is: nothing below `setState` has run.
                    android.util.Log.e("CustomerVM", "Boot resume failed; the pump stays Idle", it)
                }
            } finally {
                // In a finally because a resume that threw must not strand the sync waiting on it:
                // the idle screen would then hold a stale price with nothing on it to say why.
                bootResumed.complete(Unit)
            }
        }
        // Price sync (10c-bis), on its own coroutine on purpose: it is a network call, and the boot
        // sequence above holds the relay-open invariant and a possibly-resumed live sale. Nothing
        // that safety-critical waits on a server that may be unreachable. The dependency runs the
        // other way — see [bootResumed].
        viewModelScope.launch { syncPriceOnBoot() }
        // The queue's catch-up (10f). A sale that completed while the forecourt had no internet,
        // or while the tablet was off, is reported the next time the app opens — without this the
        // only thing that ever asks is a *new* sale, so a pump that goes quiet keeps its records
        // to itself. Costs nothing when the queue is empty.
        uploadScheduler.requestUpload()
    }

    /**
     * Pull the operator's current price down at start-up.
     *
     * Every transaction start already re-reads [DeviceConfig] through [canStartTransaction], so a
     * price that lands here is picked up by the next sale without anything else observing it. What
     * this adds is the **idle screen**, which would otherwise keep showing the boot-time figure
     * until someone bought fuel.
     *
     * Applied to the display only when **no price has been struck yet**. A resumed dispense, or a
     * sale already quoted, has its price and its litre target fixed; moving the figure under a
     * customer mid-sale would make the screen disagree with the sale they are watching, which is
     * worse than a stale idle price.
     *
     * **It is not enough to check for [TransactionState.Idle], which is what this did until the
     * 10g gate (2026-09-19).** A tablet that restores to [TransactionState.ModeSelect] — a
     * customer standing at the screen who has chosen nothing yet — returned early here, so the
     * refreshed price reached the *database* and never reached *memory*. Everything downstream
     * then quoted from a stale field: the amount screen's litre previews, and
     * `PrepayAwaitingPayment.priceKoboPerLitre`, which is what the receipt and the completion
     * screen read. Observed on production with the seed ₦870 on screen and ₦1,490 on the wire.
     *
     * The states listed here are the ones where nothing has been struck. Anything else keeps what
     * it has, which is the original guard's intent stated precisely rather than by proxy.
     */
    private suspend fun syncPriceOnBoot() {
        // Issued first and awaited second, so the round trip overlaps the resume instead of
        // following it. Only the decision below waits. See [bootResumed].
        //
        // **Nothing here may throw** (re-review #R6). This runs on a bare `viewModelScope.launch`
        // at construction, so an exception is an uncaught one on every single boot — a pump whose
        // database has gone bad would not open the app at all, and a forecourt tablet that cannot
        // open the app cannot take cash either. `refresh()` now honours its own never-throws
        // contract; the read below is this function's own, and gets the same treatment.
        deviceConfigSync.refresh()
        bootResumed.await()
        if (!priceMayMoveFreely(_ui.value.state)) return
        val config = runCatchingCancellable { deviceConfigRepository.getConfig() }
            .onFailure { android.util.Log.e("CustomerVM", "Could not re-read the synced price", it) }
            .getOrNull()
        config?.let {
            priceKoboPerLitre = it.koboPerLitre
            _ui.update { ui -> ui.copy(priceKoboPerLitre = priceKoboPerLitre) }
        }
    }

    /**
     * Whether the displayed price may still change without contradicting something a customer is
     * looking at. True before a sale has been quoted, false from the moment one has.
     *
     * Kept as a named predicate rather than an inline `is` check because the cost of getting the
     * list wrong is a customer charged at a price the screen never showed.
     */
    private fun priceMayMoveFreely(state: TransactionState): Boolean = when (state) {
        is TransactionState.Idle,
        is TransactionState.ModeSelect,
        -> true
        else -> false
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
                    startedAtEpochMs = restored.startedAtEpochMs,
                )
                // Restored, not re-granted — the same rule pre-pay follows two branches above.
                startFillupDigitalExpiry(source, restored.expiresAtEpochMs?.let(Instant::ofEpochMilli))
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
                            // A sale that finished across a restart is exactly the one the upload
                            // must not lose, so the reference and the start time come off the
                            // restored state rather than being re-derived (10f).
                            paymentReference = restored.paymentReference,
                            startedAtEpochMs = restored.startedAtEpochMs,
                            priceKoboPerLitre = restored.priceKoboPerLitre,
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
                            priceKoboPerLitre = restored.priceKoboPerLitre,
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

    /**
     * Returns the transaction ref embedded in the state, or null for stateless variants.
     *
     * **Not always a `BLC-NNNNN`.** Cash and pre-authorise states carry the one
     * [generateCashTxnId] minted; every state downstream of an `/authorise` carries the id the
     * server issued instead. Callers use it to tie pulses to whatever sale is in flight, which
     * holds either way — but do not read it as "the local reference".
     */
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
                    // Moves the state before it launches, so its own `ModeSelect` check already
                    // stops a second tap. Only the digital branch holds `ModeSelect` open.
                    PaymentMethod.USSD -> startUssdFlow(amountKobo = amountKobo)
                    // Review #5's other half. `startPrepayPayment` cancels before it starts, so
                    // the check belongs here rather than inside it. See [authoriseJob].
                    else -> if (!authoriseInFlight()) {
                        startPrepayPayment(amountKobo = amountKobo, method = method)
                    }
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
                            startedAtEpochMs = System.currentTimeMillis(),
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
                startedAtEpochMs = current.startedAtEpochMs,
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
                    // #47 confirmed the backend accepts a litres figure other than the authorised
                    // one, so an OQ #22 early end is reported honestly rather than not at all.
                    paymentReference = current.paymentReference,
                    startedAtEpochMs = current.startedAtEpochMs,
                    priceKoboPerLitre = current.priceKoboPerLitre,
                )
                is TransactionState.CashFixedDispensing -> TransactionState.Complete(
                    flow = TransactionFlow.CASH_FIXED,
                    txnId = current.txnId,
                    litres = current.litresSoFar,
                    amountKobo = current.cashAmountKobo,
                    method = null,
                    litresTarget = current.litresCutoff,
                    priceKoboPerLitre = current.priceKoboPerLitre,
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
        // Before the cancel, not after: cancelling is what would hide the first authorise from
        // this check and let a second one out behind it. See [authoriseJob].
        if (authoriseInFlight()) return
        cancelInFlightJobs()
        startFillupDigitalPayment(current)
    }

    private fun startFillupDigitalPayment(source: TransactionState.FillupTankFull) {
        paymentJob?.cancel()
        paymentJob = viewModelScope.launch {
            paymentProcessor.process(fillupDigitalRequest(source)).collect { result ->
                // The first result is what closes the window: every one of the three either moves
                // the state or ends the flow, so the plain state check guards from here on.
                authoriseJob = null
                when (result) {
                    is PaymentResult.Pending -> onFillupDigitalPending(source, result)
                    is PaymentResult.Success -> onFillupDigitalSuccess(source, result)
                    is PaymentResult.Failed -> onFillupDigitalFailed(source, result)
                }
            }
        }
        authoriseJob = paymentJob
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
                        is PaymentResult.Success -> onFillupDigitalSuccess(source, result)
                        is PaymentResult.Failed -> onFillupDigitalFailed(source, result)
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
                startedAtEpochMs = source.startedAtEpochMs,
            )
        )
        startFillupDigitalExpiry(source, pending.expiresAt)
    }

    /**
     * [success] is taken whole because of one field: `paymentReference`. It has arrived here since
     * 10a and was discarded, and `POST /transactions/upload` cannot go out without it — so this
     * flow's dispenses were unreportable and nothing said so (10f).
     */
    private suspend fun onFillupDigitalSuccess(
        source: TransactionState.FillupTankFull,
        success: PaymentResult.Success,
    ) {
        if (currentState() !is TransactionState.FillupDigitalAwaitingPayment) return
        expiryJob?.cancel()
        completeAndRecord(
            TransactionState.Complete(
                flow = TransactionFlow.FILLUP_DIGITAL,
                // **The server's id, not the one this pump made up.** `source.txnId` is the
                // `BLC-…` reference `generateCashTxnId()` minted at attendant-authorise, before
                // any server transaction existed — and the upload quotes the record's id, so a
                // fill-up recorded under it describes a sale the backend has never heard of.
                //
                // The 10g gate proved it on a real ₦149 sale (2026-09-19): the fuel flowed, the
                // money was taken, and `/transactions/upload` was refused as terminal, leaving a
                // paid transaction with no dispense against it. Flow 1 never had this because it
                // has always taken the id from the payment result; this path had its own.
                txnId = success.transactionRef,
                litres = source.verifiedLitres,
                // **What was charged, not what was quoted at the nozzle.** `source.amountDueKobo`
                // is computed at shutoff from the *device's* price; the processor then re-fetches
                // `/config` and quotes against the server's. The two diverge on a mid-sale
                // re-price — the case this code already logs as PRICE_CHANGED_MID_SALE, saying
                // "Displayed X, charged Y" and then storing X — and at any price whose payable
                // litre step is coarser than the measured figure. The record and the receipt must
                // say what Paystack collected.
                amountKobo = success.amountKobo,
                method = PaymentMethod.BANK_QR_TRANSFER,
                paymentReference = success.paymentReference,
                startedAtEpochMs = source.startedAtEpochMs,
                priceKoboPerLitre = strikePrice(success.amountKobo, source.verifiedLitres),
            )
        )
    }

    private suspend fun onFillupDigitalFailed(
        source: TransactionState.FillupTankFull,
        failed: PaymentResult.Failed,
    ) {
        val awaiting = currentState() as? TransactionState.FillupDigitalAwaitingPayment ?: return
        // First, and before anything that suspends: the countdown is the other writer of the row
        // below, and cancelling it here is what keeps one abandonment from being logged twice.
        expiryJob?.cancel()
        // **The poller's ending is the one that normally happens** (#R10). Both clocks run off the
        // same `expiresAt`, and the processor's compares against the wall clock while
        // [startFillupDigitalExpiry]'s counts one-second `delay`s — so a tablet that dozes hands
        // the ending to the poller, which until now wrote nothing. The id and the amount come off
        // the **live** state for #R4's reason: `source` carries the local `BLC-…` minted at
        // attendant-authorise and the quote struck at shutoff, neither of which is what the still
        // payable checkout page charges.
        if (failed.windowElapsed) {
            recordAbandonedPayment(failed.transactionRef ?: awaiting.txnId, awaiting.amountDueKobo)
        }
        // No Error state here: the fuel is already in the tank, so the flow falls back to cash
        // rather than to a card the customer can only dismiss. The diagnostic half still has to go
        // somewhere, and this is the one failure path with no attendant banner to put it on.
        android.util.Log.w(
            "CustomerVM",
            "Fill-up digital payment failed: " +
                (failed.failure.attendantDetail ?: failed.failure.customerMessage),
        )
        setState(
            TransactionState.FillupAwaitingCashConfirm(
                txnId = source.txnId,
                verifiedLitres = source.verifiedLitres,
                amountDueKobo = source.amountDueKobo,
            )
        )
    }

    /**
     * @param serverExpiry the deadline `/authorise` returned. **Null is a fallback, not a
     *   default.** Until the 10g review this method ignored the server entirely and always gave
     *   five minutes, while the processor polled to the real twenty — so a customer who scanned at
     *   5:30 had the sale cancelled under them and the attendant asked for cash on a tank that was
     *   about to be paid for by card. That is TODO #43, fixed for pre-pay in `startExpiryCountdown`
     *   and left standing here.
     */
    private fun startFillupDigitalExpiry(
        source: TransactionState.FillupTankFull,
        serverExpiry: Instant? = null,
    ) {
        expiryJob?.cancel()
        expiryJob = viewModelScope.launch {
            var remaining = serverExpiry
                ?.let { Duration.between(Instant.now(), it).seconds.toInt() }
                ?.coerceAtLeast(1)
                ?: FILLUP_DIGITAL_EXPIRY_SECONDS
            _ui.update { it.copy(fillupDigitalExpiresInSeconds = remaining) }
            while (remaining > 0 && currentState() is TransactionState.FillupDigitalAwaitingPayment) {
                delay(1_000L)
                remaining -= 1
                _ui.update { it.copy(fillupDigitalExpiresInSeconds = remaining) }
            }
            // **Read the live state, not [source].** `source` is the FillupTankFull this sale
            // started from, and its `txnId` is the local `BLC-…` reference minted at
            // attendant-authorise — an id no `/authorise` ever issued. The abandonment row exists
            // so that a customer who pays after the pump stops watching can be answered, and an
            // id the backend has never heard of answers nothing. The state the countdown is
            // watching carries the server's id, put there by [onFillupDigitalPending], which is
            // exactly what the pre-pay twin in [startExpiryCountdown] reads.
            //
            // This is the third appearance of one defect: `ed77e00` fixed it for `Complete.txnId`
            // in this same flow after the 10g gate caught it on a real sale, and left the expiry
            // path standing.
            val abandoned = currentState() as? TransactionState.FillupDigitalAwaitingPayment
            if (remaining <= 0 && abandoned != null) {
                paymentJob?.cancel()
                // The fall-back to cash is visible to an attendant, unlike the pre-pay one — but
                // the checkout page is just as live, so a customer who pays digitally a moment
                // later can be asked for cash as well. The log is what makes that answerable.
                //
                // `abandoned.amountDueKobo` for the same reason: it is the processor's figure,
                // which is what the still-live checkout page will charge. `source.amountDueKobo`
                // is the device-priced quote struck at shutoff, and the two diverge on a mid-sale
                // re-price and at any payable litre step coarser than the metered figure.
                recordAbandonedPayment(abandoned.txnId, abandoned.amountDueKobo)
                setState(
                    // The cash fall-back keeps **`source`** on purpose, and the asymmetry is the
                    // point. What is owed in cash is the tank's litres at the pump's own price —
                    // the same figure Flow 2 collects for the same tank — and the record it
                    // settles into is a cash sale, which nothing authorised and nothing uploads.
                    // The server's id belongs to a transaction that was never paid.
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
                    // FillupAwaitingCashConfirm carries no price of its own, so it is recovered
                    // from the two figures locked together at tank-full.
                    priceKoboPerLitre = strikePrice(current.amountDueKobo, current.verifiedLitres),
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
                                        priceKoboPerLitre = current.priceKoboPerLitre,
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
                    is PaymentResult.Success -> onUssdSmsConfirmed(amountKobo, txnId, result)
                    is PaymentResult.Failed -> onUssdFailed(result.failure)
                }
            }
        }
    }

    /** [success] is taken for its `paymentReference` — see [onFillupDigitalSuccess]. */
    private suspend fun onUssdSmsConfirmed(
        amountKobo: Long,
        txnId: String,
        success: PaymentResult.Success,
    ) {
        if (currentState() !is TransactionState.UssdAwaitingSms) return
        expiryJob?.cancel()
        val litresAuthorised = litresFor(amountKobo)
        pulseBaseline = 0
        recoveredLitres = 0.0
        setState(
            TransactionState.FixedDispensing(
                flow = TransactionFlow.USSD_OFFLINE,
                // The server's id, not `generateUssdRef`/`generateCashTxnId`'s local one. This is
                // the same defect `ed77e00` fixed for Flow 3 and it was left standing here: the
                // row carries a real `paymentReference`, so it *is* uploadable, and the upload
                // quotes `record.id` — an id `/authorise` never issued. TRANSACTION_NOT_FOUND is
                // terminal, so the dispense would be dropped permanently.
                txnId = success.transactionRef,
                priceKoboPerLitre = priceKoboPerLitre,
                amountKobo = amountKobo,
                litresAuthorised = litresAuthorised,
                litresSoFar = 0.0,
                method = PaymentMethod.USSD,
                paymentReference = success.paymentReference,
                startedAtEpochMs = System.currentTimeMillis(),
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
                // See the same line in `startFillupDigitalPayment`: the first result moves the
                // state or ends the flow, and the ordinary state check takes over from there.
                authoriseJob = null
                when (result) {
                    is PaymentResult.Pending -> onPaymentPending(amountKobo, method, result)
                    is PaymentResult.Success -> onPaymentSuccess(result)
                    is PaymentResult.Failed -> onPaymentFailed(result)
                }
            }
        }
        authoriseJob = paymentJob
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
                // Derived from the quote, not read from this class's field. The quote's amount
                // and litres were struck together against the price the processor fetched, so
                // their ratio *is* that price; the field is a display copy that can be stale.
                // 10g caught it stale — ₦870 on the state, ₦1,490 on the wire — which would
                // have put the wrong price on the receipt and the completion screen (#37).
                priceKoboPerLitre = pending.strikePriceKoboPerLitre() ?: priceKoboPerLitre,
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
                // Derived from what the server settled, not from this class's display field.
                // Carrying the price onto Complete (review finding 1) only helps if the state it
                // is carried from holds the struck price — and this is where Flow 1's comes from,
                // so reading the field here would leave the receipt exactly as wrong as before.
                priceKoboPerLitre = strikePrice(success.amountKobo, litresAuthorised)
                    ?: priceKoboPerLitre,
                // What was collected, not what was asked for — this is the figure the audit row and
                // the receipt carry.
                amountKobo = success.amountKobo,
                litresAuthorised = litresAuthorised,
                litresSoFar = 0.0,
                method = method,
                // The reference the upload quotes. Received since 10a, kept since 10f.
                paymentReference = success.paymentReference,
                startedAtEpochMs = System.currentTimeMillis(),
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
    private suspend fun onPaymentFailed(failed: PaymentResult.Failed) {
        val awaiting = currentState() as? TransactionState.PrepayAwaitingPayment
        // **Not [cancelInFlightJobs]**: this runs inside `paymentJob`'s collect, and cancelling the
        // job you are standing in is #R9 — the write below would throw at its first suspension and
        // the `setState` after it would never run. `paymentJob` is ending on its own. The others
        // are cancelled first because `expiryJob` is the row's other writer (see #R10).
        expiryJob?.cancel()
        dispenseJob?.cancel()
        fillupWatchdogJob?.cancel()
        // The window elapsing is an abandonment; a decline is not. Until #R10 only
        // [startExpiryCountdown] wrote this row, and it is not normally the clock that gets there
        // first — so on a real tablet the row was never written at all. The amount is the live
        // state's, which is the tender the server authorised and what the checkout page will take.
        if (failed.windowElapsed && awaiting != null) {
            recordAbandonedPayment(failed.transactionRef ?: awaiting.txnId, awaiting.amountKobo)
        }
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
    /**
     * The price this quote was struck at, in kobo per litre, or null when it cannot be derived.
     *
     * `amountKobo` and `litres` come out of `SaleQuote` together, so their ratio is the price the
     * server will check the sale against — which is the only price a receipt should ever show.
     * Mirrors what [bootResume] already does for a restored fill-up.
     */
    /**
     * The price a settled sale was struck at: the amount actually charged over the litres it
     * bought. Null when there are no litres to divide by.
     */
    private fun strikePrice(amountKobo: Long, litres: Double): Long? =
        if (litres > 0) Math.round(amountKobo / litres) else null

    private fun PaymentResult.Pending.strikePriceKoboPerLitre(): Long? =
        if (litres > 0) Math.round(amountKobo / litres) else null

    /**
     * Note that a digital payment window closed unpaid, and that the app has stopped watching.
     *
     * See [EventType.PAYMENT_ABANDONED]. The backend does not move the transaction off
     * `PENDING_PAYMENT` when its `expiresAt` passes, so this is not "the sale is over" — it is
     * "we are no longer looking", which is a different and more useful thing to have written down.
     *
     * **Never throws**, for the reason argued at
     * `BalanceePaymentProcessor.recordPriceRaceIfAny`: every caller writes this row and then moves
     * the state, so a Room failure here would take the `setState` with it and strand the pump on a
     * dead QR screen — a countdown at zero, the relay shut, and no way back to Idle but a restart.
     * Losing the row costs an answer to one customer; losing the transition costs the pump.
     *
     * **Four callers, two clocks** (#R10). The two expiry countdowns own the ending when the server
     * issued no `expiresAt`; the two payment-failure handlers own it when it did, because the
     * processor's poll deadline compares against the wall clock and a countdown of one-second
     * `delay`s does not. Whichever fires cancels the other, so the row is written once — and if the
     * two ever overlap by an instant, two truthful rows are a better failure than none, which is
     * what the tablet showed on 2026-09-20.
     */
    private suspend fun recordAbandonedPayment(txnId: String, amountKobo: Long) {
        val detail = "Payment window closed unpaid for ${formatNaira(amountKobo)}. " +
            "The pump stopped watching; the checkout link may still be payable."
        // [runCatchingCancellable], not `runCatching`, and the difference is load-bearing here:
        // `expiryJob` is cancelled the instant a payment succeeds, and this coroutine may be
        // suspended inside the write when that happens. A swallowed cancellation would let the
        // `setState` after this call run anyway — wiping a sale that had just been paid for.
        runCatchingCancellable {
            events.record(
                type = EventType.PAYMENT_ABANDONED,
                transactionRef = txnId,
                detail = detail,
            )
        }.onFailure {
            android.util.Log.e("CustomerVM", "Could not record abandonment of $txnId: $detail", it)
        }
    }

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
            val abandoned = currentState() as? TransactionState.PrepayAwaitingPayment
            if (remaining <= 0 && abandoned != null) {
                // **Not [cancelInFlightJobs], which cancels `expiryJob` — this coroutine** (#R9).
                // Once this job is cancelled the next suspension point throws, and the very next
                // call is a suspending Room write: neither the abandonment row nor the
                // `setState` below survived it. The pump was left on a dead QR at zero with a
                // checkout URL the backend still honours and nothing written down to answer the
                // customer who paid it — the one thing `PAYMENT_ABANDONED` exists to prevent.
                //
                // The fill-up twin in [startFillupDigitalExpiry] never had this: it cancels the
                // payment job and leaves its own alone. The jobs are named individually here for
                // the same reason — `expiryJob` is ending on its own and must not be told to.
                paymentJob?.cancel()
                dispenseJob?.cancel()
                fillupWatchdogJob?.cancel()
                recordAbandonedPayment(abandoned.txnId, abandoned.amountKobo)
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
                                        paymentReference = current.paymentReference,
                                        startedAtEpochMs = current.startedAtEpochMs,
                                        priceKoboPerLitre = current.priceKoboPerLitre,
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
        // The struck price, same rule as toAuditRecord. This is the receipt a customer is handed
        // when the saved row cannot be read back, so it is the last place that should be guessing.
        priceKoboPerLitre = priceKoboPerLitre ?: this@CustomerViewModel.priceKoboPerLitre,
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
        val record = complete.toAuditRecord(priceKoboPerLitre)
        try {
            transactions.saveTransaction(record)
        } catch (t: Throwable) {
            android.util.Log.e("CustomerVM", "Failed to persist transaction ${complete.txnId}", t)
            // Nothing to upload: the row the job reads does not exist. Asking anyway would send
            // the queue looking for a record that was never written.
            return
        }
        // Only a sale the backend can accept. A cash sale has no paymentReference and is outside
        // the upload path, not behind in it (10f) — and this is deliberately not awaited, because
        // the customer already has their fuel and the screen has to move on.
        if (record.isUploadable) uploadScheduler.requestUpload()
    }

    private fun TransactionState.Complete.toAuditRecord(priceKoboPerLitre: Long): Transaction =
        Transaction(
            id = txnId,
            flow = flow,
            paymentMethod = method,
            litresDispensed = litres,
            amountKobo = amountKobo,
            // The sale's own struck price wins; the parameter is only the fallback for rows
            // persisted before Complete carried one. See Complete.priceKoboPerLitre.
            priceKoboPerLitre = this.priceKoboPerLitre ?: priceKoboPerLitre,
            transactionRef = txnId,
            attendantId = attendantId,
            attendantNote = litresTarget?.let { target ->
                String.format(Locale.UK, "Ended by attendant at %.2f of %.2f L", litres, target)
            },
            recoveredLitres = recoveredLitres,
            // 10f. Null on a cash sale, which nothing authorised and which therefore has nothing
            // to upload — `Transaction.isUploadable` is the one place that reads it that way.
            paymentReference = paymentReference,
            startedAt = startedAtEpochMs,
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
