// The flow meter's K-factor — how many electrical pulses the meter emits per litre of fuel.
// This is the single conversion between what the hardware counts and what the customer is
// billed for, so it is defined exactly once, here, and every litre figure in the app derives
// from it.
package app.balancee.smartpump.display.domain.hardware

/**
 * TODO(OQ #1 / T-01) — **`100` IS A PLACEHOLDER, NOT A MEASURED VALUE.**
 *
 * It was inherited from the original mock and has never been checked against a real meter.
 * Every litre reading, every naira-to-litres cutoff and every audit row scales linearly off
 * this number: if the real meter is 450 pulses/L (a common small-bore figure) the app would
 * bill 4.5x the fuel actually delivered. **Do not guess a replacement.**
 *
 * The value comes from execution-tracker task **T-01**: five runs of exactly 10 L into a
 * calibrated measuring jug, recording run number / actual / screen reading / variance, to a
 * **±0.5% tolerance**. Olonade owns identifying the meter from the dispenser service manual
 * and tapping the pulse wire.
 *
 * **This constant is bench scaffolding and is expected to be deleted.** Prototype Specification
 * v1.0 (Hardware → pulse-tap adapter board) makes the pulse-per-litre constant a *sealed*
 * value, loaded onto the adapter at commissioning and reported by the board — SON/NIS 348
 * metrology requires that the app treat it as read-only rather than hold an editable copy.
 * When the sealed board exists, this is replaced by the value carried in the BOOT frame; it
 * must **never** be moved into operator config, which is an editable tamper surface.
 */
// Back to the placeholder after the 2026-09-02 push-button demo (which ran at 2).
//
// STILL A PLACEHOLDER, STILL NOT MEASURED. The first live trial against a real meter must begin
// with TEST-01 / T-01 — runs of exactly 10 L into a calibrated jug, +/-0.5% — and this value
// replaced by the result before any figure the app shows can be trusted.
//
// **Double, not Int, and that is load-bearing.** A measured K-factor is not a whole number. Held
// as an integer, the rounding alone costs up to 0.5 pulses — which at ~100 pulses/L is a **0.5%
// error before the meter is even involved**, i.e. the whole TEST-01 tolerance spent on a type
// declaration, and at a coarser ~50 pulses/L it is 1% and fails outright. Worked examples:
// a measured 98.5 stored as 98 is 0.508% (already a fail); 98.7 stored as 99 is 0.304%.
// Enter the measured value exactly as calculated — do not round it to something tidy.
const val PULSES_PER_LITRE = 100.0
