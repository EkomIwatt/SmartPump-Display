# Phase 7 — Production wiring (plan)

Phases 1–6 built and polished the whole app on a **mock stack**. Phase 7 swaps each mock
for a real integration. The seam is clean: every real impl drops in behind an interface that
already exists (`PulseSource`, `RelayController`, `PaymentProcessor`, the repositories), so a
sub-phase replaces **one** mock and leaves the rest — and the build — green.

This is a plan, not a log. Completed sub-phases get logged in `PROJECT_LOG.md` as usual.

## Scope (resolves OPEN_QUESTIONS #20)

**All six sub-phases below are V1.** Confirmed 2026-05-28. None of them touch the spec's
V1-out-of-scope list, which is a *separate* feature set:

> ad/attract screen, loyalty / RFID, multi-station, ATG (automatic tank gauging), fleet,
> multi-nozzle, shift management — all V2+.

Spec-marked V1-required behaviours these sub-phases must preserve: Flow 1 (fixed pre-pay
digital), fill-up nozzle shutoff, dynamic NIP QR, GTBank USSD/SMS.

## Sub-phases

Each is independently testable, leaves the build green, and is committed/logged as its own
stage (per the house rules — do not roll several into one).

### 7a — Hardware: USB-serial pulse driver + relay GPIO
- **Swaps:** `MockPulseSource` → real USB-serial driver; `MockRelayController` → real relay GPIO.
- **Seam:** `PulseSource` / `RelayController` bindings in `HardwareModule`.
- **Also:** wire the currently-dead `BuildConfig.MOCK_HARDWARE` flag so the binding picks mock
  vs real; keep the relay-open-on-boot invariant.
- **Blocked on:** real Arduino rig + confirmed USB framing (OQ #1–4).
- **Done when:** a bench rig dispenses and the litre count tracks real pulses at the agreed
  pulses/L.

### 7b — Operator config push
- **Swaps:** the seeded default `DeviceConfig` → live config pushed from the operator app.
- **Seam:** `DeviceConfigRepository`.
- **Blocked on:** push-channel decision — FCM / polled HTTP / BLE (OQ #8).
- **Done when:** an operator price change reaches the pump and the price guard picks it up live.
- *Most self-contained — a good first mover.*

### 7c — Digital payments (Flow 1 + Flow 3 digital)
- **Swaps:** `MockPaymentProcessor` → Balanceè backend/SDK + webhook for QR / app pre-pay and
  the post-fill-up dynamic QR.
- **Seam:** `PaymentProcessor`.
- **Also:** real backend transaction correlation (cash flows currently only get a local
  `BLC-NNNNN` ref); webhook-trail reconciliation.
- **Blocked on:** backend API, webhook signing, expiry reconciliation (OQ #5–7).

### 7d — USSD / SMS (Flow 5)
- **Swaps:** the mock USSD path → SIM-side `BroadcastReceiver` + GTBank SMS parser, behind the
  same `PaymentProcessor` (USSD channel).
- **Also:** **re-adds the `RECEIVE_SMS` permission** removed during cleanup (with the
  `uses-feature telephony required=false` tag lint wanted) — and only that, scoped to this need.
- **Blocked on:** real GTBank confirmation-SMS samples + SIM provisioning model (OQ #9–12).

### 7e — Backend sync (audit log upload)
- **Swaps:** nothing visible — adds a WorkManager job that uploads completed transactions and
  retries the un-synced backlog.
- **Seam:** `TransactionRepository.getPendingSync()` (already exists).
- **Also:** **re-adds the `workmanager` dependency** removed during cleanup.
- **Blocked on:** backend ingest endpoint.
- *Self-contained — can run in parallel with anything.*

### 7f — Onboarding hardening + receipts
- **Swaps:** install-time backend **station-ID validation**; cashier-tablet → pump **PIN-push**
  channel; real **receipt sharing** (`onShareReceipt` is a placeholder today).
- **Seam:** onboarding flow + `CustomerViewModel.onShareReceipt`.
- **Blocked on:** OQ #13–14 + the PIN-push channel decision.

## Sequencing

- **External blockers drive order, not code dependencies.** The sub-phases are loosely coupled
  (separate interfaces), so order them by *what's available when*: Arduino rig (7a), backend
  API (7c/7e), SMS samples (7d). Start with the unblocked, self-contained ones — **7b** and
  **7e** are the natural first movers.
- Decide and start each as its own stage; wait for an explicit "go" before beginning.

## Cross-cutting

- **Keep the mocks.** Gate real vs mock via `BuildConfig.MOCK_HARDWARE` / a build flavor so
  debug + demo keep working and each real impl can be A/B'd. DI makes this a one-line swap.
- **Build green per sub-phase**; each real impl ships behind its interface with the mock intact.
- **Pre-ship hardening (not one of the six):** enable R8 / `shrinkResources` for release with
  the right keep rules (the last deferred infra item from the cleanup phase), and the first
  Room migration (`MIGRATION_2_3`) the moment an entity changes — workflow in
  `SmartPumpMigrations.kt`.
