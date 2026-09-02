# Field run sheet — meter integration + TEST-01 calibration

**Date:** Friday 2026-09-04 · **Site:** live fuel station · **Branch:** `feature/phase-7g-eeprom-totaliser`

The goal of the day is **one number**: the meter's pulses-per-litre constant. Everything the app
displays or bills scales off it, and it has never been measured. Accuracy verification is a
*second* activity and must not reuse the same runs — see §4.

---

## 0. Two things to settle BEFORE leaving

### 0a. Which tolerance applies? The spec gives two, ten times apart

| Source | Says | At a 10 L run that means |
|---|---|---|
| `METRIC-01` | ±0.5 L over **100 L** | 0.5% |
| `TEST-01` (table) | ±0.5 L over **10 L**, 20 L, 50 L | **5%** |
| `TEST-01-detail` | **±0.5%**, and *"if any run fails ±0.5% the demo is rescheduled"* | **0.05%** of nothing — 0.05 L |

`TEST-01`'s ±0.5 L on a 10 L run is **5%**, ten times looser than the ±0.5% the same test's detail
paragraph demands. Get a ruling before you drive out, because it decides pass/fail and one of the
two readings requires reading a jug to **50 mL**.

Practical note: at ±0.5% the *jug* becomes the limiting instrument, not the meter. If the jug is
not certified to better than 0.05 L at the 10 L mark, a "failure" may be the measurement, not the
system.

### 0b. Is the relay gating real fuel on day one?

`HW-C-01` makes the adapter a **read-only** tap on the pulse line. The relay is a separate output
that cuts the dispenser solenoid. **Recommendation: do not wire the relay into the dispenser on
the first visit.** Tap the pulse line, observe, calibrate. Prove counting is right before taking
control of fuel flow at a live station with customers on site. Leave `D7` driving the LED only.

If the relay *is* going in, the watchdog behaviour has to be briefed to station staff first: the
adapter cuts fuel by design if the tablet stops talking to it for 3 seconds.

### 0c. Still outstanding

- **Meter output type and voltage — from Kelvin, not yet received** (`OQ-05`). Reed switch, hall,
  or NPN open-collector? Voltage swing? Does it need external excitation? Without this the front
  end cannot be built correctly and the visit may be wasted.
- Olonade owns identifying the meter in the dispenser service manual and locating the pulse wire.

---

## 1. Pack

- Laptop with this repo, Arduino IDE, and **`adb` working** (test `installDebugRealHw` before leaving)
- Arduino Mega + USB cable (and a spare cable)
- **4N35 optocouplers** + resistors: 220–270 Ω (5 V line) and 680 Ω–1 kΩ (12 V line)
- Breadboard, jumpers, multimeter
- **Calibrated measuring jug** — and its calibration certificate if the 0.5% reading applies
- Tablet (SM-T220), charged, `debugRealHw` already installed
- Phone for video — `TEST-01-detail` requires video evidence

**Test the full rebuild-and-install loop at home first.** On site you will be editing one constant,
rebuilding and reinstalling; that is not the moment to discover a toolchain problem.

---

## 2. Wiring

Meter pulse line → **4N35** → `D2`. Never straight to the pin: `HW-C-02` requires 5 V *and* 12 V
tolerance and 12 V on an AVR input destroys it.

```
meter pulse (+) --[R]-- 4N35 pin 1 (anode)      R = 220-270R at 5V, 680R-1k at 12V
meter ground ---------- 4N35 pin 2 (cathode)
D2 -------------------- 4N35 pin 5 (collector)
Arduino GND ----------- 4N35 pin 4 (emitter)
```

**Do not bond meter ground to Arduino ground.** Keeping them separate is the entire point of the
2500 V isolation (`HW-C-06`).

Measure the meter's pulse line with the multimeter before connecting anything, to confirm the
voltage matches what Kelvin said.

---

## 3. First light — is it counting at all?

Mega → **laptop** USB. Serial Monitor at **115200**.

1. Expect `BOOT:<n>*<cs>` then `HB:<n>*<cs>` every ~2 s. The count in `HB` is the free-running
   lifetime total and is what you read for calibration.
2. Dispense a small amount. `PULSE:` frames should stream and the number should climb.
3. **Sanity-check the rate before trusting anything.** Note the count, run 10 seconds of flow, note
   it again. `pulses ÷ seconds` = pps. Cross-check against the dispenser's own flow rate:
   `expected_pps = L_per_min ÷ 60 × pulses_per_litre`. If pps looks implausibly low, suspect the
   debounce (`PULSE_DEBOUNCE_US`, currently 250 µs) or a marginal opto drive current.

If nothing counts: check opto orientation, resistor value, and that the meter is actually pulsing
(scope or multimeter on the line while flowing).

---

## 4. TEST-01 — derive, then verify. They are different runs.

**This is the part most likely to be got wrong.** You cannot validate a constant using the runs you
derived it from — the arithmetic is circular and will always "pass". Two phases:

### Phase A — derive K (3 runs, 10 L each)

For each run: note `HB` count → dispense exactly 10 L into the jug → note `HB` count.

| Run | Pulses before | Pulses after | Δ pulses | Actual L (jug) | Δ ÷ actual = K |
|---|---|---|---|---|---|
| A1 | | | | | |
| A2 | | | | | |
| A3 | | | | | |

**K = mean of the three.** If the three disagree by more than the tolerance from §0a, stop — the
front end or the debounce is wrong, and no amount of averaging fixes a systematic error.

### Phase B — apply K, then verify with FRESH runs

1. Edit `PULSES_PER_LITRE` in
   `app/src/main/java/app/balancee/smartpump/display/domain/hardware/MeterCalibration.kt`
2. `./gradlew :app:installDebugRealHw` (tablet on the laptop's USB)
3. Move the Mega's cable to the tablet, grant USB permission
4. Run `TEST-01` proper — **3 runs each at 10 L, 20 L and 50 L**, reading litres off the Android
   screen this time, not off Serial Monitor

| Run | Target L | Actual L (jug) | Screen reading | Variance | Pass? |
|---|---|---|---|---|---|
| B1 | 10 | | | | |
| B2 | 10 | | | | |
| B3 | 10 | | | | |
| B4 | 20 | | | | |
| B5 | 20 | | | | |
| B6 | 50 | | | | |
| B7 | 50 | | | | |
| B8 | 50 | | | | |

> **Note the spec disagrees with itself on run count too:** the `TEST-01` table says 10/20/50 L
> × 3 runs each (9 runs); `TEST-01-detail` says 5 runs at 10 L. The table version is the stronger
> test — a K-factor error shows up as a *proportional* error, so a 50 L run reveals what a 10 L run
> hides. Run the table version if there is fuel and time for it.

**A proportional error across all volumes means K is wrong** — recompute and repeat Phase B.
**A fixed offset regardless of volume** means something else: a missed first pulse, or a
start/stop edge effect.

---

## 5. Record for the file

`TEST-01` requires: test ID, date, who ran it, pass/fail, and evidence (video or CSV). Both tables
above, plus video of the runs. `TEST-01-detail` is explicit that failing data is not presented and
the demo is rescheduled instead.

Commit the completed sheet to this repo on the day.

---

## 6. What is NOT being tested Friday

- **The EEPROM totaliser has never been flashed or bench-run by anyone.** It is in the sketch and
  compiles, but Friday is not its test. If it misbehaves, set `ENABLE_POWER_FAIL_SAVE = false`
  (already the default) and carry on — the totaliser only writes on `RLY:0` and on a watchdog trip.
- Power-cut recovery (`TEST-02`/`TEST-12`) — needs the UPS and a controlled cut.
- The known recovery gap (`OQ #25`): pulses counted while the tablet is down are currently absorbed
  into a new baseline rather than landing anywhere explicit. Unfixed. Do not power-cycle the tablet
  mid-run and expect the count to be right.

---

## 7. Abort conditions

Stop and reschedule rather than push on if:

- The meter's pulse line voltage does not match what the front end was built for
- Phase A runs disagree with each other beyond tolerance (systematic error, not noise)
- Counting stops or jumps during a run (bad connection, or debounce swallowing pulses)
- Anything requires connecting the pulse line without the optocoupler

`TEST-01-detail`: *"If any run fails ±0.5%, the demo is rescheduled — failing data is not
presented."*
