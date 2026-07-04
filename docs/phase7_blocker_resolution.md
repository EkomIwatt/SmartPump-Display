# SmartPump Phase 7 — Blocker Resolution Context

This document captures every blocker question that was open at the start of Phase 7, where it
stands now, and what's still pending boss confirmation. Use this as the source of truth when
deciding what to build against vs. what to defer.

---

## Architectural shift (read first)

The original prototype spec described the pump as a **webhook receiver** — Balancee's backend
would call `POST /pump/authorise` *into* the pump.

The Pump API Reference (received later) inverted this: the **pump is now the initiator**. It
calls the backend outbound, gets a Paystack checkout URL back, and renders it as a QR. Customer
pays Paystack directly; Paystack webhooks the backend; backend marks the transaction PAID.

This means:
- No inbound webhook listener on the pump.
- No virtual account provisioning needed (Paystack owns the payment surface).
- USSD/SMS path is likely obsolete — Paystack handles USSD on its checkout page.
- A new gap appears: **how does the pump learn the transaction became PAID?** (resolved
  architecturally below, pending backend endpoint.)

---

## Resolved by Pump API Reference doc

### Authentication
- **Scheme:** API key (`X-Api-Key: bal_live_xxx`) + per-device `signingSecret` for HMAC-SHA256.
- **Required headers on every signed request:** `X-Api-Key`, `X-Device-Id`, `X-Timestamp`,
  `X-Signature`.
- **Timestamp format:** ISO-8601 UTC (`yyyy-MM-dd'T'HH:mm:ss'Z'`), must be within 5 minutes
  of server clock.
- **Signature formula:** `HMAC-SHA256(signingSecret, timestamp + "." + rawRequestBody)`,
  hex-encoded. Do not re-serialize the body after signing.

### Onboarding / activation
- **Endpoint:** `POST /api/pump/activate` (public, no auth).
- **Request:** `{ activationCode, deviceId }`.
- **Response:** `{ deviceId, pumpId, apiKey, signingSecret }`.
- **Critical:** `apiKey` and `signingSecret` are emitted exactly once. Cache in secure storage
  immediately. If lost, station must revoke and reissue.
- One-time activation code per pump, redeemed once on first boot.

### Starting a sale
- **Endpoint:** `POST /api/pump/authorise` (signed).
- **Request:** `{ pumpId, transactionId, amount, expectedLitres, fuelType }`.
  - `transactionId` is locally generated, acts as idempotency key.
  - `fuelType`: `PETROL`, `KEROSENE`, `DIESEL`, or `COOKING_GAS`.
  - Server validates `amount === expectedLitres × stationPricePerUnit`.
- **Response:** `{ status: "PENDING_PAYMENT", transactionId, paymentReference,
  authorizationUrl, expiresAt }`.
- Render `authorizationUrl` as the QR code on screen.

### Uploading dispense log
- **Endpoint:** `POST /api/pump/transactions/upload` (signed).
- **Request:** `{ pumpId, transactionId, paymentReference, actualLitresDispensed, startedAt,
  completedAt }`.
- **Idempotent on `transactionId`.** Safe to retry from the offline queue when connectivity
  returns.
- **Response:** `{ status: "DISPENSED", transactionId, paymentReference }`.

---

## Resolved by architectural discussion

### How does the pump learn a transaction became PAID?
**Decision:** Hybrid push + poll.
- **Push (primary):** backend pushes a `transaction_paid` message on the same channel used
  for price updates (FCM or WebSocket — see Play Services check below) immediately after
  Paystack webhooks the backend.
- **Poll (fallback):** pump polls `GET /api/pump/transactions/{id}` every 10s during the
  PENDING_PAYMENT window. Catches any push that misses.
- **Requires:** backend to add the GET endpoint and wire push on Paystack webhook receipt.
- **Pending boss confirmation** (item 3 in v3 docx).

### How does the pump get current fuel prices?
**Decision:** Hybrid fetch + push.
- **Endpoint:** new `GET /api/pump/config` returning current price per fuel type.
- **Pump behaviour:** fetches on boot, fetches again before every `/authorise` call (this is
  the correctness guarantee — cannot sell at stale price because the server-side
  `amount === expectedLitres × stationPricePerUnit` check would reject it), accepts push
  updates whenever they arrive for live idle-screen freshness.
- **Requires:** backend to add the GET endpoint.
- **Pending boss confirmation** (item 4 in v3 docx).

### How does the activation code reach the pump physically?
**Decision:** Olayemi types the activation code on the pump touchscreen during install.
- No QR scan, no USB stick, no BLE for V1.
- QR scan is a fast-follow if commissioning gets painful at scale.
- Pure pump-side work; no backend dependency.
- **Pending boss confirmation** (item 5 in v3 docx).

### Late-payment / QR-expiry reconciliation
**Decision:** Pump is stateless about late arrivals.
- Pump only honours transactions it's actively waiting on (PENDING_PAYMENT window).
- Idempotent on `transactionId` — replays are no-ops.
- Late money is the backend's problem.
- **Backend-side policy still pending** (item 7 in v3 docx): auto-refund vs. wallet credit
  vs. manual review queue. Doesn't block pump code; needs picking before field test.

### PIN / credential architecture
**Decision:** Backend generates and owns credentials.
- Pump never originates a PIN. Backend issues `apiKey` + `signingSecret` via
  `POST /api/pump/activate`.
- Future rotation: backend pushes new credentials over the same channel as price updates.
- Cashier tablet never touches credentials — its job is just to trigger station-claim API
  calls from the operator dashboard.

### USSD / SMS parser (sub-phase 7d)
**CORRECTION (2026-07-03) — this is NOT obsolete. Deferred to a future update, not dropped.**
The "likely obsolete" reading below conflated two different USSD paths:
- **Paystack USSD** — lives on Paystack's checkout page. The pump still needs internet to call
  `/authorise` and get the `authorizationUrl` at all, so this is just an *online* payment method
  inside the new QR flow.
- **Flow 5 USSD/SMS** — the genuinely **offline** mode: no connectivity at the pump, customer pays
  via bank USSD, confirmation lands as an SMS the pump parses. Works with zero internet on either
  device. Paystack does **not** replace this — it's a real differentiator for no-signal stations.

Per the boss, the offline-USSD flow is **deferred to a future update**. Flow 5 stays in
`flows.md` / `state-machine.md`; sub-phase 7d and OQ #9–#12 are deferred (kept alive), not cut.

~~**Likely obsolete.** Paystack handles USSD end-to-end on its checkout page. Pump never receives an
SMS, never needs a SIM, never parses bank-specific message formats. Pending boss confirmation
(item 8 in v3 docx) before the sub-phase is formally dropped.~~ *(superseded by the correction above)*

### Receipt sharing
**Decision:** Android system share sheet. User taps share, OS surfaces WhatsApp, SMS, email,
and whatever else is installed.

### Push channel selection
**Decision pending one input:** Does the ordered tablet have Google Play Services?
- **If yes:** FCM. Standard, well-documented, Google handles reconnection and queuing.
- **If no:** persistent WebSocket from pump to backend, with reconnection logic on the pump.
- This single answer unlocks three features at once (price push, PAID notification, future
  credential rotation).
- **Pending boss confirmation** (item 6 in v3 docx).

---

## Pending boss confirmation

These items shouldn't be coded against until the boss confirms or pushes back. Sequenced by
blocking priority:

1. **Confirm Pump API Reference is canonical** — affects everything. Build assumption.
2. **Tablet has Google Play Services?** — picks FCM vs WebSocket for the push channel.
3. **GET /api/pump/transactions/{id} endpoint** — needed for PAID-detection fallback poll.
4. **GET /api/pump/config endpoint** — needed for price fetching.
5. **USSD/SMS sub-phase 7d drop confirmation** — currently planned work that should be cut.
6. **Late-payment policy** — backend-side, doesn't block pump code, but needs deciding
   before field test.
7. **Hosted staging + test activation code** — ~~only `localhost:8080` is documented~~. **URLs received
   2026-07-04:** prod `https://api.balancee.app/`, dev `https://api.dev.balancee.app/` (wired into
   `BuildConfig.PUMP_API_BASE_URL` — release→prod, debug/debugRealHw→dev). **Still need** a test
   activation code to redeem against dev.

---

## What this means for the codebase

### Stays the same
- `PulseSource` / `RelayController` / `PaymentProcessor` / repository interfaces — the
  Phase 1–6 mock-first architecture is intact.
- `BuildConfig.MOCK_HARDWARE` flag for swapping mocks vs real.
- Room/SQLite offline transaction queue (`PENDING_SYNC` rows replay into
  `/api/pump/transactions/upload`).
- WorkManager job for audit log upload (sub-phase 7e).

### Needs building
- **Network client layer:** Retrofit/OkHttp with an interceptor that injects all four signed
  headers and computes the HMAC. Single source of truth for the signing scheme.
- **Activation flow:** one-shot screen at first boot, text input for activation code, POST
  to `/api/pump/activate`, secure storage of returned credentials.
- **Authorise flow:** call `/api/pump/authorise`, render `authorizationUrl` as QR.
- **PAID detection:** push listener (FCM/WebSocket) + 10s poll on `GET /api/pump/
  transactions/{id}` during PENDING_PAYMENT.
- **Price config fetcher:** boot fetch + pre-authorise fetch + push listener for
  `GET /api/pump/config`.
- **Upload job:** WorkManager job posting to `/api/pump/transactions/upload`, idempotent
  retry from `getPendingSync()`.

### Gets dropped (pending boss confirmation)
- USSD/SMS BroadcastReceiver, GTBank/Access/Zenith SMS parser strategies, SIM provisioning
  logic. Phase 7d as currently planned likely goes away entirely.

### Hardware (sub-phase 7a) — OUT OF DATE below; 7a is built + merged
> **CORRECTION (2026-07-03):** the line below is stale. The real USB-serial pulse driver + relay
> (sub-phase 7a) is **built, bench-verified on an Arduino Uno, and merged to `main`**. The
> 7a-hardening branch (comms-loss watchdog + PING heartbeat + fixed-flow pause/resume) is built and
> awaits one Uno bench re-run before merge. `MockPulseSource`/`MockRelayController` remain for the
> default `debug` variant; `debugRealHw` drives real hardware. This doc is purely a network/payment
> reconciliation and has **no** firmware implications — the hardware-contract questions (OQ #1/#2/#4)
> are untouched by it.

~~**Stays mocked for now.** Real USB-serial pulse driver and relay GPIO (sub-phase 7a) — waits on
Olonade's hardware rig. `MockPulseSource` and `MockRelayController` continue to drive dev work.~~
*(superseded — see correction above)*

---

## Reference: V1 success metrics (gate live payments)

Live customer money does not flow until the **14-day parallel run** shows <1% daily variance
between the system's dispensed-litres count and station stock records, every single day. Even
once Balancee integration is wired, the field-test pilot stays on parallel-run mode until
this gate passes.

This means **all real-money sub-phases can be built and end-to-end tested against a sandbox
without rushing**. Build correctness first, observe for two weeks, then go live.
