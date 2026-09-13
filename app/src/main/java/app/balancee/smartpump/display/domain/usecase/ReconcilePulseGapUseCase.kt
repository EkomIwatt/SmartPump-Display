// Decides what to do with fuel the adapter counted while the app was not watching (Phase 7h,
// OPEN_QUESTIONS #25).
//
// THE PROBLEM. The adapter's pulse counter is free-running and it is not on the tablet's power.
// The app can stop — a crash, a low-memory kill, an OS update reboot, a flat battery — while the
// board keeps counting, and for the ~3 s it takes the firmware watchdog to drop the relay, fuel is
// still moving into a customer's tank. Before this phase the app came back, found a counter it did
// not recognise, adopted the reading as a fresh baseline and contributed ZERO. The litres were
// delivered and billed to nobody.
//
// Note which way the money runs: the app always UNDER-counts, so the station absorbs the loss and
// the customer is never overcharged. That is why this is a reconciliation problem and not a refund
// one. It is also why the safe failure mode here is to decline and log, never to guess high.
//
// WHAT THIS DOES NOT DO. It cannot recover fuel delivered while the ADAPTER was also down, because
// a board that lost power restarts its counter at zero and the evidence is gone. That case needs
// the EEPROM totaliser (7g, firmware unverified and held off `main`). This class reports it
// honestly as unexplained rather than inventing a figure, and it is the seam the totaliser plugs
// into when it lands.
//
// Pure: no Android, no coroutines, no I/O. Deliberately — a wrong answer here bills the wrong
// person, so it is the kind of code that must be exhaustively testable off-device.
package app.balancee.smartpump.display.domain.usecase

import javax.inject.Inject

class ReconcilePulseGapUseCase @Inject constructor() {

    /**
     * @param anchor           the adapter's free-running count as of the last persisted write, or
     *                         null if none was recorded. Null is NOT zero.
     * @param adapterCountNow  the adapter's count read back after the restart, or null if the
     *                         board has not reported (no link, no permission, nothing attached).
     * @param transactionInFlight whether a dispensing transaction was restored alongside it.
     * @param maxPlausiblePulses ceiling above which a gap is rejected as not-from-this-sale.
     */
    operator fun invoke(
        anchor: Long?,
        adapterCountNow: Long?,
        transactionInFlight: Boolean,
        maxPlausiblePulses: Int = MAX_PLAUSIBLE_GAP_PULSES,
    ): Result {
        // Order matters below: each branch removes a reason we cannot trust the arithmetic, so by
        // the time a delta is computed both operands are known to be real readings from the same
        // uninterrupted run of the same board.

        if (adapterCountNow == null) return Result.Unexplained(null, Reason.ADAPTER_SILENT)
        if (anchor == null) return Result.Unexplained(null, Reason.NO_ANCHOR)

        if (adapterCountNow < anchor) {
            // The counter went backwards, so the board restarted — whether or not we caught its
            // BOOT frame (it may have been sent while the app was dead). Everything it counted
            // before the reset is unrecoverable, and the size is genuinely unknowable: null, not
            // zero, and certainly not the post-reboot reading, which is fuel that never flowed.
            return Result.Unexplained(null, Reason.ADAPTER_RESTARTED)
        }

        val delta = (adapterCountNow - anchor).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (delta == 0) return Result.NoGap

        // Known size, nowhere to put it. The restart landed between sales, or during one that had
        // already finished. Still recorded: the litres are real and the station has lost them.
        if (!transactionInFlight) return Result.Unexplained(delta, Reason.NO_TRANSACTION)

        if (delta > maxPlausiblePulses) {
            // Too much fuel to have come from the blind window of one sale. Something else
            // produced it — a bench test, a manual dispense, a second transaction, a counter we
            // cannot account for. Billing this to whoever happens to be standing at the pump is a
            // worse failure than the station absorbing it, so it goes to a human instead.
            return Result.Unexplained(delta, Reason.IMPLAUSIBLE_SIZE)
        }

        return Result.Recovered(delta)
    }

    sealed interface Result {
        /** The adapter counted nothing while the app was away. The ordinary case. */
        data object NoGap : Result

        /** [pulses] can be attributed to the transaction that was in flight. */
        data class Recovered(val pulses: Int) : Result

        /**
         * Fuel moved and the app will not guess who owes for it. [pulses] is the size where it is
         * knowable and null where it is not — null means "fuel may have been lost and we cannot
         * even say how much", which is a worse finding than a number, not a smaller one.
         */
        data class Unexplained(val pulses: Int?, val reason: Reason) : Result
    }

    enum class Reason {
        /** No link to the board, so its count could not be read at all. */
        ADAPTER_SILENT,

        /** Nothing was stored to compare against — a pump updated from before schema v4. */
        NO_ANCHOR,

        /** The count went backwards: the board lost power too, and its history with it. */
        ADAPTER_RESTARTED,

        /** Bigger than one sale's blind window could produce. */
        IMPLAUSIBLE_SIZE,

        /** Measurable, but there was no transaction in flight to attribute it to. */
        NO_TRANSACTION,
    }

    companion object {
        /**
         * Ceiling on a gap that will be billed to a customer, IN PULSES.
         *
         * Pulses, not litres, on purpose: litres go through PULSES_PER_LITRE, which is an
         * unmeasured placeholder (OPEN_QUESTIONS #1). A ceiling written in litres would silently
         * change meaning on the day calibration lands, in the direction of accepting larger gaps.
         *
         * Derivation — two terms, and the first is easy to forget:
         *  - Up to PULSE_PERSIST_EVERY_N (25) pulses of ORDINARY in-sale flow, because the anchor
         *    is only written every 25th pulse, so it is already that stale before anything goes
         *    wrong. (Recovering these is the second, smaller leak #25 describes, fixed here for
         *    free.) If that cadence changes in CustomerViewModel, this must change with it.
         *  - The firmware watchdog window: HEARTBEAT_TIMEOUT_MS = 3000 ms in
         *    smartpump_pulse_adapter.ino, during which the relay is still closed after the app
         *    goes quiet. At a fast 50 L/min and the placeholder 100 pulses/L that is ~250 pulses.
         *
         * ~275 with no margin; rounded to 400 to cover relay and solenoid coast, scheduling, and a
         * meter finer than the placeholder. REVISIT AT T-01: once the real K-factor is measured,
         * recompute rather than assuming this number still means three seconds.
         */
        const val MAX_PLAUSIBLE_GAP_PULSES = 400
    }
}
