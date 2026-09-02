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
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.FuelType
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.PaymentResult
import app.balancee.smartpump.display.domain.model.PulseMessage
import app.balancee.smartpump.display.domain.model.Transaction
import app.balancee.smartpump.display.domain.model.TransactionState
import app.balancee.smartpump.display.domain.payment.PaymentProcessor
import app.balancee.smartpump.display.domain.repository.DeviceConfigRepository
import app.balancee.smartpump.display.domain.repository.PulseRepository
import app.balancee.smartpump.display.domain.repository.TransactionRepository
import app.balancee.smartpump.display.domain.usecase.CanStartTransactionUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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

    /** Emit a cumulative session pulse count. Litres = count / 100. */
    fun emitPulse(count: Int, timestampMs: Long = count.toLong()) {
        flow.tryEmit(PulseMessage.Pulse(count, timestampMs))
    }

    fun emitHeartbeat() { flow.tryEmit(PulseMessage.Heartbeat(0L)) }
    fun emitDisconnected() { flow.tryEmit(PulseMessage.Disconnected) }
}

/** Records relay start/stop calls and exposes the fuel-flow state the VM invariant depends on. */
class FakeRelayController : RelayController {
    private val _isDispensing = MutableStateFlow(false)
    override val isDispensing: StateFlow<Boolean> = _isDispensing.asStateFlow()

    var startCount = 0; private set
    var stopCount = 0; private set

    override suspend fun startFuelFlow() { startCount++; _isDispensing.value = true }
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
    var processCount = 0; private set

    override fun process(method: PaymentMethod, amountKobo: Long): Flow<PaymentResult> = flow {
        processCount++
        lastMethod = method
        lastAmountKobo = amountKobo
        emit(PaymentResult.Pending(pendingRef, method))
        emitAll(terminals)
    }

    fun succeed(
        ref: String = pendingRef,
        amountKobo: Long = lastAmountKobo,
        method: PaymentMethod = lastMethod ?: PaymentMethod.BALANCEE_APP,
    ) {
        terminals.tryEmit(PaymentResult.Success(ref, amountKobo, method))
    }

    fun fail(reason: String, ref: String? = pendingRef) {
        terminals.tryEmit(PaymentResult.Failed(reason, ref))
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
    override suspend fun getConfig(): DeviceConfig? = config
    override suspend fun saveConfig(config: DeviceConfig) { saveCount++; this.config = config }
    override fun observeConfig(): Flow<DeviceConfig?> = MutableStateFlow(config)
}

class FakePulseRepository : PulseRepository {
    /** Seed before building the VM to drive a boot-resume path. */
    var stateToRestore: TransactionState = TransactionState.Idle
    var pulsesToRestore: Int = 0
    var activeRef: String? = null

    val savedStates = mutableListOf<Pair<TransactionState, String?>>()
    val savedPulseCounts = mutableListOf<Pair<Int, Long>>()

    override suspend fun saveTransactionState(state: TransactionState, transactionRef: String?) {
        savedStates += state to transactionRef
    }
    override suspend fun restoreTransactionState(): TransactionState = stateToRestore
    override suspend fun savePulseCount(count: Int, lastPulseTimeMs: Long) {
        savedPulseCounts += count to lastPulseTimeMs
    }
    override suspend fun restorePulseCount(): Int = pulsesToRestore
    override suspend fun getActiveTransactionRef(): String? = activeRef

    val lastSavedPulseCount: Pair<Int, Long>? get() = savedPulseCounts.lastOrNull()
}

class FakeTransactionRepository : TransactionRepository {
    val saved = mutableListOf<Transaction>()
    override suspend fun saveTransaction(transaction: Transaction) { saved += transaction }
    override fun getRecentTransactions(limit: Int): Flow<List<Transaction>> = MutableStateFlow(saved.toList())
    override suspend fun getPendingSync(): List<Transaction> = saved.toList()

    val last: Transaction? get() = saved.lastOrNull()
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
    val pulseRepo = FakePulseRepository()
    val transactions = FakeTransactionRepository()

    fun build(): CustomerViewModel = CustomerViewModel(
        canStartTransaction = CanStartTransactionUseCase(deviceConfig),
        deviceConfigRepository = deviceConfig,
        paymentProcessor = payment,
        pulseSource = pulseSource,
        pulseRepository = pulseRepo,
        relay = relay,
        transactions = transactions,
    )
}
