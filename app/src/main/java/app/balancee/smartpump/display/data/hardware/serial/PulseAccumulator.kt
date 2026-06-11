// Turns the adapter's free-running cumulative pulse count into per-frame deltas. Pure and
// stateful (no Android / coroutine deps) so the USB driver can stay thin and this stays
// unit-testable. The driver sums these deltas into a session count (PulseMessage.Pulse.count)
// and resets the session on each relay-open — exactly mirroring MockPulseSource, which zeroes
// its count on every open→close→open cycle.
//
// Why deltas off a cumulative counter: a dropped PULSE line self-heals because the NEXT PULSE
// carries the higher cumulative, so its delta spans the gap. We never trust a frame to be the
// "next" one — only the running total.
package app.balancee.smartpump.display.data.hardware.serial

class PulseAccumulator {

    private var initialised = false
    private var last = 0L

    /** Forget the baseline (e.g. on USB disconnect). The next frame re-establishes it. */
    fun reset() {
        initialised = false
        last = 0L
    }

    /**
     * Adapter announced a (re)boot: its counter now reads [cumulative] (usually 0). Adopt that
     * as the baseline so the first PULSE after boot is counted in full — a boot is not itself fuel.
     */
    fun onBoot(cumulative: Long) {
        last = cumulative
        initialised = true
    }

    /**
     * Feed a PULSE frame's [cumulative]. Returns the number of NEW pulses since the last accepted
     * cumulative — always >= 0.
     *
     * - First frame with no baseline establishes the reference and contributes 0 (we must not emit
     *   a large free-running count as phantom fuel when connecting mid-stream).
     * - A backward jump without a preceding BOOT (device glitch, a missed BOOT, or a — physically
     *   unreachable at 32-bit — counter rollover) re-syncs the baseline and contributes 0, so a
     *   negative delta can never reach the litre maths.
     */
    fun onPulse(cumulative: Long): Int {
        if (!initialised) {
            last = cumulative
            initialised = true
            return 0
        }
        if (cumulative < last) {
            last = cumulative
            return 0
        }
        val delta = cumulative - last
        last = cumulative
        return delta.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
