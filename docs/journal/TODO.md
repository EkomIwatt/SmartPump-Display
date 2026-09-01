# SmartPump Display — TODO

Living work board. The **PROJECT_LOG** records what's *done*; this file tracks what's *outstanding*.
Keep it current: check items off, add follow-ups as they surface, move finished work into the log.

**Legend:** `[ ]` open · `[~]` in progress · `[x]` done (then move to PROJECT_LOG) · `[·]` deferred/parked

_Last updated: 2026-09-01_

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

## 🔴 API conformance — from the Reference PDF audit (2026-08-05)

Full analysis: [`API_CONFORMANCE_AUDIT.md`](API_CONFORMANCE_AUDIT.md). The network layer was built
against our *summary* of the API, not the Reference itself (which only landed in the repo 2026-08-04).
9 issues found. ~~**#11 blocks all backend integration**~~ — **#11 is FIXED (2026-09-01)**;
**#12 must land before the first real activation.**

- [x] **11. Response envelope not handled — FIXED 2026-09-01** on branch
  `fix/api-response-envelope`. `ApiEnvelope<T>(status, message, data)` added; every
  `PumpApiService` method now returns `ApiEnvelope<T>` and `PumpApiClient` calls `unwrap()`.
  A `status:false` (or `status:true` with no `data`) throws `EnvelopeFailureException`, which
  `safeApiCall` maps to the new `ApiError.Business(message, httpCode)` — **not** retryable, so the
  idempotent upload can't hammer a considered refusal. **Every success fixture in
  `PumpApiClientTest` is now copied verbatim from the Reference's literal §4.1/§4.2/§4.3 JSON**,
  and `PumpSigningInterceptorTest`'s three fixtures were enveloped too (they had encoded the same
  wrong assumption and went red the moment the shape was corrected — which is the point). Added a
  regression test asserting the *old* unenveloped shape now fails as `ApiError.Serialization`.
  Suite green at **87 tests** (12 classes, 0 failures/errors/skips); `compileDebugRealHwKotlin`
  clean. _(move to PROJECT_LOG when the conformance batch is logged.)_
  - **Verified against the primary doc, not a summary:** the PDF has no text layer this machine can
    read (no poppler/pypdf), so the literal JSON was recovered by decoding the PDF's Flate streams
    and ToUnicode CMaps directly. Envelope + all three `data` shapes confirmed field-by-field.
  - **Note for #14:** `ApiError.Business` is the type #14 needs. #14 is now only "parse the envelope
    out of 4xx *error* bodies and fill in `httpCode`" — no new type, and `ApiError.Http` still
    carries the raw blob until it lands.
- [ ] **12. `apiKey`/`signingSecret` logged to logcat (HIGH, security).** `Level.BODY` in debug +
  `redactHeader` only covers headers, so the `/activate` response body prints both secrets.
  `debugRealHw` is a debug build and we commit logcats to `docs/logcats/`. **No leak yet** (grepped
  clean; activation never ran). Fix before the first activation against dev — the secret is emitted
  once and costs a revoke-and-reissue.
- [ ] **13. `pumpId` never persisted (HIGH).** Returned once by `/activate`, required in the body of
  `/authorise` and `/upload`, absent from `PumpCredentials`. Also rename to kill the
  `DeviceConfig.pumpId` ("PUMP 1") vs API `pumpId` (UUID) collision.
- [ ] **14. Error `message` discarded (MED).** Business errors ("Amount mismatch…", "out of stock",
  "Payment has not been confirmed") arrive as opaque blobs. ~~Add `ApiError.Business(code, message)`~~
  — **the type already exists** (added by #11, currently only fed by 2xx envelope refusals). What's
  left: parse the envelope out of 4xx *error* bodies in `safeApiCall`, fill in `httpCode`, and map
  the handful of known messages to attendant-facing copy.
- [ ] **15. Clock skew unguarded (MED).** ±5 min or every request 401s. Enforce automatic network
  time at install; map that 401 to distinguishable attendant copy.
- [ ] **16. `deviceId` has no generator (MED).** Ours to mint, must be stable forever (change →
  revoke-and-reissue). Recommend a random UUID in the encrypted store, not `ANDROID_ID`
  (resets on factory reset).
- [x] **17. `amount` money unit — DECIDED 2026-08-05: NAIRA.** Reference example `amount 7000 /
  expectedLitres 10` → ₦700/L. App stays kobo; repository mapper owns the ÷100. Fails closed at
  `/authorise` if wrong. Recorded in `PumpApiDtos.kt`. _(Decimals still open — see #18.)_
- [ ] **18. Backend/spec asks — fold into #6.** (a) `GET /api/pump/config` doesn't exist and
  **nothing tells the pump its `fuelType`**, which `/authorise` requires; (b)
  `GET /api/pump/transactions/{id}` doesn't exist → no fallback if an FCM push drops;
  (c) does `amount` accept decimals? (integer-only constrains pricing to whole naira/L — a business
  call); (d) full status set (`PAID` is real but missing from the §5 list); (e) what to sign for a
  GET. **Send today — their lead time is the critical path.**

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
  canonical — gates everything; (2) ~~tablet has Google Play Services? → FCM vs WebSocket~~ →
  **ANSWERED 2026-08-04: FCM.** Tablet will have Play Services; we're *advising* for it (better than
  a persistent WebSocket on a kiosk device) and the manager is expected to provide it. Bench SM-T220
  already satisfies it. Ask becomes a ratification, not an open question — see OQ #8; (3) GET
  `/transactions/{id}` exists; (4) GET `/config` exists + final payload/units (incl. money unit on
  `amount` — naira vs kobo); (5) confirm offline-USSD 7d deferral; (6) late-payment policy; (7) hosted
  staging URL + test activation code. **Draft ready → `BOSS_CONFIRMATIONS_DRAFT.md`.** Awaiting send +
  answers; on reply → reconcile into `OPEN_QUESTIONS.md` + unblock #8.

## Gated / later

- [x] **5. Debug `network-security-config` for cleartext localhost.** Done 2026-07-04 (uncommitted):
  `src/debug/res/xml/network_security_config.xml` (cleartext to 10.0.2.2/localhost/127.0.0.1) applied
  via `src/debug/AndroidManifest.xml` overlay; `debugRealHw` source set wired to reuse it. Verified in
  both merged manifests; release stays cleartext-denied. _(move to PROJECT_LOG at next phase log.)_
- [x] **7. Phase 8 — `CustomerViewModel` unit tests.** **DONE 2026-07-31** on branch
  `feature/phase-8-vm-tests` (`88be743`/`98fa167`/`a128457`). 23 pure-JVM tests via hand-written fakes
  + an `UnconfinedTestDispatcher` rule (Turbine unneeded — `ui.value` read synchronously). Covers
  money/cutoff (+never-over-dispense floor, below-min, price guard, audit record), dispensing
  completion (fixed/pre-pay/cash-fixed target + no-overrun + fill-up shutoff), every boot-resume
  branch, and lifecycle (relay-open invariant, cancel teardown, prepay expiry). **Both open decisions
  settled:** Log flag (`isReturnDefaultValues=true`, test-only) over a Logger interface; DAO tests
  **deferred** to stay pure-JVM. Full suite green at **81 tests**. **MERGED to `main` 2026-08-04**
  (merge commit `d2c4283`); post-merge verify on `main` green — 81 tests / 0 failures +
  `compileDebugRealHwKotlin` clean. _(PROJECT_LOG entry filed.)_
- [ ] **8. Payment feature flows** — activate → persist creds; authorise → Paystack QR; PAID via
  push + 10 s poll; price config fetcher; WorkManager upload job (re-add `workmanager`).
  **Blocked by #3, #4, #6.** Sandbox-testable; live money gated behind the 14-day parallel run.

## Deferred (parked, not dropped)

- [·] **9. Offline USSD (Flow 5 / sub-phase 7d).** Boss-deferred to a future update. The genuinely
  *offline* path (bank USSD + parsed SMS), distinct from Paystack's *online* USSD. Kept in
  `flows.md`/`state-machine.md`. Revisit with OQ #9–#12 (real bank SMS samples, SIM provisioning,
  ref-collision scheme, per-station code generation).
