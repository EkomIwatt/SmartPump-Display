# SmartPump Display — Open Questions

Decisions still owed before V1 ships. Resolved questions are removed (not crossed out — git keeps history).

---

## Hardware / firmware contract

1. **Pulses per litre.** Currently assumed at `100 pulses/L` (from prior mock). Is this confirmed against the production pulse-tap adapter, or is it per-station configurable? If per-station, where does it come from — operator push, on-device setup screen, both?
2. **Nozzle shutoff timeout.** Spec recommends 3s. Confirm 3s as production default. Should it be operator-pushable per-station, or device-local?
3. **Relay open-on-boot.** Spec invariant says relay must default OPEN. Confirm the Android side actively asserts this on app start (vs. relying on hardware default).
4. **USB serial protocol.** Existing mock assumes `PULSE:NNNN` strings. Confirm production framing (line-delimited? checksum? heartbeat?).
   - *Phase 7a proposal (built + bench-verified 2026-06-11, pending vendor/Olonade ratification):* line-delimited `TYPE:<cum>*<cs>`, `<cs>` = XOR-8 of the bytes before `*`. Device→app `PULSE`/`HB`/`BOOT`/`ERR`; app→device `RLY:1`/`RLY:0`. Cumulative counter (app takes deltas → dropped lines self-heal). The illustrative `PULSE:0042817*7C` in the framing note is wrong — real XOR-8 is `5D`. *7a-hardening (2026-06-30): added `ERR:WDOG` (relay dead-man watchdog trip) and a standing requirement that the app re-assert `RLY:1` ≈every 700ms while dispensing.*

## Payment integration

5. **Webhook signing.** Is the `POST /pump/authorise` webhook signed (HMAC, mTLS)? Required for V1?
6. **Station virtual account.** Where does the Android unit obtain its NIP virtual account number on setup? Pushed by operator app or provisioned during install?
7. **Webhook expiry.** Spec says 5-min QR expiry → cancel txn. Confirm whether backend also expires its side, and how to reconcile (does the relay ever close late after a customer walked away?).
8. **Price-per-litre push protocol.** Spec says "pushed from operator app." What channel — FCM, polled HTTP, BLE from the cashier tablet? V1 default?

## USSD / SMS (Flow 5)

9. **GTBank SMS format — locked.** Need at least 2–3 real GTBank confirmation SMS examples to harden the parser before field test. Same for Access and Zenith when those land in V2.
10. **Pump SIM provisioning.** Does each station have its own SIM, or shared across pumps? Affects `BroadcastReceiver` filtering.
11. **USSD ref collisions.** Reference is currently `BALANCEE-NNN`. Three digits will collide. Confirm scheme.
12. **USSD code generation per-station.** Are the `*737*5000*847#` codes generated per-transaction, or per-station static? Spec implies per-transaction.

## Operator config

13. **DeviceConfig schema.** What's the minimum set of operator-pushable config fields? Current model has price, station name, nozzle ID. Anything else for V1 (e.g., shutoff timeout, virtual account, attendant IDs eventually)?
14. **Receipt sharing.** Flow 1 "Share receipt" — share via what? SMS, system share sheet, print to cashier tablet?

## UI / UX

15. **Idle screen attract loop.** Should the idle screen show anything beyond the logo + "Tap to pay"? Branding, fuel price, station name?
16. **Custom amount entry.** Pre-pay → Custom — numeric keypad on the customer screen, or routed through attendant? Spec doesn't show a custom-amount screen.
17. **Error recovery copy.** What does "Price not set — contact operator" actually display? Static screen or auto-retry on next push?
18. **Cancellation paths.** Can a customer cancel mid-pre-pay (e.g., after QR shown, before paying)? Or attendant-only?

## Resolved

- **#21 (Mid-dispense USB link-loss handling)** — Resolved 2026-06-30 (7a-hardening), **pending boss ratification**. Decision: **pause-and-resume with an explicit "pump disconnected" state**, *not* treat-as-shutoff — a fixed-flow customer prepaid for N litres, so billing the partial litres would short them. Three-part fix: (1) firmware **relay dead-man watchdog** — the Arduino fails the relay closed if it stops hearing `RLY:1` within `RELAY_DEADMAN_MS` (2 s), so fuel can't run on after a yank/freeze; (2) app **relay keepalive** — `UsbSerialRelayController` re-asserts `RLY:1` ≈every 700 ms while dispensing, which feeds the watchdog and **auto-re-energises the relay on reconnect**; (3) app **fixed-flow disconnect handling** — the pre-pay/USSD/cash-fixed collectors pause into `TransactionState.PumpDisconnected` on `PulseMessage.Disconnected` and auto-resume toward the prepaid target on the next pulse/heartbeat (live collector keeps its session count across reconnect; boot-resume covers a power cut while paused). Fill-up keeps its 3 s flow-gap shutoff (it bills what flowed by design). The original concern that the activity may relaunch on `USB_DEVICE_ATTACHED` is moot for the demo path (persistent grant, no relaunch observed); revisit only if field logcat shows a relaunch. Still relates to #3 (relay-on-boot) and #7 (relay closing late).
- **#20 (Phase 7 scope)** — Resolved 2026-05-28. All six Phase 7 sub-phases (7a hardware, 7b operator config push, 7c digital payments, 7d USSD/SMS, 7e backend sync, 7f onboarding + receipts) are **V1**. None touch the spec's V1-out-of-scope set — ad/attract screen, loyalty/RFID, multi-station, ATG, fleet, multi-nozzle, shift management — which stays V2+. See `docs/specs/PHASE_7_PLAN.md`.
- **Money precision & display** — Resolved 2026-05-27. Sub-naira fuel prices (e.g. ₦870.50/L) are expected, so prices and amounts are carried as **kobo (`Long`) end-to-end** through `TransactionState`, `CustomerViewModel`, and the audit row — no truncation to whole naira. Display uses **full precision (2 dp)** everywhere via `ui/util/formatNaira(kobo)` — e.g. price `₦870.50/L`, fill-up total `₦33,166.05`. **This deviates from the strict-design screens**, which only ever show whole naira (`₦53,147`, `₦870/L`); the screens predate the sub-naira requirement and don't represent a kobo case. Customer-typed entry (pre-pay amount tiles, cash keypad) stays whole-naira at the UI boundary and is multiplied to kobo in the VM. If the boss wants whole-naira amounts to render without a trailing `.00`, that's a `formatNaira` tweak, not a state change.
- **#19 (Roles & PIN in V1)** — Resolved 2026-05-23. V1 ships with a single shared 4-digit PIN gating every attendant action (FILL UP AUTHORISE / AUTHORISE CASH / CASH RECEIVED). No roles. PIN set at install during onboarding, stored as PBKDF2-HMAC-SHA256 hash + per-device salt in SQLite. Cashier-tablet → pump PIN-push channel deferred to Phase 7. Role-based PINs (manager vs attendant) deferred to V2.
