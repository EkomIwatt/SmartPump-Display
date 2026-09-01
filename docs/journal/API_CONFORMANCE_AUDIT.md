# Pump API — conformance audit against the Reference PDF

**Date:** 2026-08-05
**Auditor:** Claude (paired with Ekomobong Iwatt)
**Trigger:** [`docs/pump-api-reference-v3.pdf`](../pump-api-reference-v3.pdf) was added to the repo
on 2026-08-04. This is the first time the **primary** API document has been available to read
directly — the network layer was built in July against `docs/phase7_blocker_resolution.md`, our
*summary* of it.
**Scope:** all 526 lines of the Reference vs. every file in `data/network/`, `domain/network/`, and
`di/NetworkModule.kt`.
**Outcome:** 9 issues (3 live defects, 3 design gaps, 3 backend/spec gaps) + 2 minor observations.

---

## 1. Why this audit happened

`phase7_blocker_resolution.md` was written from a v3 `.docx` walkthrough of the API. It was accurate
about **which endpoints exist** and **how request signing works** — and the code that implements
those parts is correct (see §5, "Verified correct"). What the summary did not carry was the
*shape* of what comes back, and two once-only values.

That is the through-line of this audit: **every defect found is a payload-shape or lifecycle-value
problem, and none is a logic problem.** Where the summary was precise, the code is right. Where the
summary compressed, the code guessed — and the guesses are wrong in a way that unit tests written
from the same guesses could never catch.

---

## 2. Severity summary

| # | Issue | Severity | Owner |
|---|---|---|---|
| 1 | Response envelope not handled — every API call fails | **Critical** | us |
| 4 | `GET /api/pump/config` does not exist; no source for `fuelType` | **Critical** | backend |
| 7 | `pumpId` never persisted — emitted exactly once, unrecoverable | **High** | us |
| 9 | `apiKey` + `signingSecret` printed to logcat in debug builds | **High** | us |
| 5 | `GET /api/pump/transactions/{id}` does not exist — no way to observe payment | **High** | backend |
| 2 | Error `message` discarded — business errors surface as opaque blobs | Medium | us |
| 3 | Clock skew (±5 min) unguarded — silent field failure mode | Medium | us |
| 8 | `deviceId` has no generator or stability policy | Medium | us |
| 6 | Spec ambiguities: `amount` decimals, full status set, GET signing rule | Medium | spec |
| — | Poll cadence (10s) vs read timeout (20s) can overlap | Low | us |
| — | No certificate pinning on a payments API in an unattended kiosk | Low | us |

---

## 3. Live defects in committed code

### #1 — Response envelope not handled (Critical)

**Reference §1:** *"Every response from the server follows a standardized response envelope"*

```json
{ "status": true, "message": "Human readable message", "data": { … } }
```

Every endpoint's documented `Success Response (200 OK)` nests the real payload inside `data`.

**What we do:** `PumpApiService.kt:25-41` returns the *inner* shape directly — e.g.
`suspend fun authorise(@Body body: AuthoriseRequest): AuthoriseResponse`.

**Impact:** against the real server, deserialisation fails on **all five calls, including the
unsigned `/activate`**:

- top-level `status` is a **Boolean** (`true`); `AuthoriseResponse.status` is declared `String` → type mismatch
- `transactionId`, `paymentReference`, `authorizationUrl`, `expiresAt` live one level down → missing-field failure

The network layer as committed **cannot talk to this backend at all.**

**Why tests didn't catch it:** `PumpApiClientTest.kt:70` feeds MockWebServer
`{"status":"PENDING_PAYMENT","transactionId":"T1",…}` — unenveloped, matching our DTOs rather than
the documented contract. Same at line 129 for upload. The fixtures encode the same wrong assumption
as the production code, so the suite is green and proves nothing about the wire contract.

> **Lesson worth keeping:** "network layer tests green" was never evidence the contract was right.
> Fixtures hand-written from an assumption test only self-consistency.

**Fix:** add `ApiEnvelope<T>(status: Boolean, message: String, data: T)`, return
`ApiEnvelope<AuthoriseResponse>` etc. from `PumpApiService`, unwrap in `PumpApiClient`, and rebuild
every test fixture from the Reference's literal JSON examples. ~1 hour.

> **RESOLVED 2026-09-01** (branch `fix/api-response-envelope`) — implemented as described. Envelope
> refusals (`status:false`, or `status:true` with no `data`) now surface as `ApiError.Business`,
> which is not retryable. `PumpSigningInterceptorTest` carried the same unenveloped assumption in
> three fixtures and went red on the corrected shape — the audit's point, demonstrated. Suite green
> at 87 tests. The Reference's `data` field sets for `/activate`, `/authorise` and `/upload` were
> re-checked field-by-field against the DTOs and all three match; only the nesting was wrong.

---

### #2 — Error `message` discarded (Medium)

**Reference §1:** on failure, `status` is `false` and *"the message string provides the error reason."*

**What we do:** `SafeApiCall.kt:24-26` puts the raw body into `ApiError.Http(code, body)` with no parsing.

**Impact:** the documented business errors are all `400`s carrying an actionable `message` —
*"Amount mismatch for PETROL…"*, *"PETROL is currently out of stock"*, *"Fuel station has an invalid
price per unit"*, *"Payment has not been confirmed for this transaction. Do not dispense fuel."*
These are the **normal operational vocabulary** of this API, not exceptional cases, and an attendant
cannot act on an opaque blob.

**Fix:** parse the envelope on the error path and add `ApiError.Business(code, message)`. Map the
handful of known messages to attendant-facing copy.

---

### #9 — Credentials printed to logcat in debug builds (High, security)

**What we do:** `NetworkModule.provideOkHttpClient` sets `HttpLoggingInterceptor.Level.BODY` when
`BuildConfig.DEBUG`, with `redactHeader("X-Api-Key")` and `redactHeader("X-Signature")`.

**The gap:** `redactHeader` redacts **headers only**. The `/activate` **response body** is precisely
where the secrets are:

```json
{ "data": { "apiKey": "bal_live_…", "signingSecret": "sec_xxxx…" } }
```

**Impact:** the one response that must never be logged is logged in full. This directly contradicts
Reference §4.1's *"CRITICAL SECURITY NOTE: Cache apiKey and signingSecret inside secure
hardware-encrypted storage immediately"* and our own `PumpCredentials.kt` docstring ("never leave the
device or be logged"). We built Keystore AES-256-GCM to protect the secret at rest and then hand it
to logcat in plaintext on arrival.

**Why it matters *here* specifically:** `debugRealHw` is a debug build (`initWith debug`), it is what
runs on the bench tablet against the dev backend, and **capturing logcats into `docs/logcats/` is
established practice on this project** — three are committed already. The realistic failure is a
bench logcat containing a live credential being committed to git.

**Current exposure: none.** `docs/logcats/` was grepped for `signingSecret` / `apiKey` / `bal_live` /
`sec_` / `X-Signature` — **zero hits**. Nothing has leaked because activation has never run. The
exposure is entirely ahead of us.

**Fix (before any real activation runs):** drop `/activate` to `Level.HEADERS`, or add a body-redacting
interceptor for that path. Do this *before* the first activation against dev, because the secret is
emitted exactly once and cannot be rotated without revoke-and-reissue at the station.

> **RESOLVED 2026-09-01** (branch `fix/api-response-envelope`). `PumpLoggingInterceptor` replaces the
> raw `HttpLoggingInterceptor`: body logging is an **allowlist** of vetted paths, so `/activate` —
> and anything added later — logs headers only. Allowlist over denylist deliberately: forgetting to
> update it costs log detail, not a secret.
>
> **This audit under-scoped the issue.** Both types that hold the credentials — `PumpCredentials` and
> `ActivateResponse` — are data classes, so their generated `toString()` printed `apiKey` and
> `signingSecret` in full. A single `Log.d(TAG, "$creds")`, an uncaught-exception dump, or logging an
> `ApiResult` from activate would have leaked them without the HTTP logger being involved at all.
> Both now redact. Worth generalising: "don't log X" is not a property of one code path, it is a
> property of the *type*, and the audit only looked at the path.
>
> Exposure re-confirmed as **none**: `docs/logcats/` grepped again across all three committed logs —
> zero hits for `signingSecret`/`apiKey`/`bal_live`/`sec_…`/`X-Signature`.

---

## 4. Design gaps — not yet wrong, but will be

### #7 — `pumpId` is never persisted (High)

`PumpCredentials` (`domain/network/PumpCredentials.kt:14-18`) holds `deviceId`, `apiKey`,
`signingSecret`. **Not `pumpId`.**

But `/activate` returns `pumpId` in the same once-only response, and **both `/authorise` and
`/upload` require it in the request body**. The activation code is single-use, so a `pumpId` not
captured at activation is unrecoverable without the station revoking and reissuing.

**Name-collision trap:** `DeviceConfig.pumpId` is `"PUMP 1"` — a display label for the screen. The
API's `pumpId` is `"7f108b57-7559-4837-8dfb-33c7aac7d632"`. Same field name, unrelated values, and
wiring the label into the request body yields `401 pumpId does not match authenticated device`.
**Recommend renaming one of them** (`DeviceConfig.pumpLabel`, or `PumpCredentials.pumpUuid`) rather
than relying on care.

**Fix:** add `pumpId` to `PumpCredentials` + `StoredCredentials`, persist at activation.

> **RESOLVED 2026-09-01** (branch `fix/api-response-envelope`). `pumpId` is now a **required**
> field on `PumpCredentials` and on the store's `StoredCredentials` — required rather than
> defaulted, so activation code physically cannot construct credentials without it. That is the
> whole enforcement: there is no activation flow yet (#8) to "remember" to persist it in.
>
> The name collision was killed by renaming the *other* one: `DeviceConfig.pumpId` → `pumpLabel`
> (and the matching UI parameters), leaving `pumpId` to mean the API UUID everywhere. The Room
> column keeps its original name via `@ColumnInfo(name = "pumpId")`, so the rename needs **no
> migration** — verified: the generated `identityHash` is unchanged at `2c9cd927…`.
>
> The stored blob is now **versioned** (`v: 2`). A pre-#13 blob fails to decode (no `pumpId`) and
> is purged as unreadable, exactly like corrupt ciphertext. Deliberate: defaulting `pumpId` to `""`
> would decode cleanly and then send an empty pumpId to `/authorise`, whose answer is
> `401 pumpId does not match authenticated device` — an opaque 401 in the field where a
> "not activated" prompt is the honest, recoverable outcome. No device has activated, so nothing
> real is being purged.

---

### #3 — Clock skew unguarded (Medium)

**Reference §3:** *"The server clock and device clock must align within a strict 5-minute window, or
the system rejects the packet"* → `401 Request timestamp is not fresh`.

`NetworkModule` provides `Clock.systemUTC()` — the device clock, with nothing detecting drift. A
kiosk tablet with no SIM, behind station wifi, can drift. The failure presents as *"payments randomly
stopped working"* with no clue pointing at the clock.

**Fix:** enforce automatic network time at install (onboarding checklist), and map that specific 401
to a distinguishable `ApiError` with attendant-facing copy naming the clock.

---

### #8 — `deviceId` has no generator or stability policy (Medium)

**Reference §4.1:** `deviceId` is *"A unique hardware identifier defined by your hardware client
software"* — i.e. **ours to mint**, sent to `/activate` and thereafter as `X-Device-Id`.

Nothing in the codebase creates one. `PumpApiClient.kt:26` takes it as a parameter and no caller
exists yet.

**Constraint:** it must be generated once *before* activation and remain stable **forever**. If it
changes, every signed request fails `401 Device id does not match credential`, recoverable only by
revoke-and-reissue.

**Recommendation:** a random UUID minted once into the encrypted credentials store. `ANDROID_ID` is
tempting but resets on factory reset — which is exactly the maintenance action a confused technician
performs on a misbehaving kiosk.

> **RESOLVED 2026-09-01** (branch `fix/api-response-envelope`). `DeviceIdProvider` (domain seam) +
> `PersistentDeviceIdProvider` mint a random UUID on first use and never re-mint. The UUID
> recommendation is taken as written; the storage recommendation is **not**.
>
> **Departure from this audit: the deviceId is stored in its own plain SharedPreferences file, not
> in the encrypted credentials store.** It is not secret — it travels in the clear as
> `X-Device-Id` — so encryption buys nothing, and putting it in the encrypted blob would couple
> identity to the KeyStore key. `KeystorePumpCredentialsStore` deliberately *drops* its blob when
> that key is invalidated or the ciphertext is unreadable; if the deviceId went with it, the next
> boot would silently mint a new identity — which is precisely the unrecoverable failure this
> issue is about, reintroduced by the fix. A separate file also means credentials `clear()`
> (revoke/reissue, debug re-onboarding) cannot touch it, so re-activation presents the identity
> the backend already knows.
>
> Two further hardenings the issue did not name: a **failed write throws** rather than returning an
> in-memory-only id (activating against an id that will not survive a reboot is the same
> unrecoverable state), and `PumpApiClient.activate()` now **sources the deviceId from the provider
> instead of taking it as a parameter**, so no caller can introduce an ad-hoc one.

---

## 5. Verified correct — no action

Balance matters; the parts the summary specified precisely are right:

- **`PumpRequestSigner`** — `HMAC-SHA256(secret, timestamp + "." + rawBody)`, hex, ISO-8601 UTC
  `yyyy-MM-dd'T'HH:mm:ss'Z'` with no sub-second component. Matches Reference §3 exactly.
  - The open "hex case" question is now **closed**: their Node reference implementation uses
    `.digest("hex")`, which is lower-case, matching ours.
- **`PumpSigningInterceptor`** — signs the already-serialised body buffered off the request and only
  adds headers, so the bytes signed are the bytes sent. Correctly honours §3's *"do not re-serialize
  after signing"*. `@Unsigned` correctly exempts `/activate`.
- **All four required headers** present and correctly named.
- **`retryingApiCall`** on upload only — correct, since §4.3 documents upload as idempotent on
  `transactionId` while `/authorise` is not safe to blind-retry.
- **`KeystorePumpCredentialsStore`** — AES-256-GCM at rest, device-verified 2026-07-08.
- **`FuelType` enum** — `PETROL` / `KEROSENE` / `DIESEL` / `COOKING_GAS` matches §4.2 exactly.

---

## 6. Backend / spec gaps — require the boss

### #4 — `/config` does not exist, and `fuelType` has no source (Critical)

The Reference documents **exactly three endpoints**, confirmed by its own §5 cheat sheet:
`/activate`, `/authorise`, `/transactions/upload`. `GET /api/pump/config` is **our proposal**, not
their endpoint.

This is worse than "prices are stale". `/activate` returns only `deviceId`, `pumpId`, `apiKey`,
`signingSecret` — **nothing about the station**. Meanwhile `/authorise` **requires** `fuelType`.

**The pump therefore has no way to learn which fuel it dispenses.** As the contract stands the
payment flow cannot close at all without that value being hardcoded or hand-entered.

Against `DeviceConfig`:

| Field | Source available? |
|---|---|
| `pumpId` (API UUID) | ✅ `/activate` — but not persisted (#7) |
| `koboPerLitre` | ❌ nowhere |
| `stationName` | ❌ nowhere (receipts need it) |
| `fuelType` | ❌ nowhere — **and `/authorise` requires it** |
| `virtualAccountNumber` | ❌ nowhere — and likely obsolete (OQ #6: Paystack owns payments) |

**Proposed minimal payload** (small asks get built):

```json
{ "pumpId": "7f108b57-…",
  "stationName": "Total Lekki Ph2",
  "fuelType": "PETROL",
  "pricePerUnit": 700,
  "updatedAt": "2026-08-04T09:00:00Z" }
```

Single `fuelType` rather than a map fits V1 (multi-nozzle is V2 per OQ #20) and mirrors `/authorise`
taking exactly one. This replaces our invented `PumpConfigResponse(prices: Map<FuelType, Long>)`,
which guessed at prices and missed the fuel-type assignment entirely.

**`/activate` cannot absorb this:** it fires once and its secrets are emitted once, but prices change
weekly. Static fields could ride along; price fundamentally cannot.

---

### #5 — `GET /api/pump/transactions/{id}` does not exist (High)

Our design detects payment via FCM push + a 10s poll fallback. The poll endpoint isn't in the
Reference.

`PAID` **is** a real status — §2's lifecycle diagram states *"Customer Pays → [Paystack Webhook] →
Transaction status set to PAID"*, and §4.3 errors with *"Payment has not been confirmed for this
transaction"*. So the backend tracks it; there is simply no endpoint exposing it. Note `PAID` is
**absent from the §5 status list** (which shows only `PENDING_PAYMENT` and `DISPENSED`), so the full
status set needs confirming.

Without this endpoint the pump is **wholly dependent on push delivery** — and FCM is best-effort. No
fallback means a dropped push strands a paying customer at the pump.

---

### #6 — Spec ambiguities (Medium)

- **`amount` unit — DECIDED: naira** (2026-08-05). The Reference never states a unit (zero hits for
  `kobo`/`naira`/`NGN`/`currency`), but §4.2's worked example — `amount: 7000`, `expectedLitres: 10`
  → ₦700/L — only reads sensibly as naira; as kobo it would be ₦7/L. Recorded in
  `PumpApiDtos.kt`. The app stays kobo internally; the repository mapper owns the ÷100 as the single
  flip point.
  - *De-risked by design:* the server enforces `amount === expectedLitres × stationPricePerUnit`
    exactly (`400 Amount mismatch`), so a wrong unit **fails closed at `/authorise`** before money
    moves or fuel flows. It cannot mischarge a customer 100×.
  - *Caution:* this project has been burned by an illustrative example before — the 7a framing note's
    `PULSE:0042817*7C` checksum was wrong (real XOR-8 is `5D`). Pin the Reference's example as a
    golden-vector test when the mapper lands.
- **Does `amount` accept decimals? — OPEN, and sharper than the unit question.** The example is
  integer naira. A fill-up of 38.1 L at ₦870.50/L is ₦33,166.05, which integer-naira cannot express —
  and because the server check is *exact*, a rounded `33166` is **rejected**, not merely off by a
  naira. If integer-only, station pricing is constrained to whole naira per litre — a **business**
  decision, not a technical one.
- **GET signing rule — OPEN.** §3's formula assumes a body; the Reference has no GET endpoints, so
  nothing specifies what to sign for one. We currently sign `timestamp + "." + ""`. Only matters for
  the two endpoints that don't exist yet — resolve in the same conversation.

---

## 7. Recommended sequence

**Ours, unblocked, can start immediately:**

1. **#1 envelope** — first. Nothing else is testable until responses parse, and it produces the
   corrected fixtures everything else is verified against.
2. **#9 credential logging** — before any real activation runs against dev. The secret is emitted
   once; a leak costs a revoke-and-reissue.
3. **#2 error messages** — naturally alongside #1 (same envelope parsing).
4. **#3 / #7 / #8** — with the activation flow, since that's the code that will consume them.

**Theirs — send today; their lead time is the long pole:**

5. One message covering #4, #5, #6. This is now the critical path for the whole payment phase.

**Interim recommendation for #4:** build a **device-local operator config screen** (fuel type, price,
station name) as 7b's first half, with the `/config` fetch stubbed behind the existing
`DeviceConfigRepository` seam. This is not throwaway — it's also the disaster fallback when the
backend is unreachable at boot, and 7b needs a settings surface regardless. It unblocks us today
rather than waiting on their sprint.

---

## 8. Standing lesson

The network layer was built against a *summary* of the contract rather than the contract. The summary
was good — signing and endpoint inventory are correct — but compression dropped the envelope, the
once-only `pumpId`, and the `fuelType` requirement.

**Where a spec is available, build fixtures from its literal examples**, not from our reading of it.
Had `PumpApiClientTest` used the Reference's verbatim JSON, #1 would have been caught in July.

---

*Related: [`phase7_blocker_resolution.md`](../phase7_blocker_resolution.md) ·
[`OPEN_QUESTIONS.md`](OPEN_QUESTIONS.md) · [`BOSS_CONFIRMATIONS_DRAFT.md`](BOSS_CONFIRMATIONS_DRAFT.md) ·
[`TODO.md`](TODO.md)*
