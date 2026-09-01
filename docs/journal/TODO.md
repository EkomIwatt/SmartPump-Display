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
9 issues found. **#11, #12, #13 and #16 are FIXED (2026-09-01)**: the two that blocked backend
integration and the first real activation, plus the two identity fields — `pumpId` and `deviceId` —
that `/activate` settles once and cannot reissue. **#14, #15 and #18 remain.**

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
- [x] **12. `apiKey`/`signingSecret` logged to logcat — FIXED 2026-09-01** on branch
  `fix/api-response-envelope`. **No leak ever occurred** — `docs/logcats/` re-grepped for
  `signingSecret`/`apiKey`/`bal_live`/`sec_…`/`X-Signature`: zero hits across all three committed
  logs, consistent with activation never having run. Two doors closed:
  - **The wire.** New `PumpLoggingInterceptor` replaces the raw `HttpLoggingInterceptor` in
    `NetworkModule`. Body logging is now an **allowlist** (`/authorise`, `/config`,
    `/transactions/upload`, `/transactions/{id}`); everything else — `/activate`, and any endpoint
    added later, such as the credential rotation anticipated in OQ #8 — drops to `HEADERS`. Chosen
    over a denylist deliberately: the failure mode of forgetting to update it is thinner logs, not
    a leaked secret. The `X-Api-Key`/`X-Signature` header redactions are kept.
  - **`toString()`.** `PumpCredentials` and `ActivateResponse` are both data classes, so their
    generated `toString()` printed both secrets in full — any stray `Log.d(TAG, "$creds")`, crash
    report or `ApiResult` dump leaked them just as surely. Both now redact. _(Not in the audit;
    found while fixing the wire path.)_
  - **Tests (12 new).** Assert on what was actually **written** — a real OkHttp stack against
    MockWebServer with a collecting logger, fed the Reference's literal `/activate` response —
    rather than on how the interceptor is configured, which is what was wrong before. Covers: the
    secrets never appear; the single-use `activationCode` never appears; `/activate` is *still*
    logged at header level (guards the wrong fix of just silencing logging); `/authorise` still
    logs bodies in full; and the allowlist predicate incl. default-deny + base-URL-prefix cases.
  - Suite green at **99 tests** (14 classes, 0 failures/errors/skips); `compileDebugRealHwKotlin`
    clean. _(move to PROJECT_LOG when the conformance batch is logged.)_
- [x] **13. `pumpId` never persisted — FIXED 2026-09-01** on branch `fix/api-response-envelope`.
  `pumpId` is now a **required** field on `PumpCredentials` and on the store's `StoredCredentials`
  — required rather than defaulted, so activation code cannot construct credentials without it.
  That *is* the enforcement; there is no activation flow yet (#8) to remember to do it.
  - **Collision killed by renaming the other one:** `DeviceConfig.pumpId` → `pumpLabel` (plus the
    matching UI parameters and the debug form's "Pump label"), so `pumpId` now means the API UUID
    everywhere. The Room column keeps its name via `@ColumnInfo(name = "pumpId")` → **no migration**;
    verified the generated `identityHash` is unchanged at `2c9cd927…`.
  - **Stored blob is now versioned (`v: 2`).** A pre-#13 blob fails to decode and is purged like
    corrupt ciphertext. Deliberate: defaulting `pumpId` to `""` would decode cleanly and then send
    an empty pumpId to `/authorise` → `401 pumpId does not match authenticated device`, an opaque
    401 in the field where "not activated" is the honest, recoverable answer. Nothing real is
    purged — no device has activated. _(move to PROJECT_LOG when the conformance batch is logged.)_
- [ ] **14. Error `message` discarded (MED).** Business errors ("Amount mismatch…", "out of stock",
  "Payment has not been confirmed") arrive as opaque blobs. ~~Add `ApiError.Business(code, message)`~~
  — **the type already exists** (added by #11, currently only fed by 2xx envelope refusals). What's
  left: parse the envelope out of 4xx *error* bodies in `safeApiCall`, fill in `httpCode`, and map
  the handful of known messages to attendant-facing copy.
  - **The Reference's full error catalogue is now extracted** (2026-09-01, same Flate/ToUnicode
    decode as #11 — the audit had only sampled it): global codes 400 / 401 / 404, eight literal 401
    auth messages, and per-endpoint tables for §4.2 and §4.3. §1 states the failure envelope
    explicitly (`status:false`, `data` absent, `message` = reason), but the doc never prints a
    literal failure *body* — so parse defensively and fall back to `ApiError.Http` when a 4xx body
    is not envelope-shaped, or a plain-text 502 from a proxy becomes `Business(null)`.
  - **Blocked on copy, not on parsing.** Mapping messages to attendant-facing text needs copy that
    does not exist: there is no error screen in `docs/Strict design screens/` and OQ #17 is open.
  - **New #18 ask:** the API returns human message strings only, several with interpolated values
    ("Amount mismatch for PETROL…", "Fuel type not available at station: PETROL"). Matching on
    substrings breaks silently if the backend rewords — **request stable error codes** alongside
    `message`.
- [ ] **15. Clock skew unguarded (MED).** ±5 min or every request 401s. Enforce automatic network
  time at install; map that 401 to distinguishable attendant copy.
  - **Mapping half is ready and rides on #14.** Exact strings confirmed from the Reference:
    `Request timestamp is not fresh` (clock skew > 5 min from server UTC) and `Invalid request
    timestamp` (malformed / non-ISO-8601) — two different causes, and only the first means "fix the
    clock", so they want distinguishable copy.
  - **Enforcement half has no home yet.** The app is not a device-owner app (kiosk lock-task still
    deferred), so it **cannot set the clock itself**; the most it can do is read
    `Settings.Global.AUTO_TIME` and warn. Whether that gate lives in the debug screen now, waits for
    the activation flow (#8), or becomes a physical install-checklist item is an open call.
- [x] **16. `deviceId` has no generator — FIXED 2026-09-01** on branch `fix/api-response-envelope`.
  `DeviceIdProvider` (domain seam) + `PersistentDeviceIdProvider` mint a random UUID once and never
  re-mint. UUID over `ANDROID_ID` as recommended (`ANDROID_ID` resets on factory reset — the exact
  maintenance action a technician performs on a misbehaving kiosk).
  - **Departs from the audit on storage:** kept in its own **plain** prefs file, *not* the encrypted
    credentials blob. The deviceId is not secret (it goes out as `X-Device-Id`), and the encrypted
    store deliberately drops its blob on KeyStore invalidation or corruption — so storing identity
    there would silently mint a new deviceId on the next boot, which is the very failure this issue
    exists to prevent. A separate file also survives credentials `clear()`, so re-activation
    presents the identity the backend already knows.
  - **Two hardenings the issue didn't name:** a failed write **throws** rather than returning an
    id that only exists in memory, and `PumpApiClient.activate()` now sources the deviceId from the
    provider instead of taking it as a parameter, so no caller can supply an ad-hoc one.
  - **Tests:** 8 pure-JVM (mint-once, reuse across a fresh instance, blank-is-absent, failed-write
    throws, 8-thread concurrent first call, UUID shape) via a `DeviceIdStorage` seam — deliberately
    off-device, unlike the crypto store's coverage which needed a device and then sat unrun for
    weeks. Plus 2 **instrumented** tests for what only a device can show (persistence across a fresh
    instance; credentials `clear()` does not change the id) — **written, not yet run: needs the
    tablet.** _(move to PROJECT_LOG when the conformance batch is logged.)_
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
