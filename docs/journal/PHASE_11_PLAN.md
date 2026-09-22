# Phase 11 — the adapter owns the cutoff (plan)

_Planned 2026-09-22. **11a DONE 2026-09-22** — [`docs/serial-protocol.md`](../serial-protocol.md)
confirmed by the user. It departs from the draft below in three places, and the spec wins: the
session is **tagged by the app** (`RLY:1:<limit>:<tag>`, `ARM:<tag>:<start>`, `STOP:<tag>:<cut>`,
`RES:<tag>`); **#38 is closed as superseded**, not folded in; and the session is **saved to EEPROM
behind `ENABLE_POWER_FAIL_SAVE`** (off until Olonade's Friday session). **11b DONE 2026-09-22**
(`5e073f6`, compiled for Uno + Mega, `hardware/host_test/` passes; not flashed). **11c DONE
2026-09-22** (`f0233e5`, 554 green). **11d DONE 2026-09-22** (`8bf9245`, 585 green — see the log for
what moved from 11d's list into 11e). **11e DONE 2026-09-22** (`ac4f1e4` Room v6, `4b4c103` view
model; 599 green; the instrumented 5→6 migration test not yet run). **Next: 11f, the bench gate —
the user's, on the rig.**_

This is a plan, not a log. Completed sub-deliverables get logged in `PROJECT_LOG.md` as usual.

## Why

**OQ #26 was agreed on 2026-09-22 by Olonade and the boss** (reported by the user): the app sends the
start and the limit, the adapter stops the relay itself and reports back. The user writes the
Arduino code, so both halves are ours to build. Three problems go at once:

| Problem | Today | After |
|---|---|---|
| **App death mid-sale** | nothing stops fuel but the 3 s watchdog — **~1.5 L** given away at the 7h gate (step 8) | the board stops at the limit whether or not the app is alive |
| **Stop latency** | a USB round trip — **35–100 mL** per fixed sale, up to 1% of a 10 L calibration run (twice the ±0.5% gate) | only the mechanical term (relay + solenoid + coast) |
| **#36 — pulses lost at attach** | the app baselines on the first `PULSE` frame it happens to catch after the relay opened — a few pulses per sale (inferred), **~22 per restart** (measured) | the board latches the sale's start count (the "trip meter", OQ #24's session mark); nothing falls in the gap |

Root cause of #36, confirmed in code 2026-09-22: `CustomerViewModel.startDispensing` calls
`relay.startFuelFlow()` *before* `pulseSource.observe().collect`, and each collection builds a fresh
`PulseAccumulator` whose `!initialised` branch returns 0 for the first frame — whose cumulative
already includes everything since the relay opened.

## Scope

- **In:** the protocol revision (limit on open, board-latched session start, board-initiated stop
  report), the firmware, the app side, the mocks, and one bench session that also closes the
  **7g firmware gate** (#19).
- **Fixed flows** (Flow 1 pre-pay, Flow 4 cash-fixed, USSD): the board stops at the limit.
- **Fill-ups** (Flows 2 and 3): no target, so a large **ceiling** as a runaway backstop. The 3 s
  nozzle-idle shutoff stays in the app for V1 (moving it to the board is a 11a decision, below).
- **Out:** OQ #23 (`CAL` frame / sealed K-factor) — the limit is in **pulses**, so this does not wait
  on it. The K-factor measurement itself (#22, Kelvin). Release signing.

## Starting point — read before 11b

- **Firmware base is the 7g sketch**, not `main`'s. `main`'s `hardware/*.ino` is the pre-7g
  (7a-hardening) sketch. The 7g branch (`origin/feature/phase-7g-eeprom-totaliser` = `b304a0a`) is
  ~30 commits behind `main`: **bring in its `hardware/` files only** (`git checkout
  origin/feature/phase-7g-eeprom-totaliser -- hardware/`) — a whole-branch merge conflicts on
  `OPEN_QUESTIONS.md` / `BRANCH_7G_SUMMARY.md` and silently reopens settled OQ #22/#25 via an old
  `TODO.md`. Read [`BRANCH_7G_SUMMARY.md`](BRANCH_7G_SUMMARY.md) and the branch's
  `hardware/README.md` (not `main`'s).
- 7g re-pins the rig: button 3 → 4, pin 3 becomes power-sense, debounce 150 ms → 250 µs.
- App seams: `RelayController.startFuelFlow()` (no argument today),
  `UsbSerialRelayController`'s reconnect re-assert (sends a bare `RLY:1` on the link's down→up
  edge), `SerialFrame` (`Pulse` / `Heartbeat` / `Boot` / `Error` / `Invalid`, each a single numeric
  payload), `UsbSerialPulseSource.observe()`, the 7h reconciliation (`reconcileGapOnResume`),
  `pulse_state` (Room, schema **version 5**).

## Sub-deliverables

One branch, `feature/phase-11-adapter-cutoff`, off `main`. Committed per sub-deliverable; every
commit leaves the build green.

### 11a — protocol spec (a document; the user confirms it before any code)

`docs/serial-protocol.md`: every frame both ways, checksums worked out, and the behaviour on every
edge below. Our draft, to be confirmed or changed:

- **App → board `RLY:1:<limit>*<cs>`** — energise, latch `sessionStart = count`, arm
  `stopAt = sessionStart + limit`. Limit in **pulses**, never litres (the app stays sole owner of
  the K-factor and the naira maths). **One frame, one checksum** — never a separate arming step,
  or a dropped arm frame means a prepaid sale with no ceiling.
- **Every `RLY:1` must carry a limit.** A bare or malformed one → `ERR:CMD` and **no fuel**.
  Fail-to-no-fuel, never fail-to-unlimited.
- **Board → app `ARM:<sessionStart>*<cs>`** — acknowledges the open with the latched start. The app
  computes the sale's pulses as `cumulative − sessionStart`, exactly, from the board's own number.
  **This is what retires #36.**
- **Board → app `STOP:<cum>*<cs>`** — the board cut off at the limit. EEPROM commit from `loop()`,
  never the ISR (as `RLY:0` and `ERR:WDOG` already do). The cutoff test itself runs **in the pulse
  ISR** (`count >= stopAt` → relay low; `onPowerFail()` is the precedent for GPIO in an ISR).
- **Resuming after a comms drop — decide here; this is the trap that bites in production.** Today's
  re-assert re-sends `RLY:1`; with a limit attached, re-sending the *original* limit hands a sale
  that stopped at 9 of 10 L a fresh 10 L. Two candidates:
  1. **`RES*<cs>` — resume the session the board still holds** (same `sessionStart`, same
     `stopAt`). The limit never leaves the board, so there is nothing for the app to get wrong. If
     the board rebooted and lost the session → `ERR:NOSESSION`, and the app falls to power-cut
     recovery. **Recommended.**
  2. `RLY:1:<remaining>` from a live remaining-count in the app — but that starts a new session and
     resets the trip meter, which undoes the #36 fix.
- **Session through a power cut:** is `sessionStart`/`stopAt` persisted to EEPROM at arm time (one
  extra write per sale — check against the wear budget), or is a power cut mid-sale left to the 7h
  reconciliation? Decide with the numbers.
- **Restart without the `ARM`:** if the app dies between sending `RLY:1` and reading `ARM`, how does
  it learn `sessionStart`? Candidate: a query `SES?*<cs>` answered by `ARM:<start>` (or
  `ERR:NOSESSION`).
- **Fill-up ceiling value**, and whether the nozzle-idle shutoff moves to the board too (it would
  bound an app crash on a fill-up as well — but the V1 default is to leave it in the app).
- **Fold in #38?** Watchdog 3 s → 2 s is a one-constant firmware change; decide it here rather than
  in a separate sitting.
- **Compatibility:** app and firmware ship together, so no back-compat with the bare `RLY:1` — but
  say so explicitly, and say what an old app does against new firmware (no fuel — safe).

### 11b — firmware

On the 7g sketch. **Build to [`docs/serial-protocol.md`](../serial-protocol.md), not to the draft
bullets above** — including the tagged frames, the 25-byte record with the session in it, and the
restore rule (§6.4) behind `ENABLE_POWER_FAIL_SAVE`, which ships `false`. The frames from 11a, the ISR cutoff, `STOP` + EEPROM commit from `loop()`, the
resume path, `ERR:CMD` on a bare `RLY:1`. Update the header comment (it currently says the session
mark is "deliberately NOT invented here — pending Olonade") and `hardware/README.md`.

### 11c — parser and frames (app)

`SerialFrame.Arm` / `SerialFrame.Stop` (both single-numeric, so no parser surgery — unlike the
`BOOT:<cum>:<ppl>` problem in OQ #23), the outbound `RLY:1:<n>` / `RES` / `SES?` writers, and their
checksums. Tests against the worked checksums in `serial-protocol.md`.

### 11d — relay controller + pulse source (app)

- `RelayController.startFuelFlow(limitPulses: Long)`; the reconnect re-assert sends the 11a resume
  frame, never a fresh limit.
- `UsbSerialPulseSource`: the sale's count is `cumulative − sessionStart` once `ARM` arrives.
  **Fallback** while `ARM` has not arrived (or the board is unknown): seed from the adapter count
  read before the relay opened — never from the first frame — so the #36 gap cannot reopen.
- Surface `STOP` as a `PulseMessage` the VM can act on.
- Mocks: `MockRelayController` / `MockPulseSource` emulate the board's limit and `STOP`, so debug
  builds and the unit tests exercise the same path as the rig.

### 11e — view model (app)

- Fixed flows pass `floor(litresCutoff × PULSES_PER_LITRE)` as the limit; fill-ups pass the ceiling.
- A `STOP` completes the sale exactly as the app's own cutoff does today.
- **Keep the app-side cutoff as a backstop** — two independent cutoffs, both fail-safe. If the
  app's ever fires first, that is a defect: write an event row, loudly, rather than papering over it.
- Boot resume: persist `sessionStart` (likely a `pulse_state` column → migration 5→6) and resume
  via 11a's resume/query frames; the 7h gap reconciliation stays for the no-session case.
- Tests first on the resume path (house rules on `TODO.md`: re-run each new test against the pre-fix
  code; a fake that never suspends cannot see a cancellation; check the sibling flows).

### 11f — the bench gate (the user, on the rig)

**RAN 2026-09-22 — PASSED, 7 of 8.** Steps, expectations and results:
[`11F_RUN_SHEET.md`](11F_RUN_SHEET.md); the narrative is in the log. Step 5 (unplug/replug) is
partial — a flat bench battery rebooted the Uno — and moves to Friday's Mega along with the
nozzle-idle shutoff and the real coast figure.

One sitting, the Arduino rig, **pin 2 tied to 5 V** (a floating pin counts noise as fuel). logcat
is unusable during an Arduino run (one USB-C port), so bring back the 7h on-screen trace by
reverting `3631b38` on a throwaway build, and read results off the screen / the tablet's DB
afterwards.

1. **7g gate:** note the `BOOT` count, one dispense, power-cycle — the count comes back **higher,
   not zero**; the next sale still starts from **zero litres** on the tablet.
2. **Board-owned stop:** a fixed pre-pay ends on `STOP`, litres = the limit.
3. **App killed mid-sale:** fuel stops at the limit (or at the watchdog, whichever is first) —
   never beyond the limit. Compare with 7h step 8's ~1.5 L.
4. **#36:** one sale, three restarts — the board-minus-app offset **stays flat** (7h measured
   2389 → 2391 → 2414 → 2436 → 2456).
5. **USB unplug/replug mid-sale:** the sale resumes and still stops at the **original** limit — no
   fresh allowance.
6. **Bad frame:** a malformed / bare `RLY:1` → `ERR:CMD`, no fuel.
7. **Fill-up:** still ends on nozzle idle; the ceiling is not hit in normal use.
8. **Coast:** after each `STOP`, note the `HB` count once flow settles minus `cut` — the fuel that
   flows after the cut (spec §6.5). TEST-01 will want its size.

**Then, on Olonade's Mega (Friday, the last session before the 14-day run):** confirm his
power-sense line is on pin 3 and goes high on power loss, and that his capacitor holds the rail for
**~85 ms** (the 25-byte record's worst-case commit); flip `ENABLE_POWER_FAIL_SAVE` to `true`;
cut the power mid-sale and check the sale comes back with its exact count and still stops at its
**original** limit; then cut it with the sense line disconnected and check the session is
**discarded** rather than resumed (spec §6.4). The board's 7g totaliser restarts from zero once on
first boot of the new record layout — do that before the run, not during it.

Then merge to `main`: the firmware, the app, and 7g's totaliser together.

## What this retires on the board

- **#36** — fixed by design (the separate app-only fix is not built; 11d's fallback covers it).
- **OQ #26** — built. **OQ #24** — the session mark, built as `ARM`.
- **#19 / the 7g firmware gate** — closed by 11f step 1.
- **#38** — if folded in at 11a.
- **Not** OQ #23 (`CAL`) or #28 (the gap ceiling, which waits on the real K-factor).
