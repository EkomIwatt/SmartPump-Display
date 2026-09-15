# What is still blocking V1

_Compiled 2026-09-12. Refreshed 2026-09-15 after 7h and the Phase 9 line were both merged to `main`._

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
| Built and merged | all 5 flows, real Arduino pulse + relay, operator config, persistence/boot-resume, signed network layer, encrypted credentials, device identity, 7h pulse continuity, Phase 9 API work + the activation step |
| Built, unmerged | 7g firmware (bench gate) |
| Not built | transaction upload job, release signing, the payment feature flows |
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
1. **Transaction upload job (7e)** — the plan calls it self-contained and it is. Two things are
   missing under it: `workmanager` was removed in the cleanup and is **not in
   `gradle/libs.versions.toml`**, and **nothing marks a transaction synced**, so
   `getPendingSync()` would return the same rows forever. Fails safe before activation, since the
   call is signed and returns `NotActivated`.
2. **Draft the OQ #22 options** — the last open decision (see section 5). Writing out the two or
   three concrete recovery behaviours would let it be settled by picking, which is what worked for
   OQ #17.

## 1. Blocked on nobody — movable today

The highest value per hour on the whole project, because none of it waits on a reply.

- [·] **Release signing — build side done 2026-09-12; the keystore itself is DEFERRED TO LAST by
  decision (2026-09-12).** The signing config, the gitignored credentials file, the version scheme
  and `docs/RELEASE.md` are in, so the pipeline is ready whenever the key is. **#34**.
  - **Why last:** it is not on the critical path. Signing is a prerequisite of the **parallel run**,
    which cannot start until the K-factor is measured, which waits on Kelvin. Nothing this week
    needs it.
  - **Why not sooner:** key **custody is the boss's call**, not an engineering one — who holds the
    key and its password, where the backup lives, and whether it survives people moving on. Worth
    asking first whether **Balancee already has an Android signing key**; generating a second one
    would be the wrong move.
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
- [ ] **7g firmware gate** — worse than merely pending: `hardware/*.ino` on `main` is the **pre-7g**
  sketch while the docs beside it describe the post-7g one, so anyone flashing from `main` gets
  firmware predating four defect fixes. **#19** / `BRANCH_7G_SUMMARY.md`.
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

## 4. Blocked on the backend

- [ ] **The activation code.** Now the single gate on the whole API line — see **#31** (two questions
  that shrink the one-way door) and **#32** (the full sequence to run in one sitting once a code
  exists). **#32 is now runnable the moment a code lands** — since `ce4a0b8` the operator settings
  screen can redeem one, which is the only entry a debug build has. Everything in the probe's "cannot reach" list is behind it: the `/config` payload shape,
  GET signing, clock skew (**#15**), the decimals question and the real status set (**#18c–e**).
- [ ] **Transaction upload job (7e).** Not built; the `workmanager` dependency is not even in the
  project. Needs the ingest endpoint confirmed.
- [ ] **#29 — the `events` table has no backend home.** 7h writes operator-visible fuel-log rows and
  nothing on the server accepts them. This was never added to the asks; it is a fifth item for
  **#18**.
- [ ] **Payment feature flows (#8)** — activate, authorise → Paystack QR, PAID via push + poll,
  config fetcher, upload job. The transport half is built and now safe to activate; the feature half
  is not.

## 5. Blocked on decisions that are ours

Both are quietly holding up built code.

- [x] **OQ #17 — error recovery copy — SETTLED 2026-09-12** (`c2c62f9`, `16d4495`). Customer gets
  one plain line, diagnostic detail goes to the **swipe-up attendant panel**, and a retryable
  failure now looks different from a terminal one. Copy in
  [`ERROR_COPY_DRAFT.md`](ERROR_COPY_DRAFT.md). Still flagged: **no error screen exists in
  `docs/Strict design screens/`**, so the layout is a deviation on record.
  - It no longer blocks **#14**'s mapping half or **#15**'s attendant half — both now wait only on
    **#8**, since nothing receives an `ApiError` until the payment flows exist.
- [ ] **OQ #22 — "safe-but-stuck".** A permanent mid-dispense link loss on the fixed / pre-pay /
  cash-fixed flows leaves the screen at the last litre count **indefinitely**. Fuel is physically
  off, so it is safe rather than dangerous, but it clears only on a power cycle or attendant action.
  Is that acceptable for V1, or do those flows need a bounded recovery?

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

Sorted by value per hour, given that section 4 is waiting on a reply either way.

1. **The 7g bench gate (#19).** Needs only the rig, and removes a live trap on `main`. ~~#27~~
   passed 2026-09-13.
2. **Chase Kelvin for the meter output type and voltage (#22)** — it is the long pole in front of the
   K-factor, which is in front of the parallel run, which is in front of live money.
3. **Send Olonade the three protocol questions** as one message.
4. **Decide OQ #22** — the last of the two decisions that were holding up written code. ~~OQ #17~~
   settled 2026-09-12.
5. **Ask the boss about the signing key** — does Balancee already have one, and who holds it. A
   question, not a task; it only needs answering before the parallel run.
6. **Release signing, last (#34).** By decision, 2026-09-12. The build side is already done; what
   remains is the key itself, and it is not needed until there is a production-shaped build to
   install.

Done and off this list: **receipt sharing (#35)** and the **activation step (#33)**, both
2026-09-12. **#21**, the third `Missing` case,
turned out **not** to be movable and has gone to section 3 — the K-factor is a compile-time
constant, not a configurable field, so there is no absent state for a guard to detect.
