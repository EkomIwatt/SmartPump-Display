# What is still blocking V1

_Compiled 2026-09-12. **Updated 2026-09-22: the payment flows (#8) are built, gated and merged (`2d7c06d`).** Corrected 2026-09-19 — section 1's 7g firmware claim was wrong and is
rewritten below; nothing else moved. Refreshed 2026-09-17, after the gate (#32) closed: the app has completed a
real paid transaction against production end to end, so **section 4 is empty of anything that blocks
V1** — nothing on the API line is waiting on the backend any more. What is left there is ours to
build (**#8**) and the backend asks that remain are improvements, not gates._

**This file is a view, not a second source of truth.** Every item points at its real entry in
[`TODO.md`](TODO.md) (work items, `#n`) or [`OPEN_QUESTIONS.md`](OPEN_QUESTIONS.md) (decisions,
`OQ #n`). When something moves, update it **there** and adjust the one-line status here. Do not
restate detail from those files — a second copy of an argument is a second copy to keep true, and
`OPEN_QUESTIONS.md` already carries a written warning about exactly that drift.

The board is organised by phase and branch, which is the right shape for building. This is the same
work sorted by a different question: **who is holding it up, and what can move today?**

---

## Summary

| | |
|---|---|
| Built and merged | all 5 flows, **the digital payment flows + upload job (Phase 10, #8, merged 2026-09-22)**, real Arduino pulse + relay, operator config, persistence/boot-resume, signed network layer, encrypted credentials, device identity, 7h pulse continuity, Phase 9 API work + the activation step, the API probe panel |
| Proven against production | the whole paid lifecycle — activate → `/config` → authorise → Paystack → `PAID` → dispense upload, by observation, on a real ₦149 sale (**#32**, 2026-09-16/17) |
| Built, unmerged | 7g firmware (bench gate) |
| Not built | release signing (deferred to last by decision) |
| Never measured | the meter K-factor — every litre figure runs on a placeholder |

---

## 0. Pick one of these next

Two candidates that need no rig, no reply and no decision. Sized roughly.

- [x] ~~**Activation step in onboarding**~~ — **DONE 2026-09-12** (`ce4a0b8`). **#33**.
  `PumpActivationRepository` now has two callers: **onboarding step 4** and a panel on the
  **operator settings screen**. The second is what makes **#32** runnable at all — a debug build
  auto-provisions a demo identity and never shows onboarding, so an onboarding-only entry would
  have been unreachable in exactly the build that points at the dev backend. Activation is
  **optional**: cash sales do not need it, and a pump is often installed before its code exists.
  Design caveat stands — no activation screen exists in `docs/Strict design screens/`.
1. **Transaction upload job (7e)** — ~~self-contained~~ **corrected 2026-09-15: it rides on #8.**
   `UploadTransactionRequest` needs a server `transactionId` and `paymentReference`, and only
   `/authorise` issues them, so a cash sale has nothing to upload and a digital one has nothing
   until the payment flows exist. Also missing: `workmanager` (not in `gradle/libs.versions.toml`)
   and anything that sets `syncedAt`. **The gate confirmed the endpoint accepts any litres figure
   (#47) and that the server refuses to record fuel against an unpaid sale (#45)** — so this is now
   a sub-deliverable of #8, not a separate candidate.
2. ~~**Draft the OQ #22 options**~~ — **done and decided 2026-09-15** (see section 5).

## 1. Blocked on nobody — movable today

The highest value per hour on the whole project, because none of it waits on a reply.

- [·] **Release signing — build side done 2026-09-12; the keystore itself is DEFERRED TO LAST by
  decision (2026-09-12).** The signing config, the gitignored credentials file, the version scheme
  and `docs/RELEASE.md` are in, so the pipeline is ready whenever the key is. **#34**.
  - **Why last:** it is not on the critical path. Signing is a prerequisite of the **parallel run**,
    which cannot start until the K-factor is measured, which waits on Kelvin. Nothing this week
    needs it.
  - ~~**Why not sooner:** key custody is the boss's call~~ — **answered 2026-09-15.** Balancee has
    a key and holds it for **production**; the **parallel run uses a dummy key** we generate. The
    switch is a **planned reinstall** at cutover (wipes history, credentials, device ID), not key
    rotation. Custody no longer blocks the run key. Steps in `docs/RELEASE.md`.
  - **New gap this exposed — #40:** a release build is not debuggable, so nothing can get the run's
    records off a tablet before that reinstall.
  - Generating a key is **not** irreversible the way the activation code is. It only binds once a
    build signed with it is installed on a tablet expected to receive updates. A disposable local
    key can prove the pipeline any time.
- [x] **Receipt sharing — done 2026-09-12.** Plain-text receipt through the Android system share
  sheet, built from the saved audit row so a screen restored after a power cut is not dated "now".
  **#35**.
- [x] **7h bench gate — PASSED 2026-09-13, merged.** **#27**. Left two under-counting findings
  (**#28**, **#36**), a watchdog proposal (**#38**) and one receipt defect:
- [x] **#37 — two receipts, two prices — FIXED 2026-09-15.** The completion screen now reads the same
  price as the saved record and the shared receipt. A price edited during an app restart is
  accepted as rare, not fixed.
- [ ] **7g firmware gate** — pending. **This entry claimed until 2026-09-19 that `main` shipped
  broken firmware. It does not, and the correction matters because the alarm was the reason to
  rush the merge.** `hardware/*.ino` on `main` is the **pre-7g** sketch, but that sketch is the
  7a-hardening one — `PIN_PULSE_IN = 2` (INT0), relay on 7 — bench-verified in June and again at
  the 7h gate. **It counts correctly.** The four defect fixes the branch carries were fixes to
  **Olonade's EEPROM contributions, made while merging the two sketches**, not regressions in
  ours. Flashing from `main` gets you a working adapter *without* the EEPROM totaliser.
  - **So merging is adding an unverified totaliser to a verified sketch**, not repairing a broken
    one — which is why the bench gate stands rather than being waived. The gate is ~5 minutes with
    the rig: note the `BOOT` count, run one dispense, power-cycle, confirm the count comes back
    **higher rather than zero**, then confirm the next sale still starts from **zero litres** on
    the tablet. The second check is the one that would otherwise surface as a customer billed for
    the pump's entire service life.
  - **The real trap is smaller and is documentation:** `hardware/README.md` on `main` is the 7a-era
    version while the journal docs beside it describe the post-7g sketch. Read the firmware docs
    from the branch, not from `main` — as TODO's 7g section already says.
  - **Merging also re-pins any bench rig** wired to `main`'s README: button 3 → 4, pin 3 becomes
    power-sense, debounce 150 ms → 250 µs.
  - Branch is pushed (`origin/feature/phase-7g-eeprom-totaliser` = `b304a0a`), so nothing is at
    risk while it waits. **Merge the `hardware/` files only** — the branch is ~30 commits behind
    `main` and a whole-branch merge conflicts on `OPEN_QUESTIONS.md` and `BRANCH_7G_SUMMARY.md`
    while silently auto-merging a 2026-09-07 `TODO.md`, which would reopen settled OQ #22 and #25.
  **#19** / `BRANCH_7G_SUMMARY.md`.
- [·] **Third `Missing` case — NOT movable, corrected 2026-09-12.** Listed here first as small and
  unblocked; it is neither. `PULSES_PER_LITRE` is a **compile-time constant** in
  `MeterCalibration.kt`, not a field on `DeviceConfig`, so there is no absent state for a guard to
  detect — the guard can only exist once the sealed value arrives from the adapter (**OQ #23**).
  Moves to section 3. **#21**, **OQ #23a**.

### A debug build cannot stand in for the parallel run

Recorded 2026-09-12, because it looks like it should work and it does not. Debug builds are right
for development and for both bench gates; they are wrong for the fourteen-day run, for four reasons
that are all in the code today.

- **It configures itself.** `seedDefaultConfigIfMissing()` runs whenever `BuildConfig.DEBUG` is
  true — which includes `debugRealHw`, since it is `initWith(debug)` and therefore debuggable — and
  silently writes a placeholder price, station name, fuel type and virtual account. That seed was
  made debug-only in 7b **precisely** because a release install doing it meant the price guard
  could never fire. A run that exists to prove litres match stock records must not happen on a
  build that invents its own price.
- **The debug hotspot is live.** A long press top-left opens live price editing and payment
  force-resolve (`MainActivity`, gated on `BuildConfig.DEBUG`). Correct on a bench, an unlocked till
  on a forecourt for two weeks.
- **It points at the dev backend.** `debug`/`debugRealHw` use `api.dev.balancee.app`; only `release`
  uses production.
- **It is a different app.** `debugRealHw` carries the `.realhw` application-id suffix, so whatever
  history the run accumulates lives in an app that is later discarded — along with that install's
  activation identity.

**Consequence:** the parallel run needs a production-shaped build (no self-seeding, no hotspot, right
backend), and such a build cannot be installed at all unless it is signed. Signing is therefore a
prerequisite *of the run*, which is why it is deferred rather than dropped.

## 2. Blocked on measurement — gates live money

- [ ] **The K-factor has never been measured.** Every litre the app has ever displayed rests on a
  placeholder. This is not just an accuracy question: live payments are gated behind a **14-day
  parallel run** at under 1% daily variance against station stock records, and that run cannot be
  started against a number nobody has measured. **OQ #1**, TEST-01.
  - **#28** — 7h's plausible-gap safety limit is derived from the same placeholder and needs
    re-deriving once the real figure exists. It is written in pulses precisely so it cannot silently
    change meaning.
  - **Blocked in turn on:** the bench meter's output type and voltage, **owed by Kelvin** (**#22**).
    Not a backend dependency.

## 3. Blocked on Olonade — one conversation, not three

- [ ] **OQ #23** `CAL` frame (how the sealed K-factor reaches the app) · **OQ #24** session mark ·
  **OQ #26** firmware-owned cutoff. All three touch the same serial protocol and should go together;
  #26's `RLY:1:<pulses>` is also the signal #24 needs.
- **#26 is the substantive one:** the fixed-dispense stop is currently a **USB round trip**, not a
  decision the firmware can make on its own.

## 4. Was blocked on the backend — NOTHING HERE BLOCKS V1 ANY MORE

**Closed 2026-09-17 by the gate (#32).** This section carried the API line since July. It no longer
holds anything up: the app activated against production, read `/config`, authorised a sale, was paid
through live Paystack, polled to `PAID` and uploaded the dispense. Every question this section was
waiting on was answered **by observation** rather than by a reply. See the PROJECT_LOG entry for
2026-09-16/17 and `docs/api-probes/2026-09-16-prod-gate/`.

- [x] **The activation code — SETTLED.** `Test Pump 1` / `SN-TEST-001`, a throwaway on a dummy
  business, with self-service **Get code** / **Revoke**. **#31**, **#32**, `GATE_32_RUNBOOK.md`.
- [x] **`/config` exists and is deployed** — and its real shape is nothing like what was modelled:
  one pump, one fuel, one price (`pumpId`, `stationName`, `fuelType`, `pricePerUnit`, `updatedAt`).
  Rebuilt from captured bytes with nothing defaulted. This retired `BOSS_CONFIRMATIONS_DRAFT.md`
  item 1 — the ask marked *highest*, and said to set the date — **before it was ever sent**.
- [x] **`GET /transactions/{id}` exists**, and the status set is `PENDING_PAYMENT` → `PAID` →
  `DISPENSED` (**#18d**). Item 2 retired. The poll can carry PAID detection on its own.
- [x] **Decimal `amount` accepted; stable error codes exist** on business failures and on no auth
  failure (**#18c**, **#18f**). GET signing confirmed by a 200. Item 3 retired.
- [x] **#15 closed** — a `/config` signed ten minutes late returns `401 "Request timestamp is not
  fresh"`, the exact string the audit predicted, so the drafted copy stands.
- [x] **Live Paystack, answered by doing it** (item 4). The ₦149 was real.

**What remains on the backend is genuinely optional — none of it gates V1:**

- [ ] **#29 — the `events` table has no backend home.** 7h writes operator-visible fuel-log rows and
  nothing on the server accepts them. A fifth item for **#18**; the rows stay device-local until then.
- [ ] **#48's other half — correction-or-refusal on upload.** First write wins and the repeat returns
  a `200` that reads as success. Ours is to upload once (**7e**); theirs is to accept a correction or
  refuse the repeat with a code. Asked, not blocking.
- [ ] **#46 — the reply never echoes `actualLitresDispensed`**, so the app cannot read its own record
  back; only the dashboard shows it (**#49**). That makes parallel-run verification a person opening
  a web page. Worth asking; not a gate.
- [·] **Push (FCM) has no server side and no client side.** There is no device-token registration
  endpoint anywhere in the API and no Firebase code in the app. **This does not block #8**: OQ #8
  already rules that push is a *freshness optimisation only* and the **poll carries the correctness
  guarantee** — and the poll is now proven. Build poll-only; leave the seam.

- [x] **Payment feature flows (#8) — DONE, merged 2026-09-22 (`2d7c06d`).** Gated on real money
  (10g) and on the tablet; what is left is boarded in `TODO.md` (#R3, #R8, #50–#52; #R11/#R14 for
  the boss; the `iad1` region and two backend questions for Balancee). Original entry: Authorise →
  Paystack QR, `PAID` by poll, the config fetcher, and the upload job (7e) that rides on it. The
  transport half is built, merged and now *demonstrated*; the feature half is not started. Carries
  **#43–#46** and **#48** with it.

## 5. Blocked on decisions that are ours

Both are quietly holding up built code.

- [x] **OQ #17 — error recovery copy — SETTLED 2026-09-12** (`c2c62f9`, `16d4495`). Customer gets
  one plain line, diagnostic detail goes to the **swipe-up attendant panel**, and a retryable
  failure now looks different from a terminal one. Copy in
  [`ERROR_COPY_DRAFT.md`](ERROR_COPY_DRAFT.md). Still flagged: **no error screen exists in
  `docs/Strict design screens/`**, so the layout is a deviation on record.
  - It no longer blocks **#14**'s mapping half or **#15**'s attendant half — both now wait only on
    **#8**, since nothing receives an `ApiError` until the payment flows exist.
- [x] **OQ #22 — stuck fixed-flow sale — SETTLED 2026-09-15, Option 1.** An attendant "End sale
  early" button ends a fixed sale that will not reach its target, recording litres flowed against
  the amount paid. Covers the link-loss case the OQ named and the far more common one it did not: a
  tank that fills before the target. No automatic timeout. See
  [`OQ22_OPTIONS_DRAFT.md`](OQ22_OPTIONS_DRAFT.md).

## 6. Confirmed out of the V1 build cycle

Listed so they are not rediscovered as surprises.

- **Flow 5 offline USSD (7d)** — boss-deferred to a future update. **#9**.
- **Kiosk lock-task** — boss-deferred (the fourth boss edit).
- **R8 / `shrinkResources` for release** — deliberately deferred 2026-06-01; needs
  serialization/Room/Hilt keep rules plus an end-to-end verify first. Revisit **after** signing
  exists, not before.
- **Role-based PINs** — V2. V1 ships one shared PIN, which means anyone who can authorise a sale can
  also change the price (**OQ #19**, accepted).

---

## Suggested order

Rewritten 2026-09-17. The old ordering was built around section 4 waiting on a reply; it is not
waiting any more, which promotes **#8** from "gated/later" to the largest movable thing on the board.

1. ~~**Payment feature flows (#8)**~~ — **DONE, merged 2026-09-22.** The long pole is now #22 below. Every contract
   question it needed has been answered by observation, and the whole lifecycle has been driven once
   by hand through the probe panel, so this is implementing against *demonstrated* behaviour rather
   than against a PDF. Build **poll-only**; push has no server side and is not needed for
   correctness. Carries **#43–#46**, **#48** and the upload job (7e).
2. **Chase Kelvin for the meter output type and voltage (#22)** — unchanged, and still the long pole
   in front of the K-factor → parallel run → live money. It runs in parallel with #8 because it
   costs a message, not a day.
3. **The 7g bench gate (#19).** Needs only the rig. **Not a live trap** — corrected 2026-09-19:
   `main`'s sketch counts correctly (see section 1). The gate buys the EEPROM totaliser; it does
   not repair anything. ~~#27~~ passed 2026-09-13.
4. **Send Olonade the three protocol questions** (OQ #23 / #24 / #26) as one message.
5. **Release signing, last (#34).** The build side is done and the dummy run key waits on nobody.
   Still last because the run waits on the K-factor. **#40** (getting records off a release build)
   has to exist before the run ends.

Done and off this list: **the gate (#32)**, closed 2026-09-16/17 — which is what reordered
everything above it. **Receipt sharing (#35)** and the **activation step (#33)**, both 2026-09-12.
~~Decide OQ #22~~ settled and built 2026-09-15 (Option 1); ~~OQ #17~~ settled 2026-09-12; ~~the
signing-key question~~ answered 2026-09-15. **#21**, the third `Missing` case, turned out **not** to
be movable and has gone to section 3 — the K-factor is a compile-time constant, not a configurable
field, so there is no absent state for a guard to detect.
