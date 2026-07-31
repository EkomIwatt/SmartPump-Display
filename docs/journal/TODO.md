# SmartPump Display — TODO

Living work board. The **PROJECT_LOG** records what's *done*; this file tracks what's *outstanding*.
Keep it current: check items off, add follow-ups as they surface, move finished work into the log.

**Legend:** `[ ]` open · `[~]` in progress · `[x]` done (then move to PROJECT_LOG) · `[·]` deferred/parked

_Last updated: 2026-07-10_

---

## ✅ `feature/phase-7a-hardening` — MERGED to `main`

**Done.** Both gates closed and the branch was **merged** (merge commit `9b76f42`; branch deleted).
The boss watchdog safety summary + report shipped on top (`ceef973`, `8b72775`). `main` = `origin/main`
= `8b72775`, build green on both variants. All PROJECT_LOG entries filed. Everything below is
**post-merge** (future phase or boss-gated).

- ~~**#2** — Uno bench: watchdog behaviour.~~ ✅ **VERIFIED ON DEVICE 2026-07-10.** The "normal fill-up
  trips" symptom did **not reproduce** on a fresh `debugRealHw` (6/6 clean dispenses, `PING tx ok=true`
  steady at 1 Hz, zero `ERR:WDOG`) → PING-starvation ruled out; the earlier trips were an **intermittent
  bus-power USB disconnect** (bench artifact — production is UPS-powered — and it fails *safe*). The
  reframed **safety check PASSED**: mid-dispense `am force-stop …realhw` → relay physically dropped ~3 s
  later (firmware dead-man watchdog). Temp diagnostic `Log` lines **reverted**; stale SM-T220 logcat
  **removed**. Evidence: `docs/logcats/forcestop-test_2026-07-10.log`, `bench-multirun_realhw_2026-07-10.log`.
- ~~**#10** — `KeystorePumpCredentialsStore` crypto verify.~~ ✅ **DONE 2026-07-08** — all 5
  instrumented tests pass on a physical device; runtime AES-GCM crypto confirmed.

Network-layer foundation (#1/#3/#4/#5) also landed in the merge. **Non-blocking post-merge follow-up:**
spontaneous-disconnect robustness (tolerate-and-resume vs fail-safe) — validate on external 5 V.

---

## Now — unblocked, high value

- [x] **10. Verify `KeystorePumpCredentialsStore` crypto** (instrumented test) — **merge gate CLOSED
  2026-07-08.** `app/src/androidTest/.../data/network/KeystorePumpCredentialsStoreTest.kt` (first
  androidTest in the project; commit `91fa772`) — covers not-activated, save→current round-trip,
  isActivated toggle, persistence across a fresh instance, `clear()` wipe, and corrupt-blob →
  null-fallback + ciphertext purge. **All 5 pass on a physical device** (`connectedDebugAndroidTest`,
  BUILD SUCCESSFUL) → runtime AES-GCM-at-rest confirmed; the #4 "runtime crypto unverified" caveat is
  cleared. _(move to PROJECT_LOG at next phase log.)_
- [x] **1. Commit network-layer foundation + doc reconciliation.** Done 2026-07-04 as two commits
  on `feature/phase-7a-hardening`: `29fe12b` (docs reconciliation + TODO board) and `4af9514`
  (network layer). _(move to PROJECT_LOG at next phase log.)_
- [x] **3. Build `PumpApiClient` / transport client.** Done 2026-07-04 (uncommitted): `ApiResult`/
  `ApiError` typed-error funnel (`safeApiCall`), `retryingApiCall` backoff primitive, `PumpApiClient`
  over all 5 endpoints with retry on the idempotent upload; interceptor now throws typed
  `PumpNotActivatedException`. Tests green (MockWebServer). **DTO↔domain mapping deferred to #8** —
  kept the client transport-only so the still-provisional bits (money unit, `/config` shape) don't
  ripple down. _(move to PROJECT_LOG at next phase log.)_
- [x] **4. Encrypted-at-rest credentials store.** Done 2026-07-04 (uncommitted): `KeystorePumpCredentialsStore`
  — AES-256-GCM key in the Android KeyStore + ciphertext in private SharedPreferences, decrypted creds
  cached for the sync `current()` hot path. Chose **KeyStore-direct** over the deprecated
  `security-crypto` lib. `NetworkModule` binding swapped; `InMemoryPumpCredentialsStore` deleted.
  Compiles + wired + unit tests green. ⚠️ **Runtime crypto unverified** (KeyStore needs a device) →
  tracked as **#10**. _(move to PROJECT_LOG at next phase log.)_

## Waiting on external input

- [x] **2. Uno bench re-run of 7a-hardening — GATE CLOSED 2026-07-10.** Run the
  `hardware/README.md` "Comms-loss heartbeat watchdog" checklist (PING ~1/s; unplug mid-fixed-dispense
  → relay off within ~3 s; replug → `RLY:1` re-assert → counting resumes toward target, not zero). A
  classic ESP32 (WROOM/CP2102 or CH340) can substitute with a ported sketch (remap off GPIO6–11,
  `IRAM_ATTR` ISR, `LED_BUILTIN`=GPIO2, 3.3 V). Merging closes the last gate on OQ #21.
  **Reframed (2026-07-08):** with the fixed-cable assumption, the safety case this gate must prove is
  no longer "cable pulled" (mode A — designed away) but **"app freezes/crashes while fuel flows,
  cable still connected" (mode B)** — which the assumption and the UPS do *not* cover. The old
  "unplug mid-flow" step is now secondary and is anyway confounded on a bus-powered bench Uno
  (unplug kills board *power*, so you'd be watching a power-loss fail-open, not the watchdog).
  **Primary required check:** leave everything plugged, then mid-dispense
  `adb shell am force-stop app.balancee.smartpump.display` (USB VBUS keeps the Uno powered, the host
  supplies 5 V regardless of app) → `D13` off within ~3 s + `ERR:WDOG*64` on Serial Monitor. The
  "replug → resume" step is now low-priority (no routine replug; app-side resume UI removed — only
  the relay-layer `RLY:1` re-assert remains for a rare transient). Build green (both variants).
  **RESOLVED 2026-07-10 (SM-T220 / Galaxy Tab A7 Lite, Android 14, USB-C).** The earlier "normal fill-up
  trips at ~5.9 s" symptom did **not reproduce** on a freshly built+installed `debugRealHw`: 6 dispenses
  back-to-back (4 fill-ups + 1 pre-pay + 1 post-replug), all 23–50 s to normal completion. Diagnostic
  logcat showed **`PING tx ok=true` steady at 1 Hz through every dispense** and **zero `ERR:WDOG`** →
  PING-starvation ruled out. Root cause reframed: an **intermittent bus-power USB disconnect**
  (`USB get_status request failed` → self re-enumeration; the Uno browns out off the tablet's OTG port) —
  a **bench artifact** (production is UPS-powered) that also **fails safe**. **Safety check PASSED:**
  mid-dispense `am force-stop app.balancee.smartpump.display.realhw` → last `PING` 14:38:56, app killed
  14:38:57, **relay physically dropped ~3 s later** (`ERR:WDOG` guaranteed by construction — firmware runs
  `setRelay(false)` then `sendError("WDOG")` in the same `if`). Temp diagnostic `Log` lines **reverted**;
  stale SM-T220 logcat **removed**; evidence logs kept (`forcestop-test_2026-07-10.log`,
  `bench-multirun_realhw_2026-07-10.log`). **Non-blocking post-merge:** spontaneous-disconnect robustness
  (tolerate-and-resume vs fail-safe) — validate on external 5 V. Full state: memory
  `project-watchdog-bench-debug`.
- [~] **6. Chase the 7 boss confirmations** (from `phase7_blocker_resolution.md`): (1) reference is
  canonical — gates everything; (2) tablet has Google Play Services? → FCM vs WebSocket; (3) GET
  `/transactions/{id}` exists; (4) GET `/config` exists + final payload/units (incl. money unit on
  `amount` — naira vs kobo); (5) confirm offline-USSD 7d deferral; (6) late-payment policy; (7) hosted
  staging URL + test activation code. **Draft ready → `BOSS_CONFIRMATIONS_DRAFT.md`.** Awaiting send +
  answers; on reply → reconcile into `OPEN_QUESTIONS.md` + unblock #8.

## Gated / later

- [x] **5. Debug `network-security-config` for cleartext localhost.** Done 2026-07-04 (uncommitted):
  `src/debug/res/xml/network_security_config.xml` (cleartext to 10.0.2.2/localhost/127.0.0.1) applied
  via `src/debug/AndroidManifest.xml` overlay; `debugRealHw` source set wired to reuse it. Verified in
  both merged manifests; release stays cleartext-denied. _(move to PROJECT_LOG at next phase log.)_
- [ ] **7. Phase 8 — `CustomerViewModel` unit tests.** Awaiting go. Stand up fakes+Turbine. **Scope
  narrowed 2026-07-08:** the disconnect pause/resume path was removed (see #2 note / OQ #21), so the
  first targets are now **boot-resume of the dispensing states** + the litre-cutoff/completion paths
  (pre-pay, USSD, cash-fixed, fill-up shutoff). Open decisions: Log flag vs Logger interface; DAO
  tests in/out.
- [ ] **8. Payment feature flows** — activate → persist creds; authorise → Paystack QR; PAID via
  push + 10 s poll; price config fetcher; WorkManager upload job (re-add `workmanager`).
  **Blocked by #3, #4, #6.** Sandbox-testable; live money gated behind the 14-day parallel run.

## Deferred (parked, not dropped)

- [·] **9. Offline USSD (Flow 5 / sub-phase 7d).** Boss-deferred to a future update. The genuinely
  *offline* path (bank USSD + parsed SMS), distinct from Paystack's *online* USSD. Kept in
  `flows.md`/`state-machine.md`. Revisit with OQ #9–#12 (real bank SMS samples, SIM provisioning,
  ref-collision scheme, per-station code generation).
