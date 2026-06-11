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

#### Build plan (agreed 2026-06-11 — demo driver)

Context: boss wants a live demo tomorrow showing **real Arduino dispensing**. Hardware arrives
tomorrow morning for a bench test; we build tonight on a branch so `main` stays the known-good
mock demo. Brief: "build something that actually works for now, change later if need be."

- **Branch:** `feature/phase-7a-hardware` off `main`. Build the `debugRealHw` APK from the branch
  for the bench test. Pass → merge; hardware fights us → `main` is pristine and we demo mock.
- **Swap is a true drop-in** — verified against the code: `PulseSource.observe(): Flow<PulseMessage>`
  and `RelayController` (`isDispensing` / `startFuelFlow` / `stopFuelFlow`) are clean interfaces;
  `PulseMessage` already has `Pulse(count)` (cumulative), `Heartbeat`, `ParseError(raw)`,
  `Disconnected`. No model / ViewModel / screen changes.
- **Build types:** add `debugRealHw` (`initWith debug`, `applicationIdSuffix ".realhw"`,
  `versionNameSuffix "-realhw"`, `MOCK_HARDWARE=false`). Two co-installable apps ("SmartPump" mock
  + "SmartPump realhw") = the side-by-side demo fallback. `debug` stays `MOCK_HARDWARE=true`.
- **Serial framing** (proposed to Olonade; sketch + parser kept self-consistent):
  - device→app (adopted from boss proposal): `PULSE:<cum>*<cs>\n`, `HB:<cum>*<cs>\n`,
    `BOOT:<cum>*<cs>\n` (resets baseline), `ERR:<code>*<cs>\n`.
  - app→device (added — relay control): `RLY:1*<cs>\n` (fuel on) / `RLY:0*<cs>\n` (fuel off).
  - checksum = **XOR-8 of the ASCII bytes before `*`**, two-hex. (Boss's `7C` example is
    illustrative — real XOR of `PULSE:0042817` is `0x5D`; sketch and parser agree on the algorithm.)
  - cumulative counter is the robustness win: parser tracks last count, emits the **delta**, so a
    dropped line self-heals on the next pulse. Parser handles BOOT-reset and counter rollover.
- **Files (all in `main` source set; chosen by DI):**
  1. `SerialFrameParser` — pure Kotlin, no Android deps → **unit-tested** (checksum valid/invalid,
     delta, BOOT reset, rollover, malformed). The must-be-right core.
  2. `UsbSerialPulseSource : PulseSource` + `UsbSerialRelayController : RelayController`, sharing one
     `@Singleton` USB connection (one port: source reads, relay writes). Emits `Disconnected` on
     detach/IO error.
  3. `HardwareModule` → `@Provides` branching on `BuildConfig.MOCK_HARDWARE`.
  4. Manifest USB-host feature + `res/xml/usb_device_filter.xml` (Arduino Uno VID `0x2341`).
  5. **Arduino sketch** under `hardware/` speaking the framing — HB every 2s, PULSE from a
     button/interrupt pin (or auto-rate to demo without a real meter), BOOT on reset, drives a
     relay/LED pin from `RLY` commands. Flash tomorrow AM and the Uno talks immediately.
- **Deferred (change-later):** wiring the debug-screen sliders to the real source; reconnect/backoff
  hardening; relay ACK (optimistic `isDispensing` for now).

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
