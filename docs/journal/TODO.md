# SmartPump Display — TODO

Living work board. The **PROJECT_LOG** records what's *done*; this file tracks what's *outstanding*.
Keep it current: check items off, add follow-ups as they surface, move finished work into the log.

**Legend:** `[ ]` open · `[~]` in progress · `[x]` done (then move to PROJECT_LOG) · `[·]` deferred/parked

_Last updated: 2026-09-19, later (10d built and the real processor is bound; **10e — error mapping — is next**)_

> **Sorted by who is holding it up:** [`V1_BLOCKERS.md`](V1_BLOCKERS.md) is the same work viewed by
> blocker rather than by phase — useful for "what can move today". It points back here; it does not
> restate detail, so this file stays the source of truth for work items.

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

## ✅ Phase 7b (first half) — device-local operator config — MERGED 2026-09-03

Branch `feature/phase-7b-operator-config` (`05556c1` schema+migration test / `37388c5` guard /
`af43918` screen). **MERGED to `main` as `0cfba90` and pushed** — `main` = `origin/main` =
`0cfba90`. Verified on `main` after merging: 125 JVM tests / 17 classes green, both variants
compile. PROJECT_LOG entry filed.

- **Why it existed:** `/authorise` requires a `fuelType` and nothing in the Pump API supplies one
  (audit §6 #4). Rather than wait on the backend, the pump is now told locally.
- `DeviceConfig.fuelType: FuelType?` at **schema v3** — nullable on purpose, since defaulting to
  PETROL would let a diesel pump authorise against the wrong fuel and price.
- **First Room migration + first migration test** in the project — step 4 of the workflow documented
  at the top of `SmartPumpMigrations.kt`, never previously carried out. `room.testing` was already
  wired and unused.
- Guard now blocks on a missing fuel type as on a missing price; `NotConfigured(missing:Set<Missing>)`
  tells the operator screen *which* field, while the customer sees one message either way.
- **Latent bug fixed:** `seedDefaultConfigIfMissing()` ran in **every** build type, so a fresh
  *release* install seeded itself ₦870/L + "Total Lekki Ph2" and the price guard could never fire in
  production. Now debug-only, which is what the file header always claimed.
- **Deviation flagged:** no operator/settings screen exists in `docs/Strict design screens/`. Entry
  is a chip in the attendant panel's chrome row, **not** a fourth action card (the three are fixed by
  `flows.md`).
- **Accepted risk (OQ #19):** same shared PIN, so any attendant who can authorise a sale can change
  the price. Role-based PINs stay V2.
- Verified: JVM **125 tests / 17 classes** green; `compileDebugRealHwKotlin` clean; **12 instrumented
  green on the SM-T220** (4 migration + 6 Keystore + 2 deviceId).

**Second half (`GET /config` sync) is BLOCKED** — it is item 1 of `BOSS_CONFIRMATIONS_DRAFT.md`.

---

## 🔴 API conformance — from the Reference PDF audit (2026-08-05)

Full analysis: [`API_CONFORMANCE_AUDIT.md`](API_CONFORMANCE_AUDIT.md). The network layer was built
against our *summary* of the API, not the Reference itself (which only landed in the repo 2026-08-04).
9 issues found. **#11, #12, #13 and #16 are FIXED (2026-09-01), device-verified and merged to `main`
(2026-09-02)**: the two that blocked backend integration and the first real activation, plus the two
identity fields — `pumpId` and `deviceId` — that `/activate` settles once and cannot reissue.
**#14, #15 and #18 remain.**

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
    purged — no device has activated.
  - **Device-verified 2026-09-02.** `KeystorePumpCredentialsStoreTest` now runs **6** on the SM-T220
    (was 5 at gate #10): the new `legacyFormatBlob_isPurged_ratherThanPartiallyRead` passes, so the
    `v: 2` purge is confirmed against real KeyStore crypto, and the original five still pass with
    `pumpId` required. _(move to PROJECT_LOG when the conformance batch is logged.)_
- [~] **14. Error `message` discarded (MED) — PARSING HALF DONE 2026-09-12** on branch
  `feature/api-live-probe` (`4970c4e`). `safeApiCall` reads the envelope back out of a non-2xx body
  and returns `ApiError.Business(message, code, httpCode)`.
  - **Built on observed bytes, not inference.** The Reference states the failure envelope in §1 and
    never prints one; the dev probe captured it (`docs/api-probes/2026-09-12/`). Two things the
    captures decided that a careful reading would have got wrong: `code` is a **top-level sibling of
    `message`**, not a field inside `data`; and it is **absent from every observed 401** while
    present on the 400 from `/activate`, so it is nullable and callers must degrade to `message`.
  - **Conservative by design.** Only a JSON object with a real boolean `status:false` counts. An
    HTML 404 (what an undeployed route actually returns), a plain-text 502 and a 4xx whose envelope
    claims success all stay `ApiError.Http` with the bytes intact — a deployment mistake must not
    read as the server declining a sale. Retryability unchanged.
  - **The mapping half is UNBLOCKED — OQ #17 settled 2026-09-12.** The copy now exists
    ([`ERROR_COPY_DRAFT.md`](ERROR_COPY_DRAFT.md), Catalogue A), the customer/attendant split is
    decided and built for local errors, and the attendant surface is the swipe-up panel. What is
    left is wiring, and it **waits on #8**: nothing receives an `ApiError` and sets
    `TransactionState.Error` until the payment feature flows exist, so building the mapping now
    would carry text nothing reads.
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
    instance; credentials `clear()` does not change the id) — **RUN AND GREEN 2026-09-02** on the
    SM-T220 (Android 14): full `connectedDebugAndroidTest` = **8 tests, 0 failures/errors/skips**.
    The `clear()` test is the one that matters — it proves on real hardware that the separate-plain-
    prefs decision holds, i.e. revoke-and-reissue cannot silently take the identity with it.
    _(move to PROJECT_LOG when the conformance batch is logged.)_
- [x] **17. `amount` money unit — DECIDED 2026-08-05: NAIRA.** Reference example `amount 7000 /
  expectedLitres 10` → ₦700/L. App stays kobo; repository mapper owns the ÷100. Fails closed at
  `/authorise` if wrong. Recorded in `PumpApiDtos.kt`. _(Decimals still open — see #18.)_
- [~] **18. Backend/spec asks — MOSTLY ANSWERED BY OBSERVATION; what is left does not block V1.**
  **Updated 2026-09-17 after the gate.** (a)–(f) below were written against the 2026-09-12 dev probe,
  when the shapes were still unverified. The gate verified all of them on production against real
  credentials — see `docs/api-probes/2026-09-16-prod-config/` and `…-prod-gate/`:
  - **(a) `/config` — shape now KNOWN and wholly unlike what was modelled.** One pump, one fuel, one
    price. Rebuilt from bytes in `1c3dc26`. This retired `BOSS_CONFIRMATIONS_DRAFT.md` item 1, the
    ask marked *highest*, before it was sent.
  - **(b) `/transactions/{id}` — shape KNOWN**, and it is the same object `/authorise` returns (#46).
  - **(c) decimals — ANSWERED: accepted**, and the exact `amount == litres × price` check passes on
    one. Forces **#44**.
  - **(d) status set — ANSWERED:** `PENDING_PAYMENT` → `PAID` → `DISPENSED`.
  - **(e) GET signing — ANSWERED** by a 200: `timestamp + "." + ""` is what the server verifies.
  - **(f) stable error codes — ANSWERED, and the half-built reading was the right one.** They exist
    on business failures (`AMOUNT_MISMATCH`, `PAYMENT_NOT_CONFIRMED`, `TRANSACTION_NOT_FOUND`,
    `INVALID_REQUEST`) and on **no** authentication failure. That is a rule, not an inconsistency:
    match business errors on `code`, identify the auth family by 401.
  - **Still genuinely open, and none of it gates V1:** **#29** (no backend home for the `events`
    table), **#48**'s other half (accept a correction, or refuse the repeat with a code instead of a
    200 that reads as success), and **#46** (echo `actualLitresDispensed` so the app can read its own
    record back). Drafted in `BOSS_CONFIRMATIONS_DRAFT.md`.

  _Original 2026-09-12 dev-probe findings, kept because they are how the routes were found at all:_
  Probing
  `api.dev.balancee.app` answered more than the reply did. Evidence: `docs/api-probes/2026-09-12/`.
  - ~~(a) `GET /api/pump/config` doesn't exist~~ — **DEPLOYED.** 401 `Missing pump authentication
    headers` with `X-Matched-Path: /api/pump/config`; an undeployed route returns an HTML 404 with
    `X-Matched-Path: /404`, so this is a real handler. **Its payload shape is still unverified** —
    that needs credentials, and 7b's second half rides on it.
  - ~~(b) `GET /api/pump/transactions/{id}` doesn't exist~~ — **DEPLOYED**, as a parameterised route
    (`X-Matched-Path: /api/pump/transactions/[id]`). Response shape likewise unverified.
  - (c) **does `amount` accept decimals?** Unanswered; unreachable without credentials. Still a
    pricing decision, not just a technical one.
  - (d) **full status set** — unanswered; needs a real transaction to observe.
  - (e) **what to sign for a GET** — unanswered **and untestable from outside**: the server
    validates the API key *before* the timestamp and signature, so a stale `X-Timestamp` and a
    removed `X-Signature` both return `Invalid API key`. Now behind activation, along with (c), (d)
    and the clock-skew window (#15).
  - (f) **stable error codes — HALF BUILT.** The 400 from `/activate` returns
    `"code":"INVALID_REQUEST"` beside `message`; **none of the three observed 401s carry one.** Go
    back with that specific gap rather than re-asking the general question.
  - **Also confirmed for free:** our four signing header names (`X-Api-Key` / `X-Device-Id` /
    `X-Timestamp` / `X-Signature`) are correct — sending them moves the server off "missing headers"
    onto "Invalid API key". That was previously only our reading of Reference §3.

## ✅ Phase 9 — first contact with the real backend — MERGED + PUSHED

Branch `feature/api-live-probe`, three commits off `main` at `3aea28c`. `f77cfb3` probe evidence,
`4970c4e` error-envelope parsing (#14 half), `5a378fe` activation persistence. Verified: JVM **155
tests / 19 classes** green (125 → 155); `compileDebugRealHwKotlin` + `lintDebug` clean. Nothing
device-specific, so no instrumented run needed — **no bench gate on this one.**

**Phase 9c** continues on `feature/onboarding-activation`, branched off the above because it builds
directly on `5a378fe`. One commit, `ce4a0b8`. Verified: JVM **184 tests / 22 classes** green (170 →
184); `compileDebugRealHwKotlin` + `lintDebug` clean, no new lint findings. Also no bench gate.

- [x] **33. The activation code now has a way in.** `PumpActivationRepository` shipped with **no
  caller**, so an arriving code could not be redeemed by an operator. Two entries now share one
  panel and one `ActivationViewModel`:
  - **Onboarding step 4.** Provisioning moved from the PIN match to the end of the new step, because
    writing the identity row *is* what ends onboarding — the gate observes that row — so saving at
    step 3 would have made step 4 unreachable. Activation is **optional**: a pump is often installed
    before its code exists, and cash sales do not need one. The exit reads "Finish without
    activating", not a hidden skip.
  - **The operator settings screen**, behind the attendant PIN — and for most units the *only*
    reachable entry. A pump installed before its code was issued finished onboarding long ago, and a
    debug build auto-provisions a demo identity and never shows onboarding at all. Without this,
    **#32** could not have been driven from the one build that points at the dev backend.
  - `ActivationReport` turns each outcome into operator-facing words **plus what may be done next**.
    The two flags exist for one case: after `Unreachable` the *same* code is safe to resend, while a
    *different* code may burn a spare on a pump the server has already registered. Typing a
    different code there **warns rather than blocks** — support may have confirmed the first never
    landed, and an operator who did the right thing must not be stuck.
  - The **device ID is on screen from the first frame**, because the recovery for an ambiguous
    activation is an operator reading it out to support. `PumpActivationRepository` gains `pumpId`
    so the panel can also say what the unit is registered *as*.
  - ⚠️ **Design-authority flag:** no activation screen exists in `docs/Strict design screens/`, so
    the layout is invention — the same deviation already on record for the error screen. Tokens and
    components are the existing ones.
  - **Not covered by tests:** `OnboardingViewModel`'s step sequencing. It takes an Android `Context`
    for logo decoding and the project has no Robolectric or mocking library, so there is no cheap
    JVM fake. The outcome-to-next-move logic, which is the part worth protecting, has 13 tests.

- [x] **30. Activation now keeps what it is given.** `PumpApiClient.activate()` existed and **nothing
  called it**: it returned the once-only `apiKey`/`signingSecret` and no caller saved them, so
  redeeming the single-use code would have marked the pump activated server-side and dropped the
  keys. `PumpActivationRepository` makes the call, the save and a **read-back** one operation.
  - The **read-back** is the point: `save()` returning proves only that nothing threw, and the
    Keystore store deliberately discards an undecryptable blob, so a bad write looks like success.
  - Outcomes separate `Refused` (nothing issued, try a fresh code) from `Unreachable` (**unknown,
    not "no"** — a timeout can land after the server committed) from `CredentialsLost` (spent, and
    the answer is gone — *including* an unparseable success body).
  - Guards: already-activated is refused **locally** (a valid second code would overwrite and
    abandon the backend's `pumpId`); the `deviceId` echo is checked and a mismatch **keeps** the
    credentials while reporting the disagreement.
- [x] **31. The pre-flight questions — SETTLED 2026-09-16. We are going to production, on purpose.**
  Kept rather than deleted because it moved three times in one day and the reasoning is the useful
  part.
  - ✅ **Are codes re-issuable? YES.** The dashboard has a self-service **Get code** button, and a
    **Revoke** button beside it. Our "single-use, so we cannot proceed on a borrowed one" framing was
    asserted in `API_CONFORMANCE_AUDIT.md` without a quoted Reference line — it was our own
    assumption. A code is single-use; another is one click away.
  - ✅ **Can a pump be reset and re-activated?** The **Revoke** button says yes. Whether the same
    `deviceId` can then re-activate is answerable **by observation** during the sitting rather than
    by asking anyone — worth doing while the pump is throwaway, because the signing cutover's planned
    reinstall mints a new `deviceId` and nobody has tested that path.
  - ✅ **Which environment?** Production. The code came from `smartpump.balancee.app/dashboard/pumps`,
    which posts GraphQL to `api.balancee.app`. The REST pump surface is deployed there and answers
    **byte-identically** to dev (`docs/api-probes/2026-09-16-prod/`), including the inconsistent
    `code` field (#18f) — a contract gap, not a deployment gap.
  - ✅ **Is production acceptable to dirty?** For this pump, yes. `Test Pump 1` / `SN-TEST-001` sits on
    a **dummy business account** the backend dev created for us. The pump record is not precious,
    junk transactions pollute nobody's reconciliation, and repeat runs are free — which matters,
    because a gate exists to be run, to disagree, and to be run again.
  - ✅ **Reaching it from an installable build** — solved by the `debugProd` variant (phase 9d-1).
  - 🚩 **The one thing that did NOT dissolve, and it is not about the pump.** `/authorise` returns
    `authorizationUrl`, a **Paystack checkout URL** (`PumpApiDtos.kt`). On production that is
    presumably Balancee's **live** Paystack integration, so a test pump on a dummy business still
    initialises real payments through the real processor. Nothing moves unless a QR is scanned and we
    will not scan one — but it is worth saying beforehand. **One question, in item 4 of
    `BOSS_CONFIRMATIONS_DRAFT.md`.** It gates *pressing* the authorise button, not *building* it, and
    it gates nothing in steps 1, 2 or 5.
  - ~~Ask for a dev code / a dev authentication path~~ — **dropped.** We were told dev needs no
    activation code (which our own dev 401s do not obviously support, and which no longer matters),
    and we are not going to dev.
- [x] **32. THE GATE — ALL SEVEN STEPS RUN, 2026-09-16.** On production, against the throwaway pump (#31).
  Everything left on the API line is behind it, and it should be done in one sitting while the server is in a known state:
  1. ~~Activate once. Confirm the credentials survive a process restart.~~ ✅ **PASSED 2026-09-16**
     — pump `3727aebf-…`, deviceId echo matched the dashboard, survived a force-stop.
  2. ~~`GET /config` → capture the literal payload~~ ✅ **PASSED 2026-09-16.** The payload matched
     nothing we had: no `prices` map, one pump with one fuel and one price. DTO rebuilt from the
     bytes (`1c3dc26`). Capture: `docs/api-probes/2026-09-16-prod-config/`. **7b's second half is
     unblocked**, and it struck two items off the backend ask (see #31).
  3. ~~`/authorise` happy path, then a deliberate **amount mismatch**~~ ✅ **PASSED 2026-09-16.**
     Both work, and stable codes DO arrive on that path: `AMOUNT_MISMATCH` (#18f). Auth 401s still
     carry none — business failures have codes, authentication failures do not.
  4. ~~Send a **decimal `amount`**~~ ✅ **ANSWERED 2026-09-16: accepted.** 3501.5 for 2.35 L at ₦1490,
     request and response both captured. `amount` must stop being a `Long` — **#44**.
  5. ~~Poll `/transactions/{id}` → the real status set (#18d)~~ ✅ **PASSED 2026-09-16, including a
     paid transaction.** The set is `PENDING_PAYMENT` → `PAID` → `DISPENSED` — the three strings the
     DTOs guessed in July, now observed. An unknown id returns 404 `TRANSACTION_NOT_FOUND`. **#18d
     is closed.**
  6. ~~Confirm **GET signing** and the **clock-skew** strings~~ ✅ **BOTH ANSWERED 2026-09-16.**
     GET signing: step 2's 200 proves `timestamp + "." + ""` is what the server verifies. Clock skew
     (**#15**): a `/config` signed ten minutes in the past returns
     `401 {"status":false,"message":"Request timestamp is not fresh"}` — **the exact string the audit
     predicted from the Reference's prose**, so the copy already drafted in `ERROR_COPY_DRAFT.md`
     stands. No `code` on it, consistent with the auth-failure rule.
  7. ~~`/transactions/upload` last~~ ✅ **PASSED 2026-09-16** — against a genuinely paid transaction
     (0.1 L, ₦149, paid for real). Returns `DISPENSED`. Against an **unpaid** one it returns 409
     `PAYMENT_NOT_CONFIRMED` — the payment gate is enforced server-side, which is the better finding
     of the two (**#45**).
  - Drive it through `PumpApiClient`, **not curl** — what is under test is our signing, our envelope
    parsing and our credential store. A curl script would test a second implementation we do not
    ship.
  - **Do not loosen `PumpLoggingInterceptor`** to see the `/activate` response (#12): assert on the
    parsed object and redact before anything reaches disk.
  - 🚩 **Step 3 is the only one that waits on anything.** `/authorise` returns a **Paystack checkout
    URL**, and on production that is presumably the live Paystack integration — so pressing it
    creates real payment initialisations, on a dummy business or not. One question to the backend
    covers it (item 4 of `BOSS_CONFIRMATIONS_DRAFT.md`); it gates *pressing* the button, not
    *building* it, and steps 1, 2 and 5 are unaffected. Superseded: the earlier reading that the
    whole sequence needed a dev server — the pump is a throwaway on a dummy business (#31).
  - ✅ **Stage 9d-1 BUILT 2026-09-16** (branch `feature/api-probe-panel`) — `debugProd` build type
    (the debug app pointed at production, own applicationId) plus an **API probe panel** on the
    operator screen that runs step 2 through the real client and keeps the literal bytes. Runbook:
    [`GATE_32_RUNBOOK.md`](GATE_32_RUNBOOK.md).
  - ✅ **Stage 9d-2 BUILT 2026-09-16** — every remaining step has a button. Read-only ones
    (`/transactions/{id}`, and a `/config` signed ten minutes in the past for #15) press freely;
    `/authorise` and upload sit behind an acknowledgement switch that resets each time the panel is
    rebuilt. The panel does the amount arithmetic **before** sending and refuses to send a fractional
    naira amount, which is #18c answered by arithmetic: at ₦1490/L, every metered fill-up produces
    one. Runbook: [`GATE_32_RUNBOOK.md`](GATE_32_RUNBOOK.md).
  - _Superseded, kept for the reasoning:_ before 9d-1, `activate()` was the only client method with
    an in-app caller, so `config()`, `authorise()`, `transactionStatus()` and `uploadTransaction()`
    could not be driven at all — and #32 requires driving them through `PumpApiClient` rather than
    curl. That is why the panel was a prerequisite of the gate rather than a nicety.
- [ ] **39. `docs/api-probes/2026-09-12/probe.sh` is re-runnable** _(was a second #33, renumbered
  2026-09-15 at the merge; nothing referenced it by number)_ and sends no secrets. Re-run it
  after any backend deploy to see whether the 401s have grown a `code` field yet (#18f).
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
- [x] **43. The QR expiry is 20 minutes, not 5 — FIXED 2026-09-18 in phase 10c.** The countdown
  reads the server's `expiresAt`; the two remaining “five minutes” are the signing freshness window,
  which is a different thing and is disambiguated in place. _(original entry)_
  Measured four times on 2026-09-16, always 20 min 1 s between the authorise and its `expiresAt`
  (`docs/api-probes/2026-09-16-prod-gate/`).
  - `TransactionState.kt:50` — *"5-min expiry, then auto-cancel back to Idle"*. **This is the one that
    costs money:** a screen that gives up at five minutes abandons a sale the server would still have
    honoured for another fifteen, and the customer is standing at the pump while it does.
  - `PumpApiDtos.kt:75` — the same claim in a comment on `expiresAt`.
  - `PumpRequestSigner.kt:6` — *"within 5 min of server clock"*. A **different** five minutes: the
    signing freshness window, still unmeasured. #15's probe only proves ten minutes is too old.
  - **Fix is not a new constant.** `AuthoriseResponse.expiresAt` is a server timestamp and the expiry
    countdown should read it, so the day the backend changes the window nothing here has to notice.
    Ours to do, inside the payment flows (#8).
- [x] **44. `AuthoriseRequest.amount` must stop being a `Long` — DONE 2026-09-17 in phase 10b.**
  `BigDecimal` + `NairaAmountSerializer`. The precision sub-question below is **still open** and is
  now sharper than when it was written: see the pre-pay finding under 10b, which is a different
  problem from the one this item anticipated and bites at a whole-naira price too.
  _(original entry follows)_
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
- [ ] **45. `PAYMENT_NOT_CONFIRMED` is a refusal that can become a success — and our taxonomy has no
  word for that.** Observed 2026-09-16 (`docs/api-probes/2026-09-16-prod-gate/`):
  ```
  POST /api/pump/transactions/upload → 409
  {"status":false,"message":"Payment has not been confirmed for this transaction. Do not dispense
   until payment is confirmed.","code":"PAYMENT_NOT_CONFIRMED"}
  ```
  - **The good half:** the server enforces the payment gate itself. It will not record fuel against an
    unpaid sale, which is a safety property nobody had verified and which does not depend on the app
    behaving.
  - **The defect-in-waiting:** a 409 with an envelope parses as `ApiError.Business`, and
    `ApiResult.kt:52` makes every `Business` **not retryable** — documented as "a considered refusal".
    This one is not. It is true *now* and may be false in a minute, once payment confirms. An upload
    job that treats it as final **drops the record permanently**, and a dispense that never reaches
    the backend is the one outcome the upload job exists to prevent.
  - **Where it bites:** not the ordinary pre-pay flow, where money lands before fuel does. It bites
    when an upload fires before the server has confirmed payment — a missed `PAID` push, a poll that
    timed out, a queued upload replayed early after a restart.
  - **Shape of a fix:** a third outcome beside retryable/terminal — *retry later, not now* — keyed on
    the `code` rather than the prose. `isRetryable` is the wrong question for it; WorkManager needs
    "reschedule with backoff" while the operator needs to not be told the sale failed. Decide when 7e
    is built, but it must be decided, not discovered.
  - **Unrelated but adjacent:** this endpoint cannot be the home for **cash** sales either — it demands
    a `paymentReference` only `/authorise` issues. `V1_BLOCKERS.md` already says a cash sale has
    nothing to upload; this is the server agreeing.
- [x] **46. Three DTOs are three partial views of one resource — FIXED 2026-09-18 in phase 10c.**
  One `PumpTransactionResponse`, three typealiases. _(original entry)_ Observed 2026-09-16 (§9 of
  `docs/api-probes/2026-09-16-prod-gate/`): `/authorise`, `/transactions/{id}` and
  `/transactions/upload` all return the **same object** — `{status, transactionId, paymentReference,
  authorizationUrl, expiresAt}` — differing only in `status` and the envelope's `message`.
  - `AuthoriseResponse` has all five. `TransactionStatusResponse` and `UploadTransactionResponse`
    have three, so `authorizationUrl` and `expiresAt` **parse away silently** under
    `ignoreUnknownKeys`.
  - **The one that matters is `expiresAt` on a status poll.** #43 says the expiry countdown must read
    the server's value rather than a 5-minute constant; the poll is where a running screen would
    refresh it, and today it is discarded before any caller sees it.
  - **Fix:** one `PumpTransactionResponse` behind the three names, or the two thin ones gaining the
    missing fields. Cheap now, and it removes a class of "why does the poll know less than the
    authorise did" confusion later. Do it with #8, from the captured bytes.
- [x] **47. Upload does NOT validate `actualLitresDispensed` — ANSWERED 2026-09-16.** 0.2 L was
  accepted against a sale authorised and paid for 0.1 L, with a second `200 Transaction recorded`.
  - **What that buys:** every case where actual and expected legitimately differ can be reported — a
    tank that fills before the target, an attendant ending a fixed sale early (OQ #22), and the pulses
    7h recovers after a restart. The under-counting posture in #28 and #36 survives contact.
  - **What it exposed:** the endpoint is an **upsert**, not a reject — see **#48**.
- [ ] **48. A dispense can be recorded once and never corrected — and the app is told otherwise.**
  Corrected 2026-09-17 from "last-write-wins", which was read out of a 200 and was wrong in the more
  dangerous direction.
  - **Observed:** a second upload for an already-`DISPENSED` transaction, carrying 0.2 L instead of
    0.1, returned `200 Transaction recorded` — and the dashboard still shows **0.1**. First write
    wins; the repeat is acknowledged and discarded.
  - **The retry is safe.** `retryingApiCall` repeats an identical upload, which is now demonstrably
    harmless. That question is closed.
  - **The hazard is correction, not duplication.** If a dispense is ever uploaded with the wrong
    litres — a bug, a bad K-factor, a figure sent before 7h's reconciliation finished — re-uploading
    the right one **succeeds loudly and changes nothing**. The station's record stays wrong while
    every log in the app says "recorded".
  - **Why it was invisible:** the reply does not echo `actualLitresDispensed` (#46), so a 200 is the
    only signal the app gets, and it means "accepted", not "stored". Nothing in the API can read the
    figure back; only the dashboard shows it.
  - **Ours (7e):** upload once per transaction and never re-send a superseded figure, because the
    first send is the only one that counts. **Theirs — a fifth item for #18:** either accept a
    correction, or refuse the repeat with a code instead of a 200 that reads as success.
- [x] **49. Uploaded dispenses ARE visible to an operator — confirmed 2026-09-17.** The dashboard's
  per-pump Transactions view lists each transaction with its state and, for a dispensed one, the
  litres recorded. That is the counterpart the **14-day parallel run** needs: something to reconcile
  the app's litres against. Worth knowing it exists before the run, not during it.
  - Caveat kept: the **API** cannot read that figure back (#46), so verification is a person opening
    a web page. The app cannot check its own record.

- [ ] **41. Credentials can only arrive by redeeming a code — and dev may not use codes.**
  `PumpActivationRepositoryImpl:54` is the **only** writer of `PumpCredentialsStore` in the app;
  everything else reads. So if dev hands over an `apiKey` + `signingSecret` + `pumpId` directly
  (which is the most likely reading of "dev does not require an activation code" — see **#31**),
  there is nowhere to put them and the whole dev path is unreachable.
  - **Shape:** a debug-only load path, sensibly part of the probe panel (**#32**) rather than a
    second screen. It must go through `PumpCredentialsStore.save()` and then **read back**, for the
    same reason `persist()` does — a Keystore blob that cannot be decrypted is discarded silently,
    so a write that returns is not a write that worked.
  - **Guards to keep:** `BuildConfig.DEBUG` only; never log the secret (the `/activate` allowlist in
    `PumpLoggingInterceptor` and the redacting `toString()` on `PumpCredentials` both stay, #12); and
    it must not become a way to hand-edit credentials on a live pump.
  - **Do not build speculatively.** It is cheap, but which of the three forms dev answers with
    decides whether it is needed at all.

---

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

## 🟢 Phase 7h — pulse continuity across restarts (BUILT, gate PASSED 2026-09-13, ready to merge)

Branch `feature/phase-7h-pulse-continuity`, five commits, off `main` at `3aea28c`. Closes the live
under-billing in OQ #25. **Needed nothing from Olonade, the backend, the boss or the meter** — the
adapter already broadcasts its cumulative in the ~2 s `HB` keep-alive, so the count is readable
while idle with no protocol change.

| step | commit | what |
|---|---|---|
| 1 | `66fd353` | schema **v4** — `pulse_state.adapterCount`, `transactions.recoveredLitres`, new `events` table + migration |
| 2 | `30872d3` | `PulseSource.adapterCount` / `awaitAdapterCount()`; anchor written on every persist |
| 3 | `d9da72f` | `ReconcilePulseGapUseCase` — pure classification, 16 tests |
| 4 | `d60483f` | boot resume applies it; `EventRepository`; the over-target safety branch |
| 5 | `51f0ce0` | "Fuel log" card on the operator screen, behind the attendant PIN |

**Verified:** JVM **165 tests / 21 classes** green (125 → 162 at build, → 165 with the gate fix);
**16 instrumented green on the SM-T220** (12 → 16, the 4 new migration tests incl. a chained
v2→v4); `compileDebugKotlin`, `compileDebugRealHwKotlin`, `lintDebug` clean. Six commits now: the
original five, plus `15dee70` from the bench gate.

- [x] **27. MERGE GATE — bench run with the Arduino. PASSED 2026-09-13**, all eight steps, on an
  Arduino Uno with the sketch from `main` and no flow meter (`ENABLE_AUTO_PULSE` supplies the
  pulses, ~50 pps = 30 L/min at the placeholder K). The recovery path has now met a real board.
  - **Steps 3, 4, 5, 7 passed.** A resumed sale shows more litres than it did at the kill, by the
    fuel that moved while the app was blind; `recoveredLitres` lands on the sale; an unplugged
    adapter logs "did not respond" and adds nothing; the Fuel log card renders.
  - **Step 8 passed.** Killed two seconds into a ₦2,000 pre-pay, the relay did **not** reopen on
    resume and the sale completed recording more litres than were charged. See **#37** — the
    *size* of that overshoot is the finding, not the behaviour.
  - **Step 6 passed, three times.** The board was reset mid-sale and the app refused to attribute
    anything, said so in red, and **kept its own count** rather than adopting the post-boot zero
    (e.g. the board fell 5805 → 0 and the app carried on from 0.51 L). The count regressed by
    0.01 / 0.09 / 0.12 L across the three, all inside the quarter-litre the 25-pulse save interval
    allows.
  - **A defect was found and fixed during the gate** (`15dee70`): boot resume added the recovered
    pulses to memory and left the database alone, so a second restart inside the next 25 pulses
    re-reported the same fuel. Two fuel-log rows that overlapped instead of two that added up. The
    sale's arithmetic was never wrong — count and anchor are read as a pair — but the operator's
    record of what went missing was. Three tests, two of which fail without the fix.
  - **How it was diagnosed:** a temporary trace (`53fa746`, `ec9399c`, reverted in `3631b38`)
    printed the reconciliation's own operands. It had to render **on screen**, not to logcat,
    because the tablet's USB-C port cannot be an adb link and an Arduino host at once and the
    wireless link would not hold for more than a few seconds. Revert `3631b38` to get it back.
  - **Bench-rig note for whoever repeats this:** the meter input (pin 2) is a bare `INPUT_PULLUP`
    with nothing attached, and a floating pin counts noise as pulses. Tie it to 5 V for any run
    where the synthetic generator is the pulse source. Untied, gaps ranged 1.11–4.48 L; tied, the
    spread closed to 1.09–3.07 L and a kill from idle recorded **nothing**. This is the same bare
    pullup **#22** already says must not survive into production.

- [ ] **28. `MAX_PLAUSIBLE_GAP_PULSES = 400` needs a THIRD term, not just re-deriving.** Still true
  that it must be recomputed once the real pulses-per-litre is known (OQ #1) — but the 2026-09-13
  bench run showed the derivation is also **structurally short**. It assumes the anchor is at most
  `PULSE_PERSIST_EVERY_N` (25) pulses stale. The anchor is written every 25 pulses **as processed by
  the app's collector**, and on the SM-T220 that collector falls behind the board: measured gaps
  reached **307 pulses** where the three-second watchdog window alone allows ~150. So the real
  staleness is bounded by collector lag, not by the save interval, and 400 is tight enough to refuse
  genuine fuel — a **4.48 L gap was rejected on the bench and was almost certainly real**. Rejecting
  under-bills, so it fails safe, but the station absorbs it. Add a lag term when recomputing.
- [ ] **29. The `events` table has no backend home.** Nothing on the server accepts these rows.
  **A fifth ask for #18**, currently not on that list. The upload job (7e) can carry them once an
  endpoint exists.
- [ ] **36. The app loses ~20 pulses per restart.** New, and only visible once the trace was on
  screen. Tracking the offset between the board's count and the app's transaction count across one
  sale with three restarts: 2389 → 2391 → 2414 → 2436 → 2456, so the app ends each cycle ~22 pulses
  (~0.22 L) behind the board, 67 across the run. It **under**-counts, so the customer is never
  overcharged and the station absorbs it — the same direction as OQ #25's original defect, two
  orders of magnitude smaller. Suspected cause: the pulses between the resume's adapter reading and
  the collector attaching, which `PulseAccumulator` swallows in its uninitialised branch. Not fixed:
  it is small, it fails safe, and it wants its own change with its own test.
- [x] **37. FIXED 2026-09-15 (`fix/receipt-struck-price`) — the simpler fix, not the one proposed
  below.** Carrying the price on `Complete` was judged too much for what it buys. Instead the
  completion screen now reads `uiState.priceKoboPerLitre`, the same ViewModel price the audit row
  and the shared receipt are written from, and `onStartTransaction()` now refreshes that UI copy —
  the pre-pay path passed neither of the other two refreshes, so after a price change the screen
  showed the boot-time price. One new test (`CustomerViewModelMoneyTest`), fails without the fix.
  - **Accepted, not fixed:** that ViewModel price is reloaded from config at boot, so a sale that
    completes *after an app restart* records today's price, even if the operator changed it during
    the restart window. Both receipts still agree, on the new price. Judged rare enough — it needs
    a PIN-gated price edit inside an interrupted sale — to not justify threading the price through
    every state. Revisit if the parallel run ever shows it.
  - _Original entry, kept for history:_ **The receipt COMPUTES price/litre instead of carrying it.** `CustomerStateHost`'s
  `priceKoboPerLitreFromState` prints `round(amountKobo / litres)`, so the "Price / L" line moves
  whenever litres and money come apart — which pulse-gap recovery and the step-8 overshoot now make
  routine. After the gate's ₦2,000 overshoot the receipt understated the unit price by nearly half:
  **it states a price the station has never charged, and it reads cheapest exactly when the customer
  got fuel for free.** The right number already exists — `Transaction.priceKoboPerLitre` is on the
  audit row — but `TransactionState.Complete` does not carry it, which is why the screen resorts to
  arithmetic. Fix by carrying the price through to the screen.
  - **Check when merging forward:** receipt *sharing* (**#35**, on the Phase 9 line) builds its text
    from the saved audit row. If so, the shared receipt and the on-screen one disagree about the
    same sale, which is worse than either being wrong alone.
    **CONFIRMED on `main` after the 2026-09-15 merge:** the shared text prints the audit row's
    `transaction.priceKoboPerLitre` (`ReceiptText.kt`), the screen prints
    `priceKoboPerLitreFromState` (`CustomerStateHost.kt`). They now disagree on exactly the sales
    where litres and money came apart. Fixing #37 as described — carry the price on `Complete` —
    closes both, since the share text is already right.
  - Found during the 7h gate, but **not a 7h defect** — recovery only made it visible. Fix
    separately.
- [ ] **38. Shorten the firmware watchdog from 3 s to 2 s?** The app PINGs at 1 Hz, so three seconds
  is three missed pings; two would still tolerate a hiccup and would **halve** the give-away
  measured in step 8. One-line firmware change, so it belongs with **#19**'s firmware work rather
  than on its own. Not a substitute for **OQ #26** — see there.

## 🔼 Phase 10 — payment feature flows (#8) — PLANNED, NOT STARTED

_Planned 2026-09-17, immediately after the gate (#32) closed. **Awaiting an explicit go.**_

Branch `feature/phase-10-payments` off `main` (`84d6f49`). Committed sub-deliverable by
sub-deliverable, 3a-style. Every commit leaves the build green.

**What makes this different from every previous attempt at #8:** it is implemented against
*observed* behaviour, not against a PDF. The whole lifecycle has already been driven by hand through
the probe panel on production, and the bytes are in `docs/api-probes/2026-09-16-prod-gate/`. Those
captures are the test fixtures.

**Two facts that shape the whole design, both confirmed at the gate:**
- `transactionId` is **ours** — client-generated, sent on `/authorise`, echoed back unchanged. The
  app owns the id *before* the call, which is what makes resuming a poll after a restart possible
  rather than a second sale.
- The server enforces the payment gate itself (**#45**): it refuses to record fuel against an unpaid
  transaction. That safety property does not depend on the app behaving.

### Scope boundary — what is deliberately NOT in this phase

- **FCM push.** No device-token registration endpoint exists anywhere in the API, and there is no
  Firebase code in the project. **OQ #8 already rules push is a freshness optimisation only and the
  poll carries the correctness guarantee** — so poll-only is not a shortcut, it is the design.
  Leave the seam; add push when there is a server side to add it to.
- **Flow 5 offline USSD** — boss-deferred (**#9**).
- **Cash flows** — no API involvement. `/upload` demands a `paymentReference` only `/authorise`
  issues, so a cash sale has nothing to upload and must not enqueue one.
- **Turning live money on.** This phase makes digital payment *work*. It stays gated behind the
  K-factor and the 14-day parallel run.

### Sub-deliverables

- [x] **10a — Widen the payment seam. DONE 2026-09-17.** The blocker in front of everything else.
  `PaymentProcessor.process(method, amountKobo)` cannot express what `/authorise` requires
  (`expectedLitres`, `fuelType`, `pumpId`), and `PaymentResult.Pending(transactionRef, method)`
  cannot carry what the screen needs (`authorizationUrl`, `expiresAt`, the server-side ids).
  Widen both. `MockPaymentProcessor` keeps working — it fabricates a URL and a 20-minute expiry —
  so the debug path and all existing tests stay green. **No behaviour change**; committed alone so
  the real processor's diff is readable against it.
  - **Built:** `PaymentRequest(method, amountKobo, expectedLitres)`; `PaymentResult.Pending` gains
    `checkoutUrl` / `expiresAt` / `paymentReference`, `Success` gains `paymentReference`. `pumpId`
    and `fuelType` deliberately stay **off** the request — they are properties of the device, not of
    the sale, so 10c's processor sources them from credentials and `DeviceConfig` rather than making
    four screens remember facts about the pump they run on.
  - **`litresFor(amountKobo)` extracted in the VM**, because the figure now has two consumers that
    must not disagree: the cutoff the pump enforces and the `expectedLitres` quoted to `/authorise`.
    The server's check is exact, so a separately-derived quote is a refused sale, not a rounding
    error. Tested directly (`prepay expectedLitres equals the cutoff the pump will enforce`).
  - **The mock now carries the measured 20-minute expiry** (#43) rather than the old 5, and its
    checkout URL points at a `.invalid` host — a mock QR must not be payable.
  - **Question surfaced, deliberately not answered here:** `onPaymentSuccess` derives litres from
    `success.amountKobo` while its fallback uses the requested `amountKobo`. Identical under the
    mock; with a real backend one of them has been round-tripped. **10c/10d must decide which is
    authoritative** — left as a comment at the site rather than silently unified.
  - Verified: JVM **297 tests / 34 classes** green (was 287 / 32); `compileDebugRealHwKotlin` and
    `lintDebug` clean.
- [x] **10b — Money representation (#44). DONE 2026-09-17.** `AuthoriseRequest.amount` stops being a `Long`.
  Fixtures from the gate: `3501.5` for 2.35 L at ₦1490, request and response both captured.
  **Decision to confirm at go:** `BigDecimal` + serializer (recommended — the wire value is decimal
  naira, the server's check is *exact*, and a float makes an exact-equality check rot on figures
  that look right) vs. kobo-`Long` serialised as a decimal. Cap at 2dp and refuse to send more: the
  app carries kobo so it cannot express a third decimal, and a rounded one is a **rejection**, not
  an approximation. Precision beyond 2dp stays open (#44) — ₦1490 hides it.
  - **Built:** `BigDecimal` (user-confirmed), with `NairaAmountSerializer` emitting a **bare JSON
    number in plain notation with trailing zeros stripped**. All three properties are load-bearing:
    the signature is computed over these exact bytes, so a quoted string is a different request;
    `stripTrailingZeros()` alone renders 3500.00 as `3.5E+3`, which is valid JSON and absurd in a
    payment; and stripping is what sends `2980` rather than `2980.00`.
  - **`nairaFromKobo(kobo)`** (exact — scale set, never divided) and **`nairaForSale(litres,
    koboPerLitre)`**, the latter being the server's own check computed the same way it computes it.
  - **The probe's "cannot be expressed" refusal is gone**, because it is no longer true. It is how
    #18c was first answered and it now lives in the log rather than in the code. `AmountPlan` stays
    as a *label* on the probe screen — telling the operator which case a litre figure lands on is
    still worth seeing — but it no longer gates what can be sent. `authoriseRaw` is kept and its
    comment corrected: it exists to ask what the **server** does with a body we would never build.
  - **⚠ Finding for 10c, pinned in a test so it cannot be rediscovered as a surprise:
    pre-pay's tendered amount and the exact product disagree.** Litres are floored to 2dp, so at
    ₦1490/L a ₦5,000 pre-pay buys 3.35 L — worth ₦4,991.50. The server's check is exact, so sending
    the ₦5,000 the customer actually handed over is a **refused sale**, not a 50-kobo discrepancy.
    Charge for the litres, or quote unfloored litres: a product decision, and **10c must make it**.
    Test: `pre-pay amount and the exact product disagree when the price does not divide evenly`.
  - **→ The decision is made, and the remaining half is being MEASURED rather than guessed
    (2026-09-17).** Discussed with the user, who pushed back on tuning anything to ₦1,490 — rightly.
    - **Quoting the tendered amount cannot be built.** ₦5,000 ÷ ₦1,490 = 3.35570469798657718…, a
      non-terminating decimal. There is no unfloored litre figure to send, and no truncation of it
      multiplies back to exactly ₦5,000.
    - **It is also the option that is *fragile* to price changes, which inverts the argument for
      it.** Whether the quotient terminates depends on the price's prime factors: at ₦1,250 a
      ₦5,000 pre-pay is exactly 4 L and everything works; at ₦1,490 it never terminates and the sale
      is refused. Same code, different month's price, different outcome. **Deriving the amount from
      the litres satisfies the exact check at every price, forever** — it computes the server's own
      equation instead of hoping it comes out even. **DECIDED: never send the tendered amount.**
    - **What is left is one measurement:** how many decimal places of litres the server accepts. At
      2dp the customer's shortfall is up to `0.01 × price` (≈₦14.90 today, ~0.3% of a ₦5,000 sale);
      at 4dp it is under a kobo. We have only ever *observed* one decimal place accepted (`3501.5`).
      **Built: the `Precision` probe** (`AuthoriseVariant.Precision`) sends a 4dp litre figure and
      its exact 4dp product, models a realistic pre-pay, and reports the shortfall at both scales.
      A refusal is reported as a **caution, not a pass** — it is half the answer, not a failed test.
    - Unaffected either way: what the pump physically stops at (a pulse boundary, and the real
      K-factor will not be round) — **#47** confirmed the upload accepts whatever actually flowed.
      The authorise is a quote; the upload reports reality.
  - **✅ PROBE RUN 2026-09-17 — ACCEPTED, and it moved the answer.** Capture:
    `docs/api-probes/2026-09-17-prod-precision/`. `expectedLitres: 2.3536` with `amount: 3506.864`
    returned 200 `PENDING_PAYMENT`, so **the server's parser is not the constraint** — 4dp litres and
    a 3dp amount both pass the exact check. (Sixth independent measurement of the 20-minute
    `expiresAt`, too.)
    - **But the accepted amount cannot be paid.** ₦3,506.864 is **350,686.4 kobo**, and Paystack
      charges in whole kobo. The server accepted an amount the rail underneath it cannot collect and
      said nothing. Nothing was scanned, so what checkout does with the fractional kobo is
      **unobserved on purpose** — the fix is to never send one, not to go and find out. Same shape as
      **#48**: a 200 means *this endpoint accepted it*, not *this is correct end to end*.
    - **So the rule is not "quote at 4dp".** It is **"quote at the finest scale whose product is an
      exact number of kobo"**, and that scale moves with the price — 3dp at ₦1,490, only 2dp at
      ₦1,491 or ₦870.50, any scale at ₦1,250. A fixed scale is wrong at some price, which is the same
      fragility the tendered-amount option was rejected for. **10c computes it per price.**
    - At today's ₦1,490 the shortfall falls **₦5.50 → ₦1.03** on a ₦3,507 tender; at ₦1,250 it is zero.
    - No further sitting needed: 3dp is strictly coarser than the 4dp just accepted.
  - Verified: JVM **306 tests / 35 classes** green (was 297 / 34); `compileDebugRealHwKotlin` and
    `lintDebug` clean.
- [x] **10c — `/config` → `/authorise` → a QR that can actually be paid. DONE 2026-09-17/18**
  (`96b5241`, `ef17770`).
  - **#46 done:** one `PumpTransactionResponse` behind three typealiases, verified byte-for-byte
    against the gate captures. `authorizationUrl` and `expiresAt` had been parsing away silently on
    every poll and upload. Making the payment fields nullable immediately caught a real call site.
  - **`SaleQuote` done:** the litre step is derived from the price
    (`10_000 / gcd(price, 10_000)`) — 0.001 L at ₦1,490, 0.01 L at ₦1,491, 0.02 L at ₦870.50. **A
    first version using a plain decimal scale was wrong** and would have produced uncollectable
    amounts at a sub-naira price; invariants are now checked across five prices and six tenders.
  - **The QR is real.** It encoded `balancee://pay?txn=…`, a scheme no scanner resolves and no bank
    honours. Now the Paystack checkout URL, with the reference shown instead when there is none.
  - **#43 done:** the countdown runs on the server's `expiresAt`; a restored sale resumes the
    persisted deadline; a past deadline ends the sale. `PumpRequestSigner`'s five minutes is
    disambiguated, not changed — different window, still unmeasured.
  - **`PaymentRequest` gains `SaleBasis`** so a re-pricing processor knows which end is fixed.
  - **NOT bound in DI** — 10d flips it once the poll exists.
  - Verified: JVM **341 tests / 39 classes** green; `compileDebugRealHwKotlin` + `lintDebug` clean.
  - **⚠ New, for the board:** for a `Dispensed` basis the fuel is already in the tank, so a price
    change between the nozzle clicking off and the QR appearing changes what is owed — and the
    customer watched the old figure climb on the display. Unavoidable from the processor (the server
    checks against its own price), so it needs a **policy**: refuse, warn the attendant, or honour
    the struck price via a backend change. Rare; not a blocker; must not be discovered in the field.

  _(original entry)_
- [x] **10c — `BalanceePaymentProcessor`: `/config` → `/authorise` → a QR that can actually be paid.**
  Fetch-before-authorise is the correctness guarantee (OQ #8), then authorise, then emit `Pending`
  carrying the real checkout URL and the server's `expiresAt`.
  - **The QR today renders a fabricated payload** — `balancee://pay?txn=…`,
    `PrepayAwaitingPaymentScreen.kt:353`. **Nobody can pay it.** It must become the Paystack
    `authorizationUrl` (`https://checkout.paystack.com/…`). This is the single most user-visible
    change in the phase.
  - Carries **#43**: the expiry countdown reads `expiresAt` off the response rather than a constant,
    so the day the backend changes the window nothing here has to notice. Correct the stale
    5-minute claims at `TransactionState.kt:50` and `PumpApiDtos.kt:75`. **Leave
    `PumpRequestSigner.kt:6` alone except to disambiguate it** — that is a *different* five minutes
    (the signing freshness window) and is still unmeasured; #15's probe only proves ten is too old.
  - Carries **#46**: one `PumpTransactionResponse` behind the three names, built from the captured
    bytes, so the poll stops silently discarding the `expiresAt` that #43 needs.
- [x] **10c-bis — sync the price from `/config`. DECIDED AND DONE 2026-09-19.**
  _All three calls went the recommended way: build it now rather than fold it into 10e; the server
  wins and the operator's typed price becomes the pre-activation fallback; the residual race
  proceeds at the server's price and is logged._
  - **`PumpConfigSync` is the write-through.** `fetch()` does what `client.config()` did and then
    stores it, so the two callers get the sync for free: the boot path (`CustomerViewModel`, its own
    coroutine — a network call must never sit in front of the relay-open invariant or a resumed
    sale) and every authorise (`BalanceePaymentProcessor`, which already fetched and threw away).
  - **Only price and fuel type are taken.** `pumpLabel` and `virtualAccountNumber` are the
    operator's. `stationName` is deliberately **not** taken though the response carries one — see
    the new board item below.
  - **The operator's field stays writable, and now says why it exists**: `/config` is a signed call,
    so a pump that has not redeemed its activation code cannot reach it at all, and an unreachable
    backend must still leave the pump selling at the last price it knew. The settings screen states
    that Balanceè sets the price, and shows when it last changed.
  - **An activated-but-never-configured pump is now sellable from the backend alone**, which is what
    retires `BOSS_CONFIRMATIONS_DRAFT.md` **item 1** (the ask marked *highest*) in code rather than
    on paper.
  - **Two new `EventType`s**, both written only when something actually moved: `PRICE_SYNCED` (the
    stored price was replaced — the operator's only evidence the screen changed with nobody at the
    pump) and `PRICE_CHANGED_MID_SALE` (the `Dispensed` race). A first sync is not a change, and an
    unchanged price writes nothing at all, so `updatedAt` keeps meaning *when the price changed*
    rather than *when we last had signal*.
  - The operator screen's card is now the **Pump log**, one chronological list with a headline per
    event kind — a price row rendered by the fuel wording read "Amount unknown" in red, which is
    lost fuel, which is not what happened.
  - Verified: JVM **357 tests / 40 classes** green (was 341 / 39); `compileDebugRealHwKotlin` and
    `lintDebug` clean.

  _(original entry, as it stood when the decision was owed)_
  - [ ] **10c-bis — sync the price from `/config`. DECISION PENDING — START HERE.**
    _Raised 2026-09-18 while answering “what do we do if the price changes mid-fill-up”. The question
    turned out to rest on a false premise, and the real finding is bigger than the policy._
    - **Nothing ever writes the server's price into `DeviceConfig`.** `PumpConfigResponse` has exactly
      three consumers — `PumpApiClient`, `PumpApiService` and `BalanceePaymentProcessor`. The only
      writers of `DeviceConfig` are the debug screen, the operator settings screen and the VM's own
      seeded default.
    - **So the displayed price and the authorised price are unrelated numbers**, and nothing
      reconciles them. This is not a rare race: it is a **permanent divergence**, zero today only
      because someone typed 1490 to match what the server happens to hold.
    - This is **#18(a) / 7b's second half**, marked BLOCKED since 2026-09-03 *because the payload shape
      was unknown*. It has been known since 2026-09-16 and 10c already parses it. **The block is
      stale.**
    - It also retires `BOSS_CONFIRMATIONS_DRAFT.md` **item 1** — the ask marked *highest*, about every
      price change becoming a physical visit to every pump. The endpoint that kills it has been live
      since 2026-09-16 and the app has not consumed it.
    - **Proposed:** fetch on boot, and cache what the processor already fetches before every authorise,
      so display and authorise agree by construction. Small; the parsing exists.
    - **Then the residual price-change race is genuinely seconds wide.** Recommended policy: proceed at
      the server's price and write an operational event to 7h's fuel log. *Not* refusing — refusal
      degrades to the existing cash path (`FillupAwaitingCashConfirm`, which never touches the API) at
      the struck price, which is defensible but forces cash on someone who chose digital.
    - **Honouring the struck price is NOT available to us:** the server's check is an equality against
      its own price, so any other amount is a refused sale. That needs a backend change — **add to the
      #18 asks**, do not wait on it.
    - **Open:** do this as 10c-bis before 10d (recommended), or fold it into 10e. Not "later".

- [ ] **NEW — `DeviceConfig.stationName` vs `StationIdentity.displayName`: two station names.**
  Receipts print the first (`ReceiptText.kt:67`); every customer screen shows the second
  (`CustomerStateHost.kt:108,126,143`). `/config` carries a third. 10c-bis declined to reconcile
  them by side effect — a price sync silently changing what receipts say is the wrong way to
  settle it. Small, and wants deciding before the parallel run prints receipts anyone keeps.

- [ ] **NEW — backend ask (goes with #18): honour the price a fill-up was struck at.**
  The server checks `amount == expectedLitres × pricePerUnit` against **its own** price, so a
  fill-up that ends seconds before a price change cannot be charged at the figure the customer
  watched climb. 10c-bis narrowed the window to seconds and logs each occurrence; closing it needs
  the server to accept a struck price (or a struck-at timestamp) on `/authorise`. **Do not wait on
  it** — an improvement, not a gate.

- [x] **10d — PAID detection by poll. DONE 2026-09-19** (`42064e1`, `b347188`, `8aa2879`).
  - **The poll.** `process()` no longer suspends after `Pending`; it polls
    `GET /transactions/{id}` every 10 s until the sale resolves or the server's `expiresAt` passes.
    **What ends it early is a short list on purpose** — `PAID`, `DISPENSED`, `TRANSACTION_NOT_FOUND`
    and `NotActivated`. An unrecognised status, an unparseable reply, a 500 and no signal all keep
    polling: giving up on a word nobody has observed would refuse fuel to someone who has paid,
    while riding to the deadline costs a wait the server's own window bounds. `DISPENSED` counts as
    paid — a sale that completed and uploaded before a restart is a paid sale.
  - **The boot-resume trap, closed on BOTH digital flows.** The board named the pre-pay one;
    checking found `startFillupDigitalPayment` had it too, and worse, because a fill-up's fuel is
    already in the tank. `PaymentProcessor.resume(ref, request, deadline)` is its own method rather
    than a flag: it polls the sale that exists and never authorises. The deadline comes off the
    persisted state — the server's window kept running while the app was down.
  - **Two money bugs found on the path and fixed here.**
    - `Pending` now carries **what will actually be collected**. At ₦1,490/L a ₦5,000 pre-pay
      authorises at ₦4,998.95, and the screen was printing ₦5,000 beside a checkout page saying
      ₦4,998.95 — two numbers for one sale, with the customer looking at both.
    - `Success` now carries the **authorised litres**, answering the question 10c left in a comment
      at `onPaymentSuccess`. Re-deriving them from the amount gives 3.35 against an authorised
      3.355, stopping the pump 5 ml short of what was paid for on the same figure 10f will
      reconcile against the server's record. Persisted on `PrepayAwaitingPayment` so a resume keeps
      it.
  - **Flow 3's QR was still unpayable and is fixed here** — see the entry below; 10c fixed pre-pay
    only.
  - **The DI flip is per build type, not one line.** New `MOCK_PAYMENTS` buildConfigField mirroring
    `MOCK_HARDWARE`: mock on `debug` / `debugRealHw` (dev backend, no activated pump, and the debug
    screen's controls only exist on the mock), real on `debugProd` / `release`. The debug screen
    says so in red when the controls are inert. Verified on the real-payments graph specifically —
    `assembleDebugProd` builds, so Hilt resolves `BalanceePaymentProcessor` and its `Clock`.
  - Verified: JVM **378 tests / 41 classes** green (was 357 / 40); `compileDebugRealHwKotlin`,
    `lintDebug` and `assembleDebugProd` clean.

- [x] **NEW, found and fixed inside 10d — Flow 3's QR could not be paid either.**
  10c fixed `PrepayAwaitingPaymentScreen`'s fabricated payload and left
  `onFillupPayDigital()` building `nip://transfer?account=…` against the operator's virtual account
  — well-formed, resolvable by no scanner, honoured by no bank. OQ #6 had retired the virtual
  account when payments moved to Paystack, and this was the last call site keeping it alive; the
  state class had been *documented* as carrying a checkout URL since 10c, which it never did. The QR
  is now the checkout URL off `Pending`, the screen holds on `FillupTankFull` until it arrives
  (mirroring Flow 1), and blank content renders the reference instead of a QR of nothing.

- [ ] **NEW — no build type is both real hardware and production.** `debugProd` takes real
  payments on the mock pulse source; `debugRealHw` drives the Arduino against the dev backend. 10g
  can prove the payment path on `debugProd` and 7h's bench gate covered the hardware, so this is not
  a blocker — but the parallel run's release build will be the first time the two run together,
  and that should be a deliberate decision rather than a discovery.

  _(original entry)_
  - [ ] **10d — PAID detection by poll.** ~10 s poll over `GET /transactions/{id}` across the
    `PENDING_PAYMENT` window, terminating on `PAID`, on `expiresAt`, or on cancel.
    - **The boot-resume trap, and the reason this is its own deliverable.**
      `CustomerViewModel:1095` already restarts a `process()` call after a restart. Against a mock
      that is free; against a real server it would **authorise a second sale** for a customer who has
      already paid for the first. Because `transactionId` is ours, the correct behaviour is to resume
      polling the existing id. Tests first, on that path specifically.

- [ ] **10e — Error mapping (#14's mapping half, #45).**
  - **#45 — the taxonomy needs a third outcome.** `PAYMENT_NOT_CONFIRMED` is a 409 that parses as
    `ApiError.Business`, and `ApiResult.kt:52` makes every `Business` non-retryable — documented as
    "a considered refusal". This one is not: it is true now and false in a minute. Add *retry later,
    not now*, keyed on `code` and never on prose. An upload job that treats it as final **drops the
    record permanently**, which is the one outcome the upload job exists to prevent.
  - Wire `ERROR_COPY_DRAFT.md` Catalogue A into the customer's one plain line and the attendant
    panel's detail. This is the half that was blocked on #8 — unblocked now, because something
    finally produces an `ApiError` a customer can see.
- [ ] **10f — Upload job (7e).** Add `workmanager` (confirmed absent from
  `gradle/libs.versions.toml`), a `TransactionUploadWorker`, and the thing that finally sets
  `syncedAt`.
  - Carries **#48**: upload **once** per transaction, and never re-send a superseded figure. An
    identical retry is proven harmless; a *corrected* one returns `200 Transaction recorded` and
    changes nothing, so a wrong figure sticks while every log in the app says it went through.
  - **#47** confirmed the endpoint accepts any litres figure, so partial dispenses, OQ #22 early
    ends and 7h's recovered pulses can all be reported honestly.
- [ ] **10g — Gate: run it on the tablet against production.** The probe panel proved the
  *endpoints*; this proves the *app*. A real small sale end to end through the customer UI against
  `SN-TEST-001`, scanning the QR with a phone. Sized like the #32 sitting. Nothing merges until it
  passes.

### Risks worth naming before starting

- **10c and 10d change money-handling code paths that 23 `CustomerViewModel` tests already cover.**
  Expect the suite to go red honestly rather than quietly — that is what those tests are for.
- **The QR change is irreversible in the field in one direction:** a build that renders the old
  fabricated payload cannot take a payment, so there is no half-shipped state worth having.
- **Live Paystack.** Every `/authorise` in 10g creates a real payment initialisation, as the gate
  did. Small amounts, dummy business, but real.

---

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
- [x] **35. Receipt sharing — DONE 2026-09-12.** Was a no-op: `CustomerViewModel.onShareReceipt()`
  was an empty function while the Share button was **live** on the completion screen, so a customer
  tapped it and got silence. Now builds a plain-text receipt and sends it to the Android system
  share sheet (**OQ #14**, already resolved — no bespoke print-to-cashier channel). Part of 7f.
  - **Plain text deliberately:** the OS offers WhatsApp / SMS / email, and the receipt has to
    survive being pasted into any of them. A PDF or image renders in some and is useless in others.
  - **The record is re-read from the audit log**, not rendered from screen state: the state carries
    no completion time, so a screen restored after a power cut would otherwise be dated "now".
    Needed a new `TransactionRepository.getTransaction(id)` + DAO query. Falls back to screen state
    if the row is missing, which is possible because `saveTransaction` is best-effort.
  - `buildReceiptText` is a pure function with an injectable zone, so the exact characters a
    customer receives are asserted in tests rather than eyeballed.
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
  staging URL + test activation code. **Draft ready → `BOSS_CONFIRMATIONS_DRAFT.md`.**
  - **NO LONGER BLOCKS #8 — updated 2026-09-17.** The gate answered (3), (4) and the money unit by
    observation, and item 4 of the draft (live Paystack?) by paying ₦149 through it. (1) is moot now
    that behaviour has been observed directly: **the wire outranks the Reference**, and where they
    disagreed the wire was right. What is still worth sending is (5), (6) and the three remaining
    backend asks under **#18** — all improvements, none of them gates.

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
- [~] **8. Payment feature flows — UNBLOCKED 2026-09-17. Moved to its own section: see
  “Phase 10” below.** Was: *blocked by #3, #4, #6*. All three are stale — #3 (transport client) and
  #4 (credentials store) shipped in July, and the gate answered #6's blocking items by observation
  rather than by reply. Kept here as a pointer so the old cross-references still land somewhere.

## Deferred (parked, not dropped)

- [·] **9. Offline USSD (Flow 5 / sub-phase 7d).** Boss-deferred to a future update. The genuinely
  *offline* path (bank USSD + parsed SMS), distinct from Paystack's *online* USSD. Kept in
  `flows.md`/`state-machine.md`. Revisit with OQ #9–#12 (real bank SMS samples, SIM provisioning,
  ref-collision scheme, per-station code generation).
