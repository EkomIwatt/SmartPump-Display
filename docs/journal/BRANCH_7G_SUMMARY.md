# Branch summary — `feature/phase-7g-eeprom-totaliser`

**Written:** 2026-09-02 · **15 commits ahead of `origin/main`** · **nothing pushed yet**

Plain-language reference for what this branch contains and what is left. For the detailed
version see `TODO.md` (7g section) and `OPEN_QUESTIONS.md` (#23, #24, #25).

---

## What this branch actually is

It started as one small job — add the EEPROM totaliser the spec asks for — and picked up three
other things along the way: the Phase 7b work it was branched from, a set of firmware bug fixes,
and the preparation for Friday's live meter trial.

**Important:** this branch sits on top of Phase 7b, so it contains 7b's commits too. Merging this
branch merges 7b as well. That matters for the merge decision below.

---

## What is done

### 1. Phase 7b — pump settings screen *(inherited, already verified)*

The pump had no way to know which fuel it sells, because the backend never tells it. A manager can
now set fuel type and price on the tablet behind the attendant PIN. A pump that isn't configured
refuses to sell instead of guessing.

Also fixed a real bug: the app was seeding itself a demo price of ₦870/L in **every** build
including production, so the "no price set" safety check could never fire on a real pump.

### 2. The meter constant lives in one place now

`PULSES_PER_LITRE` — how many electrical pulses equal one litre — was defined twice, in two files,
with a comment asking someone to keep them in sync. Every litre reading and every naira cutoff
scales off this number, so two copies was one too many for a value that is about to change.

It is now in one file with a loud note that **the current value of 100 is a guess that has never
been measured**.

### 3. The two Arduino sketches became one

There were two separate firmware sketches that could not be swapped for one another:

- **Ours** — the protocol, the relay, the safety watchdog. Bench-verified back in June.
- **Olonade's** — the EEPROM pulse memory and the power-failure save. Never worked with our app.

They are now one sketch. Ours stays in charge of anything the app reads; his EEPROM half was
added on top.

### 4. Four firmware bugs found and fixed while merging

| Bug | What would have happened |
|---|---|
| Interrupts on pins 7 and 5 | **Nothing would be counted at all** — on Uno *or* Mega. Silent: no error, no warning. |
| 150 ms debounce | Counting capped at ~4 litres/min against a real pump's 30–50. Would have quietly ruined calibration. |
| EEPROM save order | A power cut *during* a save could store a good record number against a stale count — the exact failure the feature exists to prevent. |
| Power-fail order | Relay now drops *before* the memory write, not after. |

### Where those four fixes do and do not apply

**The demo ran on Olonade's own Mega, reflashed with our merged sketch.** So on *that board*, all
four are fixed, and the demo counting correctly is proof that the pin-2 interrupt path works.

**They are not fixed in his source**, which is the copy the production adapter will be built from —
and `HW-C-05` puts that on a different chip (STM32F103 or ATmega328P), so it is a fresh firmware
build, not this one. If he writes it from his sketch, both bugs return.

The pin bug is far more expensive there. On a breadboard it is a one-line change; on a laid-out
PCB with the pulse input routed to a non-interrupt pin it is a board respin. **Send him bugs 1 and
2 regardless of Friday.**

**Hardware check on that shared board:** his sketch used **D7 as the pulse input**; ours drives
**D7 as the relay output**. Anything left wired to D7 from his setup that pulls the pin low while
our firmware drives it high is an output into a short — capable of damaging the pin. D7 should
have nothing on it but the relay module or an LED.

**Its EEPROM also holds his old records** (8-byte slots against our 10-byte ones). Ours may read
one as valid and report a large number in the `BOOT` line. Harmless — only deltas are used for
counting, and calibration reads deltas too — but erase the EEPROM if clean numbers are wanted for
the run sheet.

### 5. A spec question settled by arithmetic

The spec says the adapter "stores last 10,000 pulse counts", which could mean two very different
things. It can only mean one: 10,000 records need 40 KB, and the chip the spec itself names has
**1 KB**. It is a running total, not a 10,000-entry log. That goes to Olonade as a correction
rather than a question.

### 6. Today's demo

The merged sketch ran the demo on the Mega with a push button standing in for the meter. Since
reverted to meter settings.

### 7. Friday preparation

Firmware is now configured for a real meter — correct debounce, no fake pulses, and the
optocoupler wiring documented with resistor values. `docs/FIELD_RUN_SHEET_2026-09-04.md` has the
full run sheet.

---

## What is still to do

### Before Friday

| # | Item | Who |
|---|---|---|
| 1 | **Meter output type and voltage** — reed / hall / open-collector, and voltage swing | **Kelvin — still not received. This is the blocker.** |
| 2 | Rule on which accuracy tolerance applies — the spec gives two, ten times apart | Needs a decision |
| 3 | Decide whether the relay gates real fuel on day one (recommendation: **no**) | Needs a decision |
| 4 | Test the rebuild-and-install loop at home before packing | You |

### The number that matters

**Nobody knows the meter's pulses-per-litre.** Everything the app displays or bills scales off it.
Friday's first job is to measure it. Until then the app is running on a placeholder.

### Known gaps, not yet fixed

- **Pulses counted while the tablet is off are silently discarded** (`OQ #25`). Roughly 1.5 L per
  occurrence, always lost in the station's favour. This is on `main` today, not something this
  branch introduced. Needs a decision on where those pulses should land.
- **The EEPROM totaliser has never been verified.** It ran during the demo — every completed
  dispense writes to it — but nobody checked that the count comes back correctly after a
  power-cycle.
- **Two protocol additions are waiting on Olonade**: how the sealed calibration constant reaches
  the app (`OQ #23`), and a way to mark the start of a dispense so the spec's power-cut recovery
  rule can work at all (`OQ #24`). Deliberately not invented without him.

---

## Friday morning with Olonade — working checklist

**Friday 2026-09-04, on site, same Mega (his board), demoing together.**
Full procedure is in `docs/FIELD_RUN_SHEET_2026-09-04.md` — this is the joint-session list.

### A. Decide together, before touching anything (10 min)

- [ ] **Which accuracy tolerance applies?** The spec gives two, ten times apart: `TEST-01`'s table
      says ±0.5 L on a 10 L run (**5%**), its own detail paragraph says **±0.5%**, and `METRIC-01`
      says ±0.5 L over 100 L (0.5%). This decides pass/fail. At ±0.5% the **jug** becomes the
      limiting instrument — 50 mL on a 10 L run — so check what the jug is actually certified to
      before agreeing to that bar.
- [ ] **Does the relay gate real fuel today?** *Recommendation: no.* `HW-C-01` makes the adapter a
      read-only tap; the relay is a separate output onto the dispenser solenoid. Prove counting
      first, take control of fuel later. If it does go in, brief station staff: the adapter cuts
      fuel by design if the tablet goes quiet for 3 seconds.
- [ ] **Which volumes?** The spec disagrees with itself — `TEST-01` table says 3 runs each at 10 /
      20 / 50 L; `TEST-01-detail` says 5 runs at 10 L. Prefer the table version: a K-factor error
      is *proportional*, so a 50 L run reveals what a 10 L run hides.
- [ ] Confirm the meter's output type and voltage (`OQ-05`, was owed by Kelvin) **before** the
      front end is built.

### B. Board prep (15 min, bench before the dispenser)

- [ ] **Check `D7` has nothing on it but the relay module or an LED.** Olonade's sketch used D7 as
      the *pulse input*; ours drives it as an *output*. Anything still wired there that pulls it
      low is an output into a short.
- [ ] Flash `hardware/eeprom_erase/` → wait for **solid** LED (solid = erased *and* verified;
      blinking = failed).
- [ ] Flash `hardware/smartpump_pulse_adapter/` back.
- [ ] Serial Monitor @115200 → expect **`BOOT:0*1C`** then `HB` every ~2 s.
      **A large number instead of 0 means foreign records are still being read — stop and say so
      before calibrating against it.**
- [ ] Confirm `PULSE_DEBOUNCE_US = 250` and both `ENABLE_AUTO_PULSE` / `ENABLE_BUTTON` are
      `false`. Any synthetic source left on rides on top of the meter and corrupts calibration.

### C. At the dispenser — Olonade owns this

- [ ] Identify the meter from the dispenser service manual, and **note its datasheet
      pulses-per-litre** — that is the independent cross-check against what we measure. If our
      derived K and the datasheet disagree badly, the front end is wrong, not the meter.
- [ ] Locate and tap the pulse wire in the electronics bay.
- [ ] Measure the pulse line with a multimeter before connecting anything.
- [ ] Build the 4N35 front end — 220–270 Ω on a 5 V line, 680 Ω–1 kΩ on 12 V. **Meter ground stays
      separate from Arduino ground**; that separation is the whole point of the isolation.

### D. Then calibrate — see the run sheet

Phase A derives K (3 runs), phase B verifies with **fresh** runs. They cannot be the same runs —
validating a constant with the data you derived it from always passes and proves nothing.

### E. Hand to Olonade for the production board

These outlive Friday. The production adapter is a **different chip** (`HW-C-05`: STM32F103 or
ATmega328P) and a fresh firmware build, so nothing fixed in our sketch carries over by itself.

- [ ] **Torn-write ordering.** His layout puts `sequence` before `pulseCount`, and `EEPROM.put()`
      writes ascending — so a cut *during* a save can commit a new highest sequence against a
      stale count, and recovery then elects exactly that record. Ours reorders to
      `{magic, pulseCount, sequence, crc}` with the CRC last as a commit marker.
- [ ] **Format marker.** A CRC alone lets foreign bytes through ~1 in 65,536. Worth carrying
      `SLOT_MAGIC` into his firmware, bumped whenever the record layout changes.
- [ ] **Power-fail ordering** — drop the relay *before* the EEPROM commit, not after.
- [ ] **`HW-C-04` is wrong and should be corrected, not queried.** "Stores last 10,000 pulse
      counts" cannot mean a 10,000-entry log: that needs 40 KB, and the ATmega328P named in
      `HW-C-05` has **1 KB**. It is a running total. Still worth asking what "10,000" was meant to
      size — it is only ~100 L at the placeholder K-factor.
- [ ] **`OQ #23` — the `CAL` frame.** `HW-C-08` seals the pulse-per-litre constant on the board,
      but there is no way for it to reach the app: `BOOT` parses as a single number, so a
      two-field `BOOT` breaks the parser. Proposal is a separate `CAL:<ppl>*<cs>` frame.
      **Agree this before he writes the production firmware.**
- [ ] **`OQ #24` — the session mark.** `SW-05`'s `max(adapter_eeprom, android_persisted)` is not
      implementable as written: his totaliser is lifetime-scoped, the app's count is
      per-transaction, so a literal `max()` always returns the lifetime value. Needs the app to
      signal session-zero at relay-open and the adapter to record the total at that mark.

*(Bugs 1 and 2 — interrupt pins and the 150 ms debounce — are already handled between you.)*

---

## Is the branch clear for merge?

**Half of it, yes. Not all of it.** Recommend splitting.

### ✅ The first 7 commits — clear to merge now

Everything through `9dfbede`: Phase 7b, the constant collapse, and the docs. Fully verified —
125 JVM tests green, 12 instrumented tests green on the tablet, both build variants compile. 7b
was already marked merge-ready before this branch existed.

### ⏸ The last 8 commits — hold until Friday

The firmware half. The build is green and the sketch ran today's demo, so the protocol, relay,
watchdog and pulse-counting path are all exercised. But:

- **The EEPROM totaliser is unverified.** Compiling is not evidence.
- **Project precedent is against it.** Phase 7a-hardening was held behind two explicit merge gates
  until both were verified on a real device. The same standard applies here.

Friday's trial *is* the verification. Merging after it means `main` gets firmware that has been
proven against a real meter, rather than firmware that has only been proven to compile.

**If you want it merged sooner**, the gate is about five minutes of bench work: note the count in
the `BOOT` line, run one dispense, power-cycle the board, and confirm the new `BOOT` count is
higher rather than back at zero. Also confirm the next sale still starts from zero litres on the
tablet — that second check is the one that would show up as a customer being billed for the
pump's entire service life.

### Also worth doing

**Nothing is pushed.** 15 commits exist only on this laptop, including the Friday run sheet. Push
before Friday so the work is not sitting on a single machine going to a fuel station.

---

## Current state

```
main                              = origin/main  (unchanged, pushed)
feature/phase-7b-operator-config  = +7 commits   (verified, merge-ready)
feature/phase-7g-eeprom-totaliser = +15 commits  (includes the 7 above)

Build: 125 JVM tests / 17 classes green
       compileDebugKotlin + compileDebugRealHwKotlin clean
       firmware compiles clean (-Wall) for atmega2560 and atmega328p
Working tree: clean
```
