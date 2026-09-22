// Shared test harness for CustomerViewModel unit tests (Phase 8).
//
// Design notes:
//  - Main is pinned to an UnconfinedTestDispatcher via [MainDispatcherRule], so viewModelScope
//    coroutines run EAGERLY on the calling thread. That makes the VM's init boot sequence and every
//    onX() handler execute synchronously — assertions read vm.ui.value directly, no Turbine needed.
//  - Because init runs bootResume() eagerly at construction, fakes must be configured BEFORE the VM
//    is built. Tests seed the fakes, then call buildViewModel().
//  - The dispatcher shares one TestCoroutineScheduler, so time-based tests can `runTest(rule.dispatcher)`
//    and advanceTimeBy() the VM's delay()-driven countdowns.
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.hardware.PulseSource
import app.balancee.smartpump.display.domain.hardware.RelayController
import app.balancee.smartpump.display.domain.hardware.AdapterSession
import app.balancee.smartpump.display.domain.hardware.SessionReply
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.FailureCopy
import app.balancee.smartpump.display.domain.model.EventType
import app.balancee.smartpump.display.domain.model.OperationalEvent
import app.balancee.smartpump.display.domain.model.FuelType
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.PaymentRequest
import app.balancee.smartpump.display.domain.model.PaymentResult
import app.balancee.smartpump.display.domain.model.PulseMessage
import app.balancee.smartpump.display.domain.model.Transaction
import app.balancee.smartpump.display.domain.model.TransactionState
import app.balancee.smartpump.display.domain.payment.PaymentProcessor
import app.balancee.smartpump.display.domain.config.DeviceConfigSync
import app.balancee.smartpump.display.domain.repository.DeviceConfigRepository
import app.balancee.smartpump.display.domain.repository.EventRepository
import app.balancee.smartpump.display.domain.repository.PulseRepository
import app.balancee.smartpump.display.domain.repository.TransactionRepository
import app.balancee.smartpump.display.domain.sync.TransactionUploadScheduler
import app.balancee.smartpump.display.domain.usecase.CanStartTransactionUseCase
import app.balancee.smartpump.display.domain.usecase.ReconcilePulseGapUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.time.Instant
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Test price: ₦1000/L in kobo. Chosen so litres = pulses/100 give round numbers. */
const val TEST_KOBO_PER_LITRE = 100_000L

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val scheduler: TestCoroutineScheduler = TestCoroutineScheduler(),
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(scheduler),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

/**
 * Cold pulse stream fake. The VM subscribes once per dispense; because the collector runs eagerly
 * under the unconfined dispatcher, [emitPulse] & friends deliver synchronously to an active dispense.
 */
class FakePulseSource : PulseSource {
    private val flow = MutableSharedFlow<PulseMessage>(replay = 0, extraBufferCapacity = 64)
    override fun observe(): Flow<PulseMessage> = flow

    /**
     * Stand-in for the adapter's free-running lifetime count. Set it before or during a test to
     * control what the VM anchors its writes to. Null models a down link — the adapter's count is
     * unknown, which is NOT the same as zero.
     */
    private val _adapterCount = MutableStateFlow<Long?>(null)
    override val adapterCount: StateFlow<Long?> = _adapterCount.asStateFlow()

    /** How many times awaitAdapterCount() was called, and with what timeout. */
    val awaitCalls = mutableListOf<Long>()

    /**
     * Holds the boot resume inside its adapter wait — the window review #6 lives in.
     *
     * In production this wait is worth up to `ADAPTER_COUNT_TIMEOUT_MS` (3 s) and runs before
     * `bootResume` has dispatched anything, so `_ui.value.state` is still `Idle`. The fake answered
     * instantly, which meant every boot test saw the resume finish before the price sync started —
     * the opposite order from the one that matters, and the reason a test could assert that a
     * synced price does not move under a struck sale while proving nothing of the kind.
     */
    private var adapterGate: CompletableDeferred<Unit>? = null

    fun holdAdapterCount() { adapterGate = CompletableDeferred() }

    fun releaseAdapterCount() {
        adapterGate?.complete(Unit)
        adapterGate = null
    }

    override suspend fun awaitAdapterCount(timeoutMs: Long): Long? {
        awaitCalls += timeoutMs
        adapterGate?.await()
        return _adapterCount.value
    }

    fun setAdapterCount(value: Long?) { _adapterCount.value = value }

    /** Emit a cumulative session pulse count. Litres = count / 100. */
    fun emitPulse(count: Int, timestampMs: Long = count.toLong()) {
        flow.tryEmit(PulseMessage.Pulse(count, timestampMs))
    }

    fun emitHeartbeat() { flow.tryEmit(PulseMessage.Heartbeat(0L)) }
    fun emitDisconnected() { flow.tryEmit(PulseMessage.Disconnected) }
}

/**
 * Records relay start/stop calls and exposes the fuel-flow state the VM invariant depends on.
 * Acknowledges every arm (Phase 11) and records the limit and tag it was armed with.
 */
class FakeRelayController : RelayController {
    private val _isDispensing = MutableStateFlow(false)
    override val isDispensing: StateFlow<Boolean> = _isDispensing.asStateFlow()

    private val _session = MutableStateFlow<AdapterSession?>(null)
    override val session: StateFlow<AdapterSession?> = _session.asStateFlow()

    var startCount = 0; private set
    var stopCount = 0; private set

    /** Every (limitPulses, tag) the VM armed with, in order. */
    val arms = mutableListOf<Pair<Long, Long>>()
    val lastLimit: Long? get() = arms.lastOrNull()?.first

    override suspend fun startFuelFlow(limitPulses: Long, tag: Long): SessionReply {
        startCount++
        arms += limitPulses to tag
        _isDispensing.value = true
        return SessionReply.Armed(AdapterSession(tag, 0L)).also { _session.value = it.session }
    }

    override suspend fun resumeFuelFlow(tag: Long): SessionReply = SessionReply.NoSession
    override suspend fun querySession(): SessionReply = SessionReply.NoSession
    override suspend fun stopFuelFlow() { stopCount++; _isDispensing.value = false }
}

/**
 * Payment fake: every process() call emits one Pending immediately, then relays whatever terminal
 * the test pushes via [succeed]/[fail]. The flow stays open (emitAll of a SharedFlow) so a test can
 * hold a state in "awaiting payment" and drive the terminal deterministically.
 */
class FakePaymentProcessor : PaymentProcessor {
    private val terminals = MutableSharedFlow<PaymentResult>(replay = 0, extraBufferCapacity = 8)

    var pendingRef = "BLC-PAY01"
    var lastMethod: PaymentMethod? = null
    var lastAmountKobo: Long = 0L
    /** What the caller said the amount buys. Phase 10c sends this to /authorise verbatim. */
    var lastExpectedLitres: Double? = null; private set
    var lastRequest: PaymentRequest? = null; private set
    var processCount = 0; private set

    /**
     * How many times a restart re-attached instead of starting over (Phase 10d). A boot resume that
     * bumps [processCount] is the defect: against the real backend that is a second `/authorise`.
     */
    var resumeCount = 0; private set
    var lastResumedRef: String? = null; private set
    var lastResumedDeadline: Instant? = null; private set

    /** Optional Pending extras, so a test can drive the 10c/10d screens without a real backend. */
    var pendingCheckoutUrl: String? = null
    var pendingExpiresAt: Instant? = null
    var pendingPaymentReference: String? = null

    /** What the processor says the sale is worth. Null = echo the request, as the mock does. */
    var pendingAmountKobo: Long? = null
    var pendingLitres: Double? = null

    /**
     * Holds `process` open **after** it has been entered and before it emits — the round trip the
     * real processor spends inside `/config` and `/authorise` (review #5).
     *
     * It is the only window in which the pre-pay and fill-up screens still show the button that
     * started the sale, and until this existed no test could sit in it: the fake emitted `Pending`
     * on the same tick, so the state had always moved by the time a second tap could arrive and a
     * double-tap test passed for a reason that does not hold against a server.
     *
     * [processCount] is incremented before the wait on purpose. A guard that fails must show up as
     * a second entry, not as a second emission.
     */
    private var authoriseGate: CompletableDeferred<Unit>? = null

    fun holdAuthorise() { authoriseGate = CompletableDeferred() }

    /** How many times the VM asked for a sale to be prepared ahead of the tap. */
    var prepareCount = 0; private set

    override suspend fun prepareToAuthorise() { prepareCount++ }

    fun releaseAuthorise() {
        authoriseGate?.complete(Unit)
        authoriseGate = null
    }

    override fun process(request: PaymentRequest): Flow<PaymentResult> = flow {
        processCount++
        record(request)
        authoriseGate?.await()
        emit(
            PaymentResult.Pending(
                transactionRef = pendingRef,
                method = request.method,
                amountKobo = pendingAmountKobo ?: request.amountKobo,
                litres = pendingLitres ?: request.expectedLitres,
                checkoutUrl = pendingCheckoutUrl,
                expiresAt = pendingExpiresAt,
                paymentReference = pendingPaymentReference,
            )
        )
        emitAll(terminals)
    }

    override fun resume(
        transactionRef: String,
        request: PaymentRequest,
        deadline: Instant?,
    ): Flow<PaymentResult> = flow {
        resumeCount++
        lastResumedRef = transactionRef
        lastResumedDeadline = deadline
        record(request)
        // No Pending, per the contract: the caller restored the QR and the deadline from disk.
        emitAll(terminals)
    }

    private fun record(request: PaymentRequest) {
        lastRequest = request
        lastMethod = request.method
        lastAmountKobo = request.amountKobo
        lastExpectedLitres = request.expectedLitres
    }

    /**
     * [paymentReference] defaults to a real-shaped one because every digital sale has one — it is
     * what `/transactions/upload` quotes, and a default of null would let a test pass against a
     * record the upload could never use (10f).
     */
    fun succeed(
        ref: String = pendingRef,
        amountKobo: Long = lastAmountKobo,
        method: PaymentMethod = lastMethod ?: PaymentMethod.BALANCEE_APP,
        litresAuthorised: Double? = null,
        paymentReference: String? = "BPM-TEST-0001",
    ) {
        terminals.tryEmit(
            PaymentResult.Success(
                transactionRef = ref,
                amountKobo = amountKobo,
                method = method,
                paymentReference = paymentReference,
                litresAuthorised = litresAuthorised,
            )
        )
    }

    /**
     * [reason] is the attendant's half. The customer's line defaults to the one a real declined
     * payment carries, so a test that only cares that a payment failed still exercises the split.
     */
    fun fail(
        reason: String,
        ref: String? = pendingRef,
        customerMessage: String = FailureCopy.PAYMENT_NOT_COMPLETED,
        recoverable: Boolean = true,
        /**
         * Defaults to false because most failures are refusals. Passing true is the processor's own
         * poll deadline elapsing - the ending that writes `PAYMENT_ABANDONED` (#R10), and the one
         * that actually happens on a tablet.
         */
        windowElapsed: Boolean = false,
    ) {
        terminals.tryEmit(
            PaymentResult.Failed(
                FailureCopy(
                    customerMessage = customerMessage,
                    attendantDetail = reason,
                    recoverable = recoverable,
                ),
                ref,
                windowElapsed = windowElapsed,
            ),
        )
    }
}

class FakeDeviceConfigRepository(
    // A fully configured pump is the default: fuelType is as load-bearing as price now
    // (CanStartTransactionUseCase blocks without it), so omitting it here would silently turn
    // every flow test into a not-configured assertion.
    var config: DeviceConfig? = DeviceConfig(
        koboPerLitre = TEST_KOBO_PER_LITRE,
        fuelType = FuelType.PETROL,
    ),
) : DeviceConfigRepository {
    var saveCount = 0; private set

    /**
     * A database that cannot be read or written, as opposed to one holding nothing (#R6).
     *
     * The two are different answers and the code has to tell them apart: `null` means this pump
     * has never been configured, and writing a fresh row over that is correct. A read that
     * *threw* says nothing about what is stored, so writing over it would wipe whatever the
     * operator had set.
     */
    var failReads = false
    var failWrites = false

    override suspend fun getConfig(): DeviceConfig? {
        if (failReads) throw IllegalStateException("database is unreadable")
        return config
    }

    override suspend fun saveConfig(config: DeviceConfig) {
        if (failWrites) throw IllegalStateException("database is full")
        saveCount++
        this.config = config
    }

    override fun observeConfig(): Flow<DeviceConfig?> = MutableStateFlow(config)
}

/**
 * The boot price sync (10c-bis). Inert by default — a pump that cannot reach Balanceè keeps the
 * price it had, which is both the contract and what every flow test wants. Set [priceToSync] to
 * drive the case where the operator's price has moved underneath the device.
 */
class FakeDeviceConfigSync(private val repo: FakeDeviceConfigRepository) : DeviceConfigSync {
    var refreshCount = 0; private set

    /** Null = the refresh failed and nothing changed. */
    var priceToSync: Long? = null

    override suspend fun refresh() {
        refreshCount++
        val price = priceToSync ?: return
        repo.config = repo.config?.copy(koboPerLitre = price)
            ?: DeviceConfig(koboPerLitre = price, fuelType = FuelType.PETROL)
    }
}

class FakePulseRepository : PulseRepository {
    /** Seed before building the VM to drive a boot-resume path. */
    var stateToRestore: TransactionState = TransactionState.Idle
    var pulsesToRestore: Int = 0
    var anchorToRestore: Long? = null
    var activeRef: String? = null

    val savedStates = mutableListOf<Pair<TransactionState, String?>>()
    val savedPulseCounts = mutableListOf<Pair<Int, Long>>()
    val savedAnchors = mutableListOf<Long?>()

    /** Count-and-anchor pairs committed by boot resume, in order. */
    val reconciledWrites = mutableListOf<Pair<Int, Long>>()

    override suspend fun saveTransactionState(state: TransactionState, transactionRef: String?) {
        savedStates += state to transactionRef
    }
    override suspend fun restoreTransactionState(): TransactionState = stateToRestore
    override suspend fun savePulseCount(count: Int, lastPulseTimeMs: Long, adapterCount: Long?) {
        savedPulseCounts += count to lastPulseTimeMs
        savedAnchors += adapterCount
    }
    /**
     * Mirrors the real store: the reconciled write lands in the same single row the next restart
     * reads back, so a test can restart twice and the second resume sees what the first committed.
     */
    override suspend fun saveReconciledCount(count: Int, adapterCount: Long) {
        reconciledWrites += count to adapterCount
        pulsesToRestore = count
        anchorToRestore = adapterCount
    }

    override suspend fun restorePulseCount(): Int = pulsesToRestore
    override suspend fun restoreAdapterAnchor(): Long? = anchorToRestore
    override suspend fun getActiveTransactionRef(): String? = activeRef

    val lastSavedPulseCount: Pair<Int, Long>? get() = savedPulseCounts.lastOrNull()
}

class FakeEventRepository : EventRepository {
    data class Recorded(
        val type: EventType,
        val pulses: Int?,
        val transactionRef: String?,
        val detail: String?,
    )

    val recorded = mutableListOf<Recorded>()

    /**
     * Types whose write blows up, standing in for a full or corrupt database (re-review #R5).
     *
     * Selective rather than a single "fail everything" flag because the same fake is shared with
     * `PumpConfigSync` in the processor's tests: a blanket failure would break a collaborator the
     * test is not asking about, and the assertion would stop meaning what it says.
     */
    val failOn = mutableSetOf<EventType>()

    /**
     * **Suspends before it does anything**, which is the whole point of the `yield` (#R9).
     *
     * `EventDao.insert` is a suspend Room DAO, so in production this call reaches a suspension
     * point and a coroutine that has been cancelled throws there rather than writing. A fake that
     * returns without ever suspending cannot observe that, so a caller which cancelled its own job
     * a line earlier passed every test and lost both the row and the transition on a real device.
     * Three pre-pay tests — including #R5's own — only became capable of failing once this line
     * existed.
     */
    override suspend fun record(
        type: EventType,
        pulses: Int?,
        transactionRef: String?,
        detail: String?,
    ) {
        yield()
        if (type in failOn) throw IllegalStateException("database is full")
        recorded += Recorded(type, pulses, transactionRef, detail)
    }

    override fun observeRecent(limit: Int): Flow<List<OperationalEvent>> = MutableStateFlow(emptyList())

    val last: Recorded? get() = recorded.lastOrNull()
}

/** Counts the asks. What is *in* the queue is TransactionUploader's business, not the VM's. */
class FakeUploadScheduler : TransactionUploadScheduler {
    var requests = 0; private set
    override fun requestUpload() { requests++ }
}

class FakeTransactionRepository : TransactionRepository {
    val saved = mutableListOf<Transaction>()
    override suspend fun saveTransaction(transaction: Transaction) { saved += transaction }
    override suspend fun getTransaction(id: String): Transaction? = saved.lastOrNull { it.id == id }
    override fun getRecentTransactions(limit: Int): Flow<List<Transaction>> = MutableStateFlow(saved.toList())

    /**
     * Mirrors the DAO's filter rather than returning everything (10f). A fake that hands back rows
     * the real query excludes would let the upload job pass its tests while looping forever in the
     * field on cash sales it can never send.
     */
    override suspend fun getPendingSync(): List<Transaction> =
        saved.filter { it.syncedAt == null && it.isUploadable && it.uploadError == null }
            .sortedBy { it.createdAt }

    override suspend fun markSynced(id: String, syncedAt: Long) = replace(id) { it.copy(syncedAt = syncedAt) }

    override suspend fun markUploadFailed(id: String, reason: String) =
        replace(id) { it.copy(uploadError = reason) }

    private fun replace(id: String, edit: (Transaction) -> Transaction) {
        val index = saved.indexOfLast { it.id == id }
        if (index >= 0) saved[index] = edit(saved[index])
    }

    val last: Transaction? get() = saved.lastOrNull()
    fun byId(id: String): Transaction? = saved.lastOrNull { it.id == id }
}

/**
 * Bundles the fakes + the wired-up VM. Configure the fakes, then read [vm] (lazy) so the boot
 * sequence sees the seeded state. The real [CanStartTransactionUseCase] is used, per the plan.
 */
class VmHarness {
    val pulseSource = FakePulseSource()
    val relay = FakeRelayController()
    val payment = FakePaymentProcessor()
    val deviceConfig = FakeDeviceConfigRepository()
    val deviceConfigSync = FakeDeviceConfigSync(deviceConfig)
    val pulseRepo = FakePulseRepository()
    val transactions = FakeTransactionRepository()
    val events = FakeEventRepository()
    val uploadScheduler = FakeUploadScheduler()

    /** The real use case, not a fake — it is pure, and stubbing it would test nothing. */
    fun build(): CustomerViewModel = CustomerViewModel(
        canStartTransaction = CanStartTransactionUseCase(deviceConfig),
        deviceConfigRepository = deviceConfig,
        deviceConfigSync = deviceConfigSync,
        events = events,
        paymentProcessor = payment,
        pulseSource = pulseSource,
        pulseRepository = pulseRepo,
        reconcilePulseGap = ReconcilePulseGapUseCase(),
        relay = relay,
        transactions = transactions,
        uploadScheduler = uploadScheduler,
    )
}
