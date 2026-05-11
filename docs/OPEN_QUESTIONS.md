# SmartPump Display — Open Questions

Decisions still owed before V1 ships. Resolved questions are removed (not crossed out — git keeps history).

---

## Hardware / firmware contract

1. **Pulses per litre.** Currently assumed at `100 pulses/L` (from prior mock). Is this confirmed against the production pulse-tap adapter, or is it per-station configurable? If per-station, where does it come from — operator push, on-device setup screen, both?
2. **Nozzle shutoff timeout.** Spec recommends 3s. Confirm 3s as production default. Should it be operator-pushable per-station, or device-local?
3. **Relay open-on-boot.** Spec invariant says relay must default OPEN. Confirm the Android side actively asserts this on app start (vs. relying on hardware default).
4. **USB serial protocol.** Existing mock assumes `PULSE:NNNN` strings. Confirm production framing (line-delimited? checksum? heartbeat?).

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

## V1 scope confirmations

19. **Roles in V1.** Spec says no roles in V1; any attendant can do any attendant action. Confirm — no PIN, no ID badge?
20. **Phase 6+ scope.** USB serial driver, real payment SDK, WorkManager sync, SMS listener — which of these is "must ship V1" vs "deferrable to V2"? Spec marks Flow 1, fill-up shutoff, dynamic QR, GTBank SMS as V1-required.
