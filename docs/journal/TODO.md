# SmartPump Display — TODO

Living work board. The **PROJECT_LOG** records what's *done*; this file tracks what's *outstanding*.
Keep it current: check items off, add follow-ups as they surface, and move finished work to
[`TODO_DONE.md`](TODO_DONE.md) (and the log).

**Legend:** `[ ]` open · `[~]` in progress · `[x]` done (then move to [`TODO_DONE.md`](TODO_DONE.md)) · `[·]` deferred/parked · `[→]` moved to [`POST_V1.md`](POST_V1.md)

_Last updated: **2026-09-22**. **V1 is the priority.** This board is the road to V1 and holds only
open work. **Non-blocking improvements live in [`POST_V1.md`](POST_V1.md)**; **finished work lives in
[`TODO_DONE.md`](TODO_DONE.md)** (moved there verbatim 2026-09-22, item numbers unchanged).
**Next session: Phase 11, 11a** (V1 path, item 5)._

### ⛳ Start here — the V1 path

V1 = the **14-day parallel run** on a production-shaped release build at under 1% daily variance
against station stock records, then live money. [`V1_BLOCKERS.md`](V1_BLOCKERS.md) sorts the same
work by *who is holding it up*; this list is the order to act in. Pointers only — the detail is in
each entry.

1. [x] **#R11 — DONE 2026-09-22.** Device check passed on the tablet (the timed-out card cleared
   itself two minutes after expiry); merged to `main` (`08adf2f`). Detail in `TODO_DONE.md`, item 4a.
2. [x] **#R8 (pre-pay half) — DONE 2026-09-22.** The pre-pay QR's Cancel now writes a "cancelled"
   `PAYMENT_ABANDONED` row (server id, checkout figure); device-checked on the tablet, merged
   (`9045c3b`). Detail in `TODO_DONE.md`, item 5. (#51 went to `POST_V1.md` the same day.)
3. [x] **Tell Balancee about the orphan ₦149 sale** (item 10) — **told by the user 2026-09-22.**
4. [ ] **The K-factor — the long pole.** Chase Kelvin for the meter's output type and voltage
   (**#22**, OQ #1). Nothing measurable runs until it is known; **#28** and **#21** follow from it.
5. [~] **Phase 11 — the adapter owns the cutoff. 11a DONE 2026-09-22; NEXT: 11b (firmware),
   awaiting go.** Branch `feature/phase-11-adapter-cutoff`. Spec confirmed:
   [`docs/serial-protocol.md`](../serial-protocol.md). OQ #26 agreed by Olonade and the boss; the
   user writes the Arduino code. Plan: [`PHASE_11_PLAN.md`](PHASE_11_PLAN.md). **Olonade's Mega has
   the power-sense circuit** — one more session on it on **Friday 2026-09-25**, before the 14-day
   run, to switch on `ENABLE_POWER_FAIL_SAVE`. Retires **#36**, OQ #24/#26 and the **7g firmware gate (#19/#24)**, and
   possibly **#38**. OQ #23 (`CAL`) stays open.
6. [ ] **Accuracy before the run:** ~~**#36**~~ (folded into Phase 11), and the fact that no build
   type is yet both real hardware and production (the "no build type" entry under "Open items carried from finished phases").
7. [ ] **Robustness:** **#42** (a RuntimeException in the OkHttp chain kills the process), **#15**'s
   enforcement half (clock skew), **#44**.
8. [ ] **The run's own needs:** **#40** (get the records off a release build) before the run ends,
   and **#34** (release signing) — deferred to last by decision, but a prerequisite of the run.

### House rules that bit on this branch — read before starting

- **Check the siblings.** In five consecutive rounds a defect was fixed in one flow and left
  standing in another. On this branch that is not diligence, it is the single highest-yield step.
- **Re-run each new test against the pre-fix file** and confirm it fails. This round it caught a
  test that passed for the wrong reason (it leaked an uncaught exception into the *next* test).
- **Ask what runs after a guard you add.** Both defects introduced by fixes on this branch were
  guards that changed control flow when they fired.
- **A coroutine that cancels itself stops at its next suspension point.** #R9 was a self-cancel with
  a suspending Room write behind it, so everything after that line — including the transition that
  ended the sale — silently did not happen. Before cancelling a job, ask whether it is *this* job.
- **A row with two writers is written column by column.** #R12: read-modify-write of a whole row
  from two coroutines is a lost update waiting for its moment, and on the tablet the moment was the
  first one. If two things own parts of a record, neither may write the other's part.
- **Two timers on one deadline is one timer too many.** #R10: both digital flows armed a countdown
  and a poll deadline off the same `expiresAt`, each assuming it would be the one to end the sale.
  Whenever two things can end the same sale, ask which one actually does — on a device, not in a
  test, because `delay` and the wall clock diverge exactly where it matters.
- **A fake that never suspends cannot see a cancellation.** Three tests were incapable of failing
  until `FakeEventRepository` got one `yield()`. When the thing under test is coroutine-shaped, the
  fake has to be too.
- `lintDebug` and `assembleDebugProd` must be **separate** gradle invocations — together they race
  on generated Hilt sources and lint dies with an internal error that is not a code defect.
- Wait for an explicit **"go"** before starting a new phase; commit per logical sub-deliverable.

_(previous: 2026-09-20 — 10g's gate passed on real money, review #1 found 8, the re-review found 8
more; R1/R2 fixed that evening, R4/R5/R6 the next day.)_

> **Sorted by who is holding it up:** [`V1_BLOCKERS.md`](V1_BLOCKERS.md) is the same work viewed by
> blocker rather than by phase — useful for "what can move today". It points back here; it does not
> restate detail, so this file stays the source of truth for work items.

---

## Open items carried from finished phases

_Collected 2026-09-22 when the finished phase sections moved to [`TODO_DONE.md`](TODO_DONE.md). Each entry is verbatim; its original section is named above it._

### from API conformance

- [~] **15. Clock skew unguarded (MED) — MAPPING HALF DONE 2026-09-19, enforcement half open.**
  ±5 min or every request 401s.
  - ~~**Mapping half is ready and rides on #14.**~~ ✅ **DONE (phase 10e).** A clock-skew 401 no
    longer reads as rejected credentials: the attendant is sent to automatic date and time rather
    than to re-activation, which is the one screen that cannot fix it. It is matched on the message
    because the server sends **no code** on that path — the one prose match in the mapper, and it
    earns the exception by having been observed twice on production at the #32 gate rather than
    quoted from the PDF. A rewording degrades to the credentials line: terminal, and still pointing
    at a person. `Invalid request timestamp` (malformed, not skewed) arrives as `INVALID_REQUEST`
    and is already keyed on its code.
  - **Enforcement half has no home yet.** The app is not a device-owner app (kiosk lock-task still
    deferred), so it **cannot set the clock itself**; the most it can do is read
    `Settings.Global.AUTO_TIME` and warn. Whether that gate lives in the debug screen now, waits for
    the activation flow (#8), or becomes a physical install-checklist item is an open call.

### from Phase 9

- [ ] **42. A RuntimeException inside the OkHttp chain kills the process, not just the call.**
  Found 2026-09-16 when the missing INTERNET permission surfaced as
  `SecurityException` at DNS lookup: the app died mid-activation rather than reporting a failure.
  - **Why `safeApiCall` did not save us.** Retrofit's `suspend` path uses `enqueue`.
    `RealCall.AsyncCall.run` catches `IOException` and calls `onFailure`; for any other `Throwable`
    it calls `onFailure` **and then rethrows**, which reaches the default uncaught handler and takes
    the process down. So the coroutine *was* told, and the app died anyway.
  - **Why it matters beyond this bug.** The permission gap is fixed and DNS failures are
    `UnknownHostException` (an `IOException`), so the trigger is gone. But this is a kiosk that must
    not vanish mid-sale, and **activation is the worst possible moment to die**: the operator is left
    unable to tell whether the code was spent, which is precisely the ambiguity
    `ActivationOutcome.Unreachable` exists to make explicit.
  - **Shape of a fix:** give OkHttp a `Dispatcher` backed by an `ExecutorService` whose thread
    factory installs an `UncaughtExceptionHandler`. The call still fails, the coroutine still gets
    its `IOException`, but the process survives. Small, and testable by throwing from a stub
    interceptor.

- [~] **44 (original). `AuthoriseRequest.amount` must stop being a `Long`.** #18c is answered: the server accepts
  a decimal amount and its exact `amount == expectedLitres × pricePerUnit` check passes on one
  (3501.5 for 2.35 L at ₦1490, request and response both captured).
  - **Why it cannot stay:** at any price, most metered litre figures produce a fractional naira amount.
    A `Long` cannot carry it, and rounding is refused rather than tolerated, so every fill-up would be
    unauthorisable. The alternative — constraining station prices to whole naira so the product is
    always whole — is a business constraint we no longer have to ask for.
  - **Not a `Double`.** Money through binary floating point is how a check for *exact* equality starts
    failing on figures that look right. `BigDecimal` with a serializer, or an integer of kobo
    serialised as a decimal — decide when #8 builds it, but decide deliberately.
  - **Open, and dormant rather than answered: precision.** 3501.5 is one decimal place. The app carries
    kobo, so it cannot express more than two — yet `price × litres` exceeds two whenever the price is
    not a multiple of ten (₦1491 × 2.357 L = ₦3,514.287). Today's ₦1490 hides it. The probe is litres
    **2.3571** → 3512.079.

### from Phase 7h

- [ ] **28. `MAX_PLAUSIBLE_GAP_PULSES = 400` needs a THIRD term, not just re-deriving.** Still true
  that it must be recomputed once the real pulses-per-litre is known (OQ #1) — but the 2026-09-13
  bench run showed the derivation is also **structurally short**. It assumes the anchor is at most
  `PULSE_PERSIST_EVERY_N` (25) pulses stale. The anchor is written every 25 pulses **as processed by
  the app's collector**, and on the SM-T220 that collector falls behind the board: measured gaps
  reached **307 pulses** where the three-second watchdog window alone allows ~150. So the real
  staleness is bounded by collector lag, not by the save interval, and 400 is tight enough to refuse
  genuine fuel — a **4.48 L gap was rejected on the bench and was almost certainly real**. Rejecting
  under-bills, so it fails safe, but the station absorbs it. Add a lag term when recomputing.

- [~] **36. The app loses ~20 pulses per restart. → Phase 11 (2026-09-22).** Root cause confirmed
  in code: `startDispensing` opens the relay *before* the collector attaches, and the fresh
  `PulseAccumulator` zeroes the first frame. Fixed by design by the board-latched session start
  (`ARM`) in [`PHASE_11_PLAN.md`](PHASE_11_PLAN.md); no separate app-only fix. Original entry: New, and only visible once the trace was on
  screen. Tracking the offset between the board's count and the app's transaction count across one
  sale with three restarts: 2389 → 2391 → 2414 → 2436 → 2456, so the app ends each cycle ~22 pulses
  (~0.22 L) behind the board, 67 across the run. It **under**-counts, so the customer is never
  overcharged and the station absorbs it — the same direction as OQ #25's original defect, two
  orders of magnitude smaller. Suspected cause: the pulses between the resume's adapter reading and
  the collector attaching, which `PulseAccumulator` swallows in its uninitialised branch. Not fixed:
  it is small, it fails safe, and it wants its own change with its own test.

- [x] **38. CLOSED 2026-09-22 — superseded by Phase 11 (spec D6); watchdog stays 3 s.** The
  board-held session removes the give-away this was about: a fixed sale cannot pass its limit, and
  fill-up fuel after an app death is counted and billed on restart. Original entry:
  **Shorten the firmware watchdog from 3 s to 2 s?** The app PINGs at 1 Hz, so three seconds
  is three missed pings; two would still tolerate a hiccup and would **halve** the give-away
  measured in step 8. One-line firmware change, so it belongs with **#19**'s firmware work rather
  than on its own. Not a substitute for **OQ #26** — see there.

### from Phase 10

- [ ] **NEW — no build type is both real hardware and production.** `debugProd` takes real
  payments on the mock pulse source; `debugRealHw` drives the Arduino against the dev backend. 10g
  can prove the payment path on `debugProd` and 7h's bench gate covered the hardware, so this is not
  a blocker — but the parallel run's release build will be the first time the two run together,
  and that should be a deliberate decision rather than a discovery.

## 🔧 Phase 7g — adapter EEPROM totaliser + power-cut reconciliation (SPLIT — docs/app on `main`, firmware held)

> **2026-09-07 — the branch was split, not merged whole.** The docs and the app-side
> `PULSES_PER_LITRE` change are on `main`; the **five firmware commits stay on
> `feature/phase-7g-eeprom-totaliser`** until the EEPROM totaliser is verified on hardware, per
> the merge assessment in [`BRANCH_7G_SUMMARY.md`](BRANCH_7G_SUMMARY.md). So `hardware/*.ino` and
> `hardware/README.md` on `main` are still the pre-7g versions — read them from the branch, not
> from `main`. The gate is unchanged and Friday 2026-09-04 recorded no result in the repo.

Source: **Prototype Specification v1.0**, Hardware → "Pulse-tap adapter board" and Software →
"Power-cut transaction recovery". Not in the original Phase 7 plan (7a–7f), so filed as **7g**.
Spec lines that drive it: optically-isolated read-only tap, 5 V + 12 V pulse input (Gilbarco /
Wayne / Tokheim), 2500 V galvanic isolation, STM32F103 or ATmega328P, raw pulse count in onboard
EEPROM surviving power cuts and independently readable, K-factor sealed post-calibration.

**Agreed constraints (settled 2026-09-02):** write **only at end-of-dispense** via `EEPROM.put`,
never per pulse (AVR EEPROM is ~100k cycles — per-pulse writes at 50 pps destroy it within the
hour); the totaliser is a **reporting figure**, with the app remaining system of record for litres
sold.

- [ ] **19. Blocked on Olonade.**
  - ~~"stores last 10,000 pulse counts" — totaliser or ring buffer?~~ **SETTLED 2026-09-02, twice
    over.** His bench sketch implements a single lifetime totaliser wear-levelled over 100 slots;
    and independently, the ring-buffer reading is *physically impossible on the spec'd MCU* —
    10,000 records need 40 KB at a 4-byte count (20 KB even as 2-byte deltas), while `HW-C-05`'s
    ATmega328P has **1 KB** of EEPROM and the bench Mega 2560 only **4 KB** (verified by compiling
    `E2END + 1` for both against AVR core 1.8.7). Short by 20-40x, and the STM32F103 has no true
    EEPROM at all. So `HW-C-04` contradicts `HW-C-05` under that reading — send it to Olonade as a
    **correction**, not a question. Worth still asking what "10,000" was meant to size, since it is
    only ~100 L at the placeholder K-factor. **OQ #24 is unaffected: the session mark is not free
    and must be added to the protocol.**
  - **🔴 BLOCKER FOR T-01 — the 150 ms ISR debounce must be removed before any calibration run.**
    It caps counting at 6.67 pulses/s ~= **4 L/min** at the placeholder K-factor; a real dispenser
    flows 30-50 L/min. The loss is flow-rate dependent, so a K-factor derived through it is not a
    constant and the +/-0.5% tolerance is unreachable. The 150 ms figure is correct for the
    *pushbutton* the bench uses and must not survive contact with a meter (real meters need
    sub-millisecond debounce, ideally hardware RC + optocoupler per spec).
  - **🔴 On a Mega the sketch counts nothing.** `attachInterrupt` is used on pins **7** and **5**;
    verified against the installed AVR core 1.8.7 (`variants/mega/pins_arduino.h:110`) the Mega maps
    only pins **2, 3, 18, 19, 20, 21**. Both calls resolve to `NOT_AN_INTERRUPT` (-1), which
    `attachInterrupt`'s `uint8_t` parameter turns into 255, failing the
    `< EXTERNAL_NUM_INTERRUPTS` guard (`WInterrupts.c`) — a **silent no-op**. No pulse counting, no
    power-fail save. The sketch's own comment claims the opposite.
  - **Torn-write bug in the power-fail save.** `PumpData` orders `sequence` before `pulseCount`, and
    `EEPROM.put` writes ascending, so a cut *during* the save (the exact case it exists for) can
    commit a new highest `sequence` against a **stale `pulseCount` from 100 cuts ago** — which
    recovery then elects as the winner. Fix: write `pulseCount` first and `sequence` last as the
    commit marker, plus a CRC over the slot.
  - `CAL` frame for the sealed K-factor (**OQ #23**) — protocol change, must land before the
    adapter firmware is written.
  - `CAL` frame for the sealed K-factor (**OQ #23**) — protocol change, must land before the
    adapter firmware is written.
  - Whether `max()` gets a session mark (**OQ #24**), since the literal rule is not implementable.
- [x] **20. Recovery correctness — BUILT 2026-09-11 as Phase 7h (OQ #25).** Was: pulses counted
  while the tablet is down were silently absorbed into a new baseline. Now measured against a
  persisted anchor and either put on the live sale or logged with a reason. **Still live on `main`**
  — the fix is on `feature/phase-7h-pulse-continuity`, unmerged, gated on the bench run below.
  See the 7h section further down.

- [ ] **21. `CanStartTransactionUseCase` third `Missing` case** — no K-factor = no cutoff = refuse
  the sale, exactly as for price and fuel type (**OQ #23a**). Small; rides on the 7b guard already
  built.
- [~] **24. Merge the two sketches — WRITTEN 2026-09-02, NOT YET FLASHED.** The two were disjoint
  experiments and could not be swapped for one another: the bench sketch emitted bare `PULSE:<n>`
  with **no checksum**, so `SerialFrameParser` rejected every line as `Invalid` ("missing checksum
  delimiter", `SerialFrameParser.kt:19`) and the app would have counted zero litres; it also had
  **no `BOOT`/`HB`, no `RLY:1`/`RLY:0` and no `PING`**, so no fuel cut-off and none of the
  comms-loss watchdog that closed merge gate #2; and it claimed **D7**, our relay pin.
  `smartpump_pulse_adapter.ino` now carries both halves — 7a framing/relay/watchdog kept intact,
  7g EEPROM totaliser + power-fail save added. Four defects fixed in the merge:
  - **Interrupt pins → `D2` (pulse) and `D3` (power sense).** Those are the only interrupt-capable
    pair common to Uno and Mega, so the button moved to polled `D4`. Verified by compiling
    `static_assert(digitalPinToInterrupt(p) != NOT_AN_INTERRUPT)` against AVR core 1.8.7: pins 2/3
    pass on both boards, pins **7/5 fail on both** — so the bench sketch counted nothing on a Uno
    either, not just a Mega.
  - **Debounce 150 ms → `PULSE_DEBOUNCE_US = 250`** (µs, in the ISR), with the flow-ceiling
    arithmetic documented at the constant and in `hardware/README.md`.
  - **Torn-write fixed.** `PumpData` reordered to `{pulseCount, sequence, crc}` + CRC-16/CCITT;
    `EEPROM.put()` writes ascending so the CRC lands last as a commit marker, and recovery rejects
    any slot failing it.
  - **Power-fail ISR drops the relay before the EEPROM commit**, and `Serial.flush()` after
    `ERR:PWR` so the notice actually leaves before the halt loop.
  - Also: totaliser commits on `RLY:0` **and on a watchdog trip** (a dispense ended, however
    abruptly); `DEBUG_BANNERS` (default `false`) gates all unframed output; 64 slots × 10 B = 640 B
    fits Uno and Mega, enforced by `static_assert`.
  - **Verified:** compiles clean with `-Wall` for `atmega2560` and `atmega328p`. **Not flashed, not
    bench-run** — see the new "EEPROM totaliser (7g)" checklist in `hardware/README.md`.
  - **Deliberately NOT added:** the session mark (OQ #24) and the `CAL` frame (OQ #23). Both are
    protocol changes and both are Olonade's to ratify; inventing them unilaterally is the mistake
    this project already made once with the API summary.
- [ ] **22. Firmware:** `ENABLE_AUTO_PULSE = false` for real-meter runs; optocoupler + debounce
  replaces the bench `INPUT_PULLUP` (spec decides this — bare pullup must not survive into the
  adapter design). Bench meter output type + voltage incoming from Kelvin.
- [ ] **23. Bench:** Mega is a drop-in — flash target `arduino:avr:mega` only; manifest filter
  (vendor-only, `usb_device_filter.xml:6`) and the default CDC prober already cover Mega 2560 R3.
  Only the `// INT0` comment at `.ino:43` goes stale (pin 2 is INT4 on Mega;
  `digitalPinToInterrupt` handles it). If a clone gives the USB dialog but no `BOOT` frame, it
  needs a custom `ProbeTable`.

**Not blocked:** #20 and #21 can proceed now. #19 gates the firmware half.

## Now — unblocked, high value

- [~] **34. Release signing — BUILD SIDE DONE 2026-09-12, keystore still owed.** Was: no
  `signingConfig` at all and the scaffold's `versionCode = 1` / `versionName = "1.0"`, so a release
  APK was **unsigned and could not be installed** on a station tablet. It had been on no list
  anywhere, which was the dangerous part.
  - `signingConfigs` now reads `keystore.properties` (gitignored, template committed as
    `keystore.properties.example`) or the four `SMARTPUMP_*` environment variables for CI. Version
    is declared once at the top of the build file with the bump rule stated.
  - **Absent credentials leave release UNSIGNED rather than failing configuration** — a fresh clone,
    a CI lint run and every debug build must work without the station's private key. The build logs
    a loud warning instead, and `docs/RELEASE.md` makes `apksigner verify` a required step.
  - **The keystore itself is DEFERRED TO LAST — decided 2026-09-12.** It is not on the critical
    path: signing is a prerequisite of the **parallel run**, which waits on the K-factor, which
    waits on Kelvin. And key **custody is the boss's decision** (who holds it, where the backup
    lives, whether it survives people moving on) — with a prior question worth asking, namely
    whether **Balancee already has an Android signing key**, since generating a second would be
    wrong. Creating a key is *not* irreversible: it only binds once a build signed with it is
    installed on a tablet expected to receive updates.
  - **Custody ANSWERED 2026-09-15 (boss, via the user).** Balancee already has an Android key and
    keeps it for **production**. The **14-day run is signed with a dummy key** we generate. Cutover
    is a **planned reinstall**, chosen over APK Signature Scheme v3 rotation (which needs both keys
    and ties the dummy into production's signing history). The reinstall wipes local history,
    KeyStore credentials and the device ID, so production activates fresh. See `docs/RELEASE.md`.
  - When it happens: `keytool` command in `docs/RELEASE.md`, fill in `keystore.properties`, and
    **back the file up off the laptop** — losing it ends the app's upgrade path, because a field
    tablet will refuse an APK signed by a different key and reinstalling wipes local history *and*
    the activation identity.
  - **Do R8 after this, not before** (see the deferred minify item), so a broken release build can
    only have one cause at a time.
  - ⚠️ **A debug build cannot stand in for the parallel run** — it seeds its own price, exposes the
    debug hotspot, points at the dev backend, and installs under a different application id. Full
    reasoning in [`V1_BLOCKERS.md`](V1_BLOCKERS.md).
- [ ] **40. Get the parallel run's records off a release build.** Found 2026-09-15 from the
  signing decision: the run is cut over to production by **uninstalling**, which deletes the Room
  database, and a release build is **not debuggable**, so `adb run-as` cannot copy it off first. The
  upload job (7e) will not cover it either — it rides on `/authorise`, and cash sales have nothing
  to upload. Needs an attendant-side export (e.g. the sale log and fuel log as CSV through the share
  sheet, behind the PIN). Not urgent until the run exists, but it must land **before** the run
  ends, and it may be wanted daily for the variance check against station stock records.

## Waiting on external input

- [→] **6. Chase the 7 boss confirmations** — moved to [`POST_V1.md`](POST_V1.md) 2026-09-22: not V1-blocking.

## Deferred (parked, not dropped)

- [→] **9. Offline USSD (Flow 5 / sub-phase 7d).** — moved to [`POST_V1.md`](POST_V1.md) 2026-09-22: not V1-blocking.
