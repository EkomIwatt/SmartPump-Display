// Phase 7h step 3 — the classification matrix for fuel counted while the app was not watching.
//
// This is the piece where a wrong answer bills the wrong person, so the cases are enumerated rather
// than sampled. The through-line in every assertion: the app declines to guess. It attributes only
// when both readings are real, come from the same uninterrupted run of the same board, and the
// difference is small enough to have come from one sale's blind window. Everything else is
// recorded for a human and the station absorbs it.
package app.balancee.smartpump.display.domain.usecase

import app.balancee.smartpump.display.domain.usecase.ReconcilePulseGapUseCase.Reason
import app.balancee.smartpump.display.domain.usecase.ReconcilePulseGapUseCase.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconcilePulseGapUseCaseTest {

    private val reconcile = ReconcilePulseGapUseCase()

    // ---- the case the phase exists for ------------------------------------------------

    @Test
    fun `a gap inside the window is attributed to the transaction in flight`() {
        val result = reconcile(anchor = 41_000L, adapterCountNow = 41_150L, transactionInFlight = true)

        assertEquals(Result.Recovered(150), result)
    }

    @Test
    fun `no movement is no gap`() {
        val result = reconcile(anchor = 41_000L, adapterCountNow = 41_000L, transactionInFlight = true)

        assertEquals(Result.NoGap, result)
    }

    /** A counter that never moved reports no gap even with no sale open — nothing happened. */
    @Test
    fun `no movement and no transaction is still no gap, not an unexplained zero`() {
        val result = reconcile(anchor = 41_000L, adapterCountNow = 41_000L, transactionInFlight = false)

        assertEquals(Result.NoGap, result)
    }

    // ---- the four refusals --------------------------------------------------------------

    /**
     * The board lost power too. Its counter restarted, so the fuel it measured before the reset is
     * unrecoverable — that is what the EEPROM totaliser is for (7g, unverified, held off main).
     */
    @Test
    fun `a counter that went backwards means the adapter restarted, and the size is unknowable`() {
        val result = reconcile(anchor = 41_000L, adapterCountNow = 12L, transactionInFlight = true)

        assertEquals(Result.Unexplained(null, Reason.ADAPTER_RESTARTED), result)
    }

    /**
     * The post-reboot reading is fuel that never flowed through this sale — the relay opens on boot.
     * Reporting 12 pulses here would be a fabricated measurement, so the size stays null.
     */
    @Test
    fun `a restarted adapter never reports its post-reboot reading as the gap`() {
        val result = reconcile(anchor = 41_000L, adapterCountNow = 12L, transactionInFlight = true)

        assertEquals(null, (result as Result.Unexplained).pulses)
    }

    @Test
    fun `a silent adapter is unexplained, never zero`() {
        val result = reconcile(anchor = 41_000L, adapterCountNow = null, transactionInFlight = true)

        assertEquals(Result.Unexplained(null, Reason.ADAPTER_SILENT), result)
    }

    /**
     * A pump updating from before schema v4 has no anchor. Treating the absent value as zero would
     * subtract from nothing and attribute the board's ENTIRE lifetime count to one customer.
     */
    @Test
    fun `a missing anchor is unexplained, and is never treated as zero`() {
        val result = reconcile(anchor = null, adapterCountNow = 41_000L, transactionInFlight = true)

        assertEquals(Result.Unexplained(null, Reason.NO_ANCHOR), result)
    }

    @Test
    fun `a measurable gap with no transaction in flight is recorded, not attributed`() {
        val result = reconcile(anchor = 41_000L, adapterCountNow = 41_150L, transactionInFlight = false)

        assertEquals(Result.Unexplained(150, Reason.NO_TRANSACTION), result)
    }

    @Test
    fun `a gap too large for one blind window is refused`() {
        val tooBig = ReconcilePulseGapUseCase.MAX_PLAUSIBLE_GAP_PULSES + 1
        val result = reconcile(
            anchor = 41_000L,
            adapterCountNow = 41_000L + tooBig,
            transactionInFlight = true,
        )

        assertEquals(Result.Unexplained(tooBig, Reason.IMPLAUSIBLE_SIZE), result)
    }

    /** Exactly at the ceiling is still attributable; the bound is inclusive. */
    @Test
    fun `a gap exactly at the ceiling is attributed`() {
        val atLimit = ReconcilePulseGapUseCase.MAX_PLAUSIBLE_GAP_PULSES
        val result = reconcile(
            anchor = 41_000L,
            adapterCountNow = 41_000L + atLimit,
            transactionInFlight = true,
        )

        assertEquals(Result.Recovered(atLimit), result)
    }

    // ---- precedence between refusals ----------------------------------------------------

    /**
     * With nothing readable from the board, "silent" is the finding, not "no anchor". The reason
     * reaches an attendant, and sending someone to look at a database column when the actual fault
     * is an unplugged cable wastes the one person who could fix it.
     */
    @Test
    fun `silence outranks a missing anchor when both are true`() {
        val result = reconcile(anchor = null, adapterCountNow = null, transactionInFlight = true)

        assertEquals(Result.Unexplained(null, Reason.ADAPTER_SILENT), result)
    }

    /** A restarted board is reported as such even with no sale open. */
    @Test
    fun `a restart outranks having no transaction`() {
        val result = reconcile(anchor = 41_000L, adapterCountNow = 0L, transactionInFlight = false)

        assertEquals(Result.Unexplained(null, Reason.ADAPTER_RESTARTED), result)
    }

    /** An implausible size with no sale open is reported as having nowhere to go, size intact. */
    @Test
    fun `no transaction outranks an implausible size, and keeps the measurement`() {
        val huge = ReconcilePulseGapUseCase.MAX_PLAUSIBLE_GAP_PULSES * 10
        val result = reconcile(
            anchor = 41_000L,
            adapterCountNow = 41_000L + huge,
            transactionInFlight = false,
        )

        assertEquals(Result.Unexplained(huge, Reason.NO_TRANSACTION), result)
    }

    // ---- arithmetic edges ---------------------------------------------------------------

    /** Zero is a real reading from a board that just booted, and anchors like any other number. */
    @Test
    fun `a zero anchor is a measurement, not a missing value`() {
        val result = reconcile(anchor = 0L, adapterCountNow = 150L, transactionInFlight = true)

        assertEquals(Result.Recovered(150), result)
    }

    /**
     * A 32-bit free-running counter cannot realistically reach this, but the subtraction is done in
     * Long and handed to an Int-typed count, so the narrowing must not wrap into a negative litre
     * figure. It is refused on size anyway — the point is that it is refused, not that it crashes.
     */
    @Test
    fun `an absurd delta saturates instead of overflowing into a negative`() {
        val result = reconcile(
            anchor = 0L,
            adapterCountNow = Long.MAX_VALUE,
            transactionInFlight = true,
        )

        val pulses = (result as Result.Unexplained).pulses
        assertEquals(Reason.IMPLAUSIBLE_SIZE, result.reason)
        assertEquals(Int.MAX_VALUE, pulses)
    }

    /** The caller may tighten the ceiling; the default is not baked into the arithmetic. */
    @Test
    fun `a caller-supplied ceiling is honoured`() {
        val result = reconcile(
            anchor = 41_000L,
            adapterCountNow = 41_150L,
            transactionInFlight = true,
            maxPlausiblePulses = 100,
        )

        assertEquals(Result.Unexplained(150, Reason.IMPLAUSIBLE_SIZE), result)
    }
}
