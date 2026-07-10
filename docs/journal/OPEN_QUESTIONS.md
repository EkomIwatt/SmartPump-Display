# SmartPump Display — Open Questions

Decisions still owed before V1 ships. Resolved questions are removed (not crossed out — git keeps history).

> **2026-07-03 reconciliation:** many payment/config questions below were answered (or reshaped)
> by the Pump API Reference — see `docs/phase7_blocker_resolution.md`. That doc inverts the payment
> model (pump is now the *initiator*, not a webhook receiver). Items resolved by it are moved to
> **Resolved**; items it merely reshapes are annotated in place. Its own claim that USSD/SMS is
> "obsolete" is **wrong** and corrected there — see the USSD/SMS block below.

---

## Hardware / firmware contract

1. **Pulses per litre.** Currently assumed at `100 pulses/L` (from prior mock). Is this confirmed against the production pulse-tap adapter, or is it per-station configurable? If per-station, where does it come from — operator push, on-device setup screen, both?
2. **Nozzle shutoff timeout.** Spec recommends 3s. Confirm 3s as production default. Should it be operator-pushable per-station, or device-local?
3. **Relay open-on-boot.** Spec invariant says relay must default OPEN. Confirm the Android side actively asserts this on app start (vs. relying on hardware default).
4. **USB serial protocol.** Existing mock assumes `PULSE:NNNN` strings. Confirm production framing (line-delimited? checksum? heartbeat?).
   - *Phase 7a proposal (built + bench-verified 2026-06-11, pending vendor/Olonade ratification):* line-delimited `TYPE:<cum>*<cs>`, `<cs>` = XOR-8 of the bytes before `*`. Device→app `PULSE`/`HB`/`BOOT`/`ERR`; app→device `RLY:1`/`RLY:0`. Cumulative counter (app takes deltas → dropped lines self-heal). The illustrative `PULSE:0042817*7C` in the framing note is wrong — real XOR-8 is `5D`. *7a-hardening (2026-06-30): added app→device `PING*<cs>` (liveness heartbeat, XOR-8 `10`, ≈1/s) and device→app `ERR:WDOG` (comms-loss watchdog trip). Standing requirement: the app sends `PING` while connected; the adapter fails the relay closed if it goes stale mid-dispense.*
22. **Pre-pay permanent mid-dispense disconnect — UI recovery gap.** With the app-side pause/resume removed (#21, 2026-07-08) and only *fill-up* carrying a pulse-gap watchdog, a **permanent** mid-dispense link loss on a **fixed/pre-pay/cash-fixed** flow leaves the app showing `FixedDispensing`/`CashFixedDispensing` **indefinitely** at the last litre count — the collectors no-op `PulseMessage.Disconnected` and there is no fixed-flow timeout. **Fuel is physically OFF** (firmware watchdog on UPS power, or instant power loss on a bus-powered rig), so it is **safe-but-stuck**, not unsafe; it clears only on a power-cycle (boot-resume) or attendant action. This is a deliberate consequence of the **fixed-cable assumption** (a permanent yank shouldn't be reachable in the kiosk; only a power cut, which boot-resume covers). **Open decision:** is "safe-but-stuck" acceptable for V1, or do the fixed-flow collectors need a bounded recovery — e.g. an N-second no-pulse timeout → a "pump interrupted, see attendant" terminal state, or resume-else-refund? Bench-observed 2026-07-10 while confirming the 7a-hardening merge. Relates to #21 (link-loss handling) and #2 (shutoff timeout).

## Payment integration

*(#5 webhook signing and #6 station virtual account are resolved — see Resolved. Numbers left as gaps; they're stable identifiers.)*

7. **Late-payment / QR-expiry reconciliation.** *Pump side resolved* (`phase7_blocker_resolution.md`): the pump is stateless about late arrivals — it only honours transactions it's actively waiting on (PENDING_PAYMENT window) and is idempotent on `transactionId`, so replays are no-ops. **Backend-side policy still open** (their item 6): auto-refund vs. wallet credit vs. manual review for money that lands after the window. Doesn't block pump code; pick before field test.
8. **Price-per-litre push channel.** *Direction resolved* (`phase7_blocker_resolution.md`): hybrid — pump fetches `GET /api/pump/config` on boot and again before every `/authorise` (the correctness guarantee), and accepts pushes for idle-screen freshness. **One input still open** (their item 2): does the ordered tablet have Google Play Services? Yes → FCM; no → persistent WebSocket. Same answer also unlocks the PAID-notification push and future credential rotation.

## USSD / SMS (Flow 5) — DEFERRED to a future update (not dropped)

> **2026-07-03:** `phase7_blocker_resolution.md` claims this cluster is "obsolete" because Paystack
> handles USSD on its checkout page. **That is wrong and is corrected in that doc.** There are two
> different USSD paths: Paystack USSD is an *online* payment method (the pump still needs internet to
> get the `authorizationUrl`), whereas **Flow 5 is the genuinely *offline* mode** — no connectivity at
> the pump at all, customer pays via bank USSD, confirmation arrives as an SMS the pump parses.
> Paystack does **not** replace it. Per the boss, the real offline-USSD flow is **deferred to a future
> update**, so #9–#12 below stay alive but are **not V1-cycle blockers** — revisit when that update is
> scheduled. Flow 5 stays in `flows.md` / `state-machine.md`; sub-phase 7d is deferred, not cut.

9. **GTBank SMS format — locked.** Need at least 2–3 real GTBank confirmation SMS examples to harden the parser before field test. Same for Access and Zenith when those land in V2.
10. **Pump SIM provisioning.** Does each station have its own SIM, or shared across pumps? Affects `BroadcastReceiver` filtering.
11. **USSD ref collisions.** Reference is currently `BALANCEE-NNN`. Three digits will collide. Confirm scheme.
12. **USSD code generation per-station.** Are the `*737*5000*847#` codes generated per-transaction, or per-station static? Spec implies per-transaction.

## Operator config

13. **DeviceConfig schema.** *Reshaped* by `phase7_blocker_resolution.md`: config now splits across `POST /api/pump/activate` (returns `deviceId`/`pumpId`/`apiKey`/`signingSecret` once, at onboarding) and `GET /api/pump/config` (current price per fuel type: `PETROL`/`KEROSENE`/`DIESEL`/`COOKING_GAS`). No virtual account (Paystack owns payments). Still open: is shutoff timeout (OQ #2) operator-pushable via `/config`, or device-local? Depends on the `/config` payload the backend finalises (their item 4).

*(#14 receipt sharing resolved — see Resolved.)*

## UI / UX

15. **Idle screen attract loop.** Should the idle screen show anything beyond the logo + "Tap to pay"? Branding, fuel price, station name?
16. **Custom amount entry.** Pre-pay → Custom — numeric keypad on the customer screen, or routed through attendant? Spec doesn't show a custom-amount screen.
17. **Error recovery copy.** What does "Price not set — contact operator" actually display? Static screen or auto-retry on next push?
18. **Cancellation paths.** Can a customer cancel mid-pre-pay (e.g., after QR shown, before paying)? Or attendant-only?

## Resolved

- **#5 (Webhook signing)** — Resolved 2026-07-03 by the Pump API Reference (`phase7_blocker_resolution.md`), **pending boss ratification that the reference is canonical** (their item 1). The payment model is inverted: there is **no inbound webhook** to the pump. The pump signs its own **outbound** requests — API key (`X-Api-Key: bal_live_…`) + per-device `signingSecret`, headers `X-Api-Key`/`X-Device-Id`/`X-Timestamp`/`X-Signature`, `X-Signature = HMAC-SHA256(signingSecret, timestamp + "." + rawRequestBody)` hex, timestamp ISO-8601 UTC within 5 min of server clock. Do not re-serialize the body after signing.
- **#6 (Station virtual account)** — Resolved 2026-07-03 (`phase7_blocker_resolution.md`). **Obsolete.** Paystack owns the payment surface (pump calls `/authorise`, gets a Paystack `authorizationUrl`, renders it as a QR; customer pays Paystack directly). No NIP virtual account is provisioned to the pump. This also removes the "dynamic NIP transfer QR" mechanic described for Flow 3 — that QR is now a Paystack checkout URL.
- **#14 (Receipt sharing)** — Resolved 2026-07-03 (`phase7_blocker_resolution.md`). Android **system share sheet** — customer taps Share, the OS surfaces WhatsApp / SMS / email / whatever is installed. No bespoke print-to-cashier channel for V1.
- **#21 (Mid-dispense USB link-loss handling)** — Resolved 2026-06-30 (7a-hardening), **pending boss ratification**. Decision: **pause-and-resume with an explicit "pump disconnected" state**, *not* treat-as-shutoff — a fixed-flow customer prepaid for N litres, so billing the partial litres would short them. Three-part fix: (1) firmware **comms-loss heartbeat watchdog** — the Arduino fails the relay closed (on its own GPIO, without knowing litres) if the app's `PING` heartbeat goes stale within `HEARTBEAT_TIMEOUT_MS` (3 s), so fuel can't run on after a USB drop/freeze; (2) app **PING heartbeat + reconnect re-assert** — `UsbSerialConnection` sends a framed `PING` ≈every 1 s while connected; `UsbSerialRelayController` re-commands `RLY:1` on the link's down→up edge (the watchdog never re-energises on its own), so a brief drop self-heals; (3) app **fixed-flow disconnect handling** — the pre-pay/USSD/cash-fixed collectors pause into `TransactionState.PumpDisconnected` on `PulseMessage.Disconnected` and auto-resume toward the prepaid target on the next pulse/heartbeat (live collector keeps its session count across reconnect; boot-resume covers a power cut while paused). Fill-up keeps its 3 s flow-gap shutoff (it bills what flowed by design). The original concern that the activity may relaunch on `USB_DEVICE_ATTACHED` is moot for the demo path (persistent grant, no relaunch observed); revisit only if field logcat shows a relaunch. Still relates to #3 (relay-on-boot) and #7 (relay closing late).
  **Revised 2026-07-08:** part (3) — the app-side `TransactionState.PumpDisconnected` pause/resume UX — was **removed** to simplify the state machine (it was the source of recurring bugs). Operating assumption is now that **the USB cable is fixed in the kiosk** (hardwired, no yank path) and the only real failure is a power cut, guarded by the UPS. Parts (1) and (2) are **kept**: the firmware dead-man watchdog (fails the relay closed if the `PING` heartbeat stops) still covers the *app-freeze-while-fuel-flows* hazard, which a fixed cable does **not** eliminate; and the relay controller's down→up reconnect re-assert means a genuine transient USB glitch still self-heals and resumes counting without a dedicated UI state. The pre-pay/USSD/cash-fixed collectors now no-op `PulseMessage.Disconnected`/`Heartbeat`. Net: comms-loss *safety* is unchanged; only the disconnect *screen/pause* is gone.
- **#20 (Phase 7 scope)** — Resolved 2026-05-28. All six Phase 7 sub-phases (7a hardware, 7b operator config push, 7c digital payments, 7d USSD/SMS, 7e backend sync, 7f onboarding + receipts) are **V1**. None touch the spec's V1-out-of-scope set — ad/attract screen, loyalty/RFID, multi-station, ATG, fleet, multi-nozzle, shift management — which stays V2+. See `docs/journal/PHASE_7_PLAN.md`.
- **Money precision & display** — Resolved 2026-05-27. Sub-naira fuel prices (e.g. ₦870.50/L) are expected, so prices and amounts are carried as **kobo (`Long`) end-to-end** through `TransactionState`, `CustomerViewModel`, and the audit row — no truncation to whole naira. Display uses **full precision (2 dp)** everywhere via `ui/util/formatNaira(kobo)` — e.g. price `₦870.50/L`, fill-up total `₦33,166.05`. **This deviates from the strict-design screens**, which only ever show whole naira (`₦53,147`, `₦870/L`); the screens predate the sub-naira requirement and don't represent a kobo case. Customer-typed entry (pre-pay amount tiles, cash keypad) stays whole-naira at the UI boundary and is multiplied to kobo in the VM. If the boss wants whole-naira amounts to render without a trailing `.00`, that's a `formatNaira` tweak, not a state change.
- **#19 (Roles & PIN in V1)** — Resolved 2026-05-23. V1 ships with a single shared 4-digit PIN gating every attendant action (FILL UP AUTHORISE / AUTHORISE CASH / CASH RECEIVED). No roles. PIN set at install during onboarding, stored as PBKDF2-HMAC-SHA256 hash + per-device salt in SQLite. Cashier-tablet → pump PIN-push channel deferred to Phase 7. Role-based PINs (manager vs attendant) deferred to V2.
