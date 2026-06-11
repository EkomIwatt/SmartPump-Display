# SmartPump Display — Open Questions

Decisions still owed before V1 ships. Resolved questions are removed (not crossed out — git keeps history).

---

## Hardware / firmware contract

1. **Pulses per litre.** Currently assumed at `100 pulses/L` (from prior mock). Is this confirmed against the production pulse-tap adapter, or is it per-station configurable? If per-station, where does it come from — operator push, on-device setup screen, both?
2. **Nozzle shutoff timeout.** Spec recommends 3s. Confirm 3s as production default. Should it be operator-pushable per-station, or device-local?
3. **Relay open-on-boot.** Spec invariant says relay must default OPEN. Confirm the Android side actively asserts this on app start (vs. relying on hardware default).
4. **USB serial protocol.** Existing mock assumes `PULSE:NNNN` strings. Confirm production framing (line-delimited? checksum? heartbeat?).
   - *Phase 7a proposal (built + bench-verified 2026-06-11, pending vendor/Olonade ratification):* line-delimited `TYPE:<cum>*<cs>`, `<cs>` = XOR-8 of the bytes before `*`. Device→app `PULSE`/`HB`/`BOOT`/`ERR`; app→device `RLY:1`/`RLY:0`. Cumulative counter (app takes deltas → dropped lines self-heal). The illustrative `PULSE:0042817*7C` in the framing note is wrong — real XOR-8 is `5D`.
21. **Mid-dispense USB link-loss handling (NEW, Phase 7a — bench finding 2026-06-11).** A cable yank during a **fixed** dispense (pre-pay / cash-fixed / USSD) freezes `FixedDispensing` — those flows have no flow-gap watchdog, unlike fill-up. Reconnect resumes but stutters (Uno reboots to relay-OFF while the app still believes `isDispensing`, no `RLY:1` re-assert; the `USB_DEVICE_ATTACHED` filter may relaunch the activity; `PulseMessage.Disconnected` is ignored in the fixed collector). What's the required behaviour on mid-dispense link loss — pause-and-resume, an explicit "pump disconnected" state, or treat as shutoff? Needs a hardening pass (logcat-driven). Relates to #3 (relay-on-boot) and #7 (relay closing late).

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

- **#20 (Phase 7 scope)** — Resolved 2026-05-28. All six Phase 7 sub-phases (7a hardware, 7b operator config push, 7c digital payments, 7d USSD/SMS, 7e backend sync, 7f onboarding + receipts) are **V1**. None touch the spec's V1-out-of-scope set — ad/attract screen, loyalty/RFID, multi-station, ATG, fleet, multi-nozzle, shift management — which stays V2+. See `docs/specs/PHASE_7_PLAN.md`.
- **Money precision & display** — Resolved 2026-05-27. Sub-naira fuel prices (e.g. ₦870.50/L) are expected, so prices and amounts are carried as **kobo (`Long`) end-to-end** through `TransactionState`, `CustomerViewModel`, and the audit row — no truncation to whole naira. Display uses **full precision (2 dp)** everywhere via `ui/util/formatNaira(kobo)` — e.g. price `₦870.50/L`, fill-up total `₦33,166.05`. **This deviates from the strict-design screens**, which only ever show whole naira (`₦53,147`, `₦870/L`); the screens predate the sub-naira requirement and don't represent a kobo case. Customer-typed entry (pre-pay amount tiles, cash keypad) stays whole-naira at the UI boundary and is multiplied to kobo in the VM. If the boss wants whole-naira amounts to render without a trailing `.00`, that's a `formatNaira` tweak, not a state change.
- **#19 (Roles & PIN in V1)** — Resolved 2026-05-23. V1 ships with a single shared 4-digit PIN gating every attendant action (FILL UP AUTHORISE / AUTHORISE CASH / CASH RECEIVED). No roles. PIN set at install during onboarding, stored as PBKDF2-HMAC-SHA256 hash + per-device salt in SQLite. Cashier-tablet → pump PIN-push channel deferred to Phase 7. Role-based PINs (manager vs attendant) deferred to V2.
