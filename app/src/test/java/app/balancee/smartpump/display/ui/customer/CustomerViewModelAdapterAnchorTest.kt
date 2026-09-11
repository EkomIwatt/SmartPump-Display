// Phase 7h step 2 — the adapter-count anchor written alongside every pulse-count persist.
//
// What these tests are really protecting: the anchor is the ONLY record of where the adapter's own
// counter stood when the app last saw it. Without it a restart cannot tell "the board counted 150
// pulses while I was dead" from "the board has counted 150 pulses in its life", and the app falls
// back to adopting whatever it finds as a new baseline — which is the live under-billing bug this
// phase exists to close (OPEN_QUESTIONS #25).
//
// The distinction that carries the weight is null vs 0. A null anchor means "the adapter's count is
// unknown"; zero means "the adapter has counted nothing". Conflating them invites a reader to
// subtract against zero and attribute the board's entire lifetime count to one customer.
package app.balancee.smartpump.display.ui.customer

import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CustomerViewModelAdapterAnchorTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val harness = VmHarness()

    /** Pulses far enough apart to cross PULSE_PERSIST_EVERY_N (25) and force a write. */
    private val firstWrite = 30
    private val secondWrite = 80

    private fun startPrepayDispense(vm: CustomerViewModel) {
        vm.onStartTransaction()
        vm.onModeTileTap(TransactionMode.PRE_PAY)
        vm.onAmountTileTap(amountNaira = 5000) // ₦5000 → 5.0 L @ ₦1000/L, ample headroom
        vm.onMethodTileTap(PaymentMethod.BALANCEE_APP)
        vm.onModeConfirm()
        harness.payment.succeed()
    }

    @Test
    fun `a pulse-count write carries the adapter's own count with it`() {
        harness.pulseSource.setAdapterCount(41_000L)
        val vm = harness.build()
        startPrepayDispense(vm)

        harness.pulseSource.emitPulse(count = firstWrite)

        assertEquals(firstWrite to firstWrite.toLong(), harness.pulseRepo.lastSavedPulseCount)
        assertEquals(41_000L, harness.pulseRepo.savedAnchors.last())
    }

    /**
     * The two counts are on different scales and must move independently: the transaction count
     * restarts at zero every sale, the adapter's never does. A test that only ever saw them advance
     * together would pass just as happily against code that persisted the wrong one.
     */
    @Test
    fun `the anchor tracks the adapter, not the transaction count`() {
        harness.pulseSource.setAdapterCount(41_000L)
        val vm = harness.build()
        startPrepayDispense(vm)

        harness.pulseSource.emitPulse(count = firstWrite)
        // The board has counted 50 more than the transaction has: pulses from before this sale.
        harness.pulseSource.setAdapterCount(41_000L + secondWrite + 50)
        harness.pulseSource.emitPulse(count = secondWrite)

        assertEquals(
            listOf(41_000L, 41_000L + secondWrite + 50),
            harness.pulseRepo.savedAnchors.takeLast(2),
        )
        assertEquals(secondWrite to secondWrite.toLong(), harness.pulseRepo.lastSavedPulseCount)
    }

    /**
     * A dropped link must persist NULL, not the last value we happened to hold. A stale anchor
     * would be read after a restart as a genuine measurement and produce a fabricated gap.
     */
    @Test
    fun `a silent adapter persists a null anchor rather than a stale one`() {
        harness.pulseSource.setAdapterCount(41_000L)
        val vm = harness.build()
        startPrepayDispense(vm)
        harness.pulseSource.emitPulse(count = firstWrite)

        harness.pulseSource.setAdapterCount(null) // link down; count unknowable
        harness.pulseSource.emitPulse(count = secondWrite)

        assertNull("unknown must not be recorded as a number", harness.pulseRepo.savedAnchors.last())
    }

    /**
     * Ending the transaction clears the anchor. Leaving one behind would let the next start
     * measure a gap against fuel that was never part of any sale.
     */
    @Test
    fun `cancelling clears the anchor along with the pulse count`() {
        harness.pulseSource.setAdapterCount(41_000L)
        val vm = harness.build()
        startPrepayDispense(vm)
        harness.pulseSource.emitPulse(count = firstWrite)
        assertTrue(harness.pulseRepo.savedAnchors.last() != null)

        vm.onCancel()

        assertEquals(0 to 0L, harness.pulseRepo.lastSavedPulseCount)
        assertNull(harness.pulseRepo.savedAnchors.last())
    }
}
