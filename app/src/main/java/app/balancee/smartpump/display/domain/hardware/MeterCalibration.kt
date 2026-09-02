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
// ============================================================================================
// >>> DEMO VALUE 2026-09-02 — NOT 100. REVERT BEFORE ANY METER WORK. <<<
//
// Set to 2 (one button press = 0.5 L) for the push-button demo, where the button is wired to the
// adapter's interrupt pin and each press is one real pulse. At the usual 100 a single press would
// be 0.01 L, so a 5.75 L pre-pay would need 575 presses.
//
// This is a DEMO-ONLY figure on the 7g branch. It must go back to the placeholder 100 — and then
// to the measured T-01 value — before the meter is connected. Paired with PULSE_DEBOUNCE_US =
// 150000 in the firmware; the two only make sense together.
//
// Consequence while it is 2: litre granularity is half a litre, so a cutoff of 5.74 L is reached
// on the press that crosses it. The never-over-dispense guard still holds and the app will not
// report more than was paid for.
// ============================================================================================
const val PULSES_PER_LITRE = 2
