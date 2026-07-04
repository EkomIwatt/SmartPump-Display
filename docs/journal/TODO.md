# SmartPump Display — TODO

Living work board. The **PROJECT_LOG** records what's *done*; this file tracks what's *outstanding*.
Keep it current: check items off, add follow-ups as they surface, move finished work into the log.

**Legend:** `[ ]` open · `[~]` in progress · `[x]` done (then move to PROJECT_LOG) · `[·]` deferred/parked

_Last updated: 2026-07-04_

---

## Now — unblocked, high value

- [x] **1. Commit network-layer foundation + doc reconciliation.** Done 2026-07-04 as two commits
  on `feature/phase-7a-hardening`: `29fe12b` (docs reconciliation + TODO board) and `4af9514`
  (network layer). _(move to PROJECT_LOG at next phase log.)_
- [x] **3. Build `PumpApiClient` / transport client.** Done 2026-07-04 (uncommitted): `ApiResult`/
  `ApiError` typed-error funnel (`safeApiCall`), `retryingApiCall` backoff primitive, `PumpApiClient`
  over all 5 endpoints with retry on the idempotent upload; interceptor now throws typed
  `PumpNotActivatedException`. Tests green (MockWebServer). **DTO↔domain mapping deferred to #8** —
  kept the client transport-only so the still-provisional bits (money unit, `/config` shape) don't
  ripple down. _(move to PROJECT_LOG at next phase log.)_
- [ ] **4. Replace in-memory credentials store with an encrypted-at-rest impl.** `InMemoryPumpCredentialsStore`
  is a placeholder (not persisted, secret unencrypted). Swap for a KeyStore-backed / EncryptedSharedPreferences
  impl of `PumpCredentialsStore` — only the Hilt binding in `NetworkModule` changes. Ties into 7f.
  Note: `androidx.security:security-crypto` is in maintenance — weigh KeyStore-direct first.

## Waiting on external input

- [ ] **2. Uno bench re-run of 7a-hardening, then merge → `main`.** Waiting on the Arduino. Run the
  `hardware/README.md` "Comms-loss heartbeat watchdog" checklist (PING ~1/s; unplug mid-fixed-dispense
  → relay off within ~3 s; replug → `RLY:1` re-assert → resume from where it paused, not zero). A
  classic ESP32 (WROOM/CP2102 or CH340) can substitute with a ported sketch (remap off GPIO6–11,
  `IRAM_ATTR` ISR, `LED_BUILTIN`=GPIO2, 3.3 V). Merging closes the last gate on OQ #21.
- [ ] **6. Chase the 7 boss confirmations** (from `phase7_blocker_resolution.md`): (1) reference is
  canonical — gates everything; (2) tablet has Google Play Services? → FCM vs WebSocket; (3) GET
  `/transactions/{id}` exists; (4) GET `/config` exists + final payload/units; (5) confirm offline-USSD
  7d deferral (not the Paystack-USSD conflation); (6) late-payment policy; (7) hosted staging URL +
  test activation code. _Claude can draft this as a message to send._

## Gated / later

- [ ] **5. Debug `network-security-config` for cleartext localhost.** Runtime prereq before any live
  call to `http://10.0.2.2:8080` (cleartext blocked by default on API 9+). Debug-only, via a debug
  manifest. Not needed to compile or unit-test.
- [ ] **7. Phase 8 — `CustomerViewModel` unit tests (disconnect path first).** Awaiting go. Stand up
  fakes+Turbine; cover 7a-hardening pause/resume/boot-resume first. Open decisions: Log flag vs Logger
  interface; DAO tests in/out. This is where the app-side watchdog safety claim gets real coverage.
- [ ] **8. Payment feature flows** — activate → persist creds; authorise → Paystack QR; PAID via
  push + 10 s poll; price config fetcher; WorkManager upload job (re-add `workmanager`).
  **Blocked by #3, #4, #6.** Sandbox-testable; live money gated behind the 14-day parallel run.

## Deferred (parked, not dropped)

- [·] **9. Offline USSD (Flow 5 / sub-phase 7d).** Boss-deferred to a future update. The genuinely
  *offline* path (bank USSD + parsed SMS), distinct from Paystack's *online* USSD. Kept in
  `flows.md`/`state-machine.md`. Revisit with OQ #9–#12 (real bank SMS samples, SIM provisioning,
  ref-collision scheme, per-station code generation).
