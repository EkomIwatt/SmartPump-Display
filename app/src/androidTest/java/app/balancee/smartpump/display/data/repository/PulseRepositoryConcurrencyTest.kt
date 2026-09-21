// The one row boot resume trusts, written by two coroutines at once (TODO #R12).
//
// Instrumented rather than JVM because the defect lived in how the SQL was shaped — a read, then a
// full-row REPLACE — and a fake DAO would only restate whatever the fake was written to believe.
// This runs the real statements against a real (in-memory) Room database.
//
// Found on the SM-T220 on 2026-09-21: cancelling a digital fill-up left `pulse_state` holding the
// cancelled `fillup_digital_awaiting_payment` while the screen showed Idle, because the pulse clear
// in `resetToIdle` read the row before `setState(Idle)` landed and wrote its stale copy back after.
package app.balancee.smartpump.display.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.balancee.smartpump.display.data.db.SmartPumpDatabase
import app.balancee.smartpump.display.domain.model.TransactionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PulseRepositoryConcurrencyTest {

    private lateinit var db: SmartPumpDatabase
    private lateinit var repo: PulseRepositoryImpl

    /** The state the tablet was left claiming, as it was read off the device. */
    private val cancelledSale = TransactionState.FillupDigitalAwaitingPayment(
        txnId = "d79a0d3b-163e-4752-817d-348c6b964917",
        verifiedLitres = 0.67,
        amountDueKobo = 99_830L,
        qrContent = "https://checkout.paystack.com/1bstza0u5j73qnj",
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SmartPumpDatabase::class.java,
        ).build()
        repo = PulseRepositoryImpl(db.pulseStateDao())
    }

    @After
    fun tearDown() = db.close()

    /**
     * **The cancel, run many times over.** Exactly what `resetToIdle` does: the Idle goes to the
     * state writer while the pulse clear runs on its own coroutine, and neither waits for the
     * other. Whichever lands last, the row must end up Idle with the count cleared — anything else
     * is a cancelled sale that a restart will bring back.
     *
     * Repeated because a race does not lose on every run. Against the full-row writes this replaced
     * it lost within a few hundred iterations on the SM-T220.
     */
    @Test
    fun a_pulse_clear_racing_the_idle_write_never_restores_the_cancelled_sale() = runBlocking {
        repeat(ITERATIONS) { i ->
            repo.saveTransactionState(cancelledSale, cancelledSale.txnId)
            repo.savePulseCount(67, 1_000L, 42L)

            coroutineScope {
                launch(Dispatchers.IO) { repo.saveTransactionState(TransactionState.Idle, null) }
                launch(Dispatchers.IO) { repo.savePulseCount(0, 0L, null) }
            }

            assertEquals(
                "iteration $i: the cancelled sale survived the cancel",
                TransactionState.Idle,
                repo.restoreTransactionState(),
            )
            assertEquals("iteration $i: the pulse clear was lost", 0, repo.restorePulseCount())
            assertNull("iteration $i: the anchor was put back", repo.restoreAdapterAnchor())
        }
    }

    /**
     * The other direction of the same race, during a dispense: a state write (the litres ticking
     * up) must not carry an older pulse count or anchor back over a newer one. Losing that is an
     * under-count on resume after a power cut.
     */
    @Test
    fun a_state_write_racing_a_pulse_write_never_rolls_the_count_back() = runBlocking {
        repeat(ITERATIONS) { i ->
            repo.saveTransactionState(cancelledSale, cancelledSale.txnId)
            repo.savePulseCount(100, 1_000L, 500L)

            coroutineScope {
                launch(Dispatchers.IO) { repo.saveTransactionState(TransactionState.ModeSelect(), null) }
                launch(Dispatchers.IO) { repo.savePulseCount(200, 2_000L, 600L) }
            }

            assertEquals("iteration $i: the count went backwards", 200, repo.restorePulseCount())
            assertEquals("iteration $i: the anchor went backwards", 600L, repo.restoreAdapterAnchor())
            assertEquals(TransactionState.ModeSelect(), repo.restoreTransactionState())
        }
    }

    /** Sequential, so it fails on meaning rather than on timing: each writer owns its columns. */
    @Test
    fun each_writer_leaves_the_other_writers_columns_alone() = runBlocking {
        repo.savePulseCount(120, 5_000L, 900L)
        repo.saveTransactionState(cancelledSale, cancelledSale.txnId)

        val afterState = db.pulseStateDao().get()!!
        assertEquals(120, afterState.pulseCount)
        assertEquals(5_000L, afterState.lastPulseTimeMs)
        assertEquals(900L, afterState.adapterCount)

        repo.savePulseCount(0, 0L, null)

        assertEquals(cancelledSale, repo.restoreTransactionState())
        assertEquals(cancelledSale.txnId, repo.getActiveTransactionRef())
    }

    /** The reconciler moves the count and the anchor, and deliberately not the last-pulse time. */
    @Test
    fun a_reconciled_count_keeps_the_last_pulse_time_the_previous_process_saw() = runBlocking {
        repo.savePulseCount(50, 7_000L, 300L)

        repo.saveReconciledCount(80, 330L)

        val row = db.pulseStateDao().get()!!
        assertEquals(80, row.pulseCount)
        assertEquals(330L, row.adapterCount)
        assertEquals(7_000L, row.lastPulseTimeMs)
    }

    /**
     * An UPDATE against a missing row does nothing and says nothing, so every writer has to create
     * the row first — from either side, on a fresh install.
     */
    @Test
    fun the_first_write_of_either_kind_creates_the_row() = runBlocking {
        repo.savePulseCount(5, 10L, 1L)
        assertEquals(5, repo.restorePulseCount())
        assertEquals(TransactionState.Idle, repo.restoreTransactionState())

        db.clearAllTables()

        repo.saveTransactionState(cancelledSale, cancelledSale.txnId)
        assertEquals(cancelledSale, repo.restoreTransactionState())
        assertEquals(0, repo.restorePulseCount())
        assertNull(repo.restoreAdapterAnchor())
    }

    /** A stateless state (Idle, ModeSelect) carries no ref, and must not erase the stored one. */
    @Test
    fun a_null_ref_keeps_the_stored_one() = runBlocking {
        repo.saveTransactionState(cancelledSale, cancelledSale.txnId)

        repo.saveTransactionState(TransactionState.Idle, null)

        assertEquals(cancelledSale.txnId, repo.getActiveTransactionRef())
    }

    private companion object {
        const val ITERATIONS = 500
    }
}
