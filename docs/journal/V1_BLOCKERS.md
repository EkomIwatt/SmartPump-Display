# What is still blocking V1

_Compiled 2026-09-12._

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
| Built and merged | all 5 flows, real Arduino pulse + relay, operator config, persistence/boot-resume, signed network layer, encrypted credentials, device identity |
| Built, unmerged | 7h pulse continuity (bench gate), 7g firmware (bench gate), Phase 9 API work (no gate) |
| Not built | receipt sharing, transaction upload job, release signing, the payment feature flows |
| Never measured | the meter K-factor — every litre figure runs on a placeholder |

---

## 1. Blocked on nobody — movable today

The highest value per hour on the whole project, because none of it waits on a reply.

- [~] **Release signing — build side done 2026-09-12, keystore still owed by a human.** The signing
  config, the gitignored credentials file, the version scheme and `docs/RELEASE.md` are in. What is
  left is not code: create the keystore, fill in `keystore.properties`, and **back it up off the
  laptop** — losing it ends the app's upgrade path. **#34**.
- [x] **Receipt sharing — done 2026-09-12.** Plain-text receipt through the Android system share
  sheet, built from the saved audit row so a screen restored after a power cut is not dated "now".
  **#35**.
- [ ] **7h bench gate** — eight-step checklist, Arduino + tablet, ~1 h. Closes a live under-billing
  bug (**OQ #25**). **#27**.
- [ ] **7g firmware gate** — worse than merely pending: `hardware/*.ino` on `main` is the **pre-7g**
  sketch while the docs beside it describe the post-7g one, so anyone flashing from `main` gets
  firmware predating four defect fixes. **#19** / `BRANCH_7G_SUMMARY.md`.
- [·] **Third `Missing` case — NOT movable, corrected 2026-09-12.** Listed here first as small and
  unblocked; it is neither. `PULSES_PER_LITRE` is a **compile-time constant** in
  `MeterCalibration.kt`, not a field on `DeviceConfig`, so there is no absent state for a guard to
  detect — the guard can only exist once the sealed value arrives from the adapter (**OQ #23**).
  Moves to section 3. **#21**, **OQ #23a**.

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
  exists). Everything in the probe's "cannot reach" list is behind it: the `/config` payload shape,
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

- [ ] **OQ #17 — error recovery copy.** No error screen exists in `docs/Strict design screens/`.
  This blocks the **mapping half of #14** (the parsing half landed 2026-09-12) and the
  attendant-facing half of **#15**.
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

1. ~~**Release signing (#34).**~~ Build side landed 2026-09-12; the keystore itself is yours to
   create and back up (`docs/RELEASE.md`).
2. **The two bench gates (#27, #19).** Both need only the rig, and #19 removes a live trap on `main`.
3. **Chase Kelvin for the meter output type and voltage (#22)** — it is the long pole in front of the
   K-factor, which is in front of the parallel run, which is in front of live money.
4. ~~**Receipt sharing (#35)**~~ — done 2026-09-12. (**#21**, the third `Missing` case, turned out
   **not** to be movable: the K-factor is a compile-time constant, not a configurable field, so
   there is no absent state for a guard to detect. It waits on **OQ #23**, the sealed value arriving
   from the adapter.)
5. **Send Olonade the three protocol questions** as one message.
6. **Decide OQ #17 and OQ #22** — both unblock code that is already written.
