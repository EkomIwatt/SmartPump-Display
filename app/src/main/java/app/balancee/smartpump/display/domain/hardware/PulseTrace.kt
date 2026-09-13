// TEMPORARY BENCH INSTRUMENTATION — delete this file and its call sites before the 7h merge.
//
// Why it exists: the 7h merge-gate bench run keeps producing a recovered gap on the SECOND restart
// within one dispense that is about 0.8 L larger than the watchdog window can physically deliver,
// while the first restart in the same dispense lands inside it. Grounding the meter input removed
// the wild outliers, and a kill from idle records nothing at all, so the counter is NOT advancing
// with the relay shut. That leaves the anchor: on the second kill it appears to be older than the
// dispensing loop's 25-pulse checkpoint should allow. Reasoning has not separated the candidates,
// so this prints the actual operands instead.
//
// One tag for the whole timeline, so a single logcat filter captures it:
//   adb logcat -s PulseTrace
//
// Precedent: the same throwaway-trace-then-revert approach closed the 7a watchdog question on this
// rig. Keep it out of `main` — it logs on every persist, which is every 25 pulses.
package app.balancee.smartpump.display.domain.hardware

import android.util.Log

/** Master switch. Flip to false (or delete the file) to silence every trace site at once. */
const val PULSE_TRACE = true

const val PULSE_TRACE_TAG = "PulseTrace"

/**
 * Log a trace line. The message is built lazily so that turning [PULSE_TRACE] off costs nothing
 * at the call sites, several of which sit inside the per-pulse dispensing loop.
 */
inline fun pulseTrace(message: () -> String) {
    if (PULSE_TRACE) Log.i(PULSE_TRACE_TAG, message())
}
