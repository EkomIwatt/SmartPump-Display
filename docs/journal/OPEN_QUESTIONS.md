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

    > **Partly settled 2026-09-02 by Prototype Specification v1.0 (Hardware → pulse-tap adapter
    > board).** The "operator push / on-device setup screen" options above are **ruled out**: SON
    > governs accuracy under **NIS 348**, and the spec requires the adapter to be read-only with the
    > pulse-per-litre constant **loaded at commissioning and sealed post-calibration**. A K-factor a
    > station manager can edit behind a PIN is a metrology tamper surface, so operator config holds
    > **fuel type and price only** — it must never hold the K-factor. Agreed split: **the board holds
    > the sealed constant and reports it; the app treats it as read-only and displays it.**
    > **The number itself is still open** — no value yet, and it must not be guessed. Derivation is
    > fixed by execution-tracker task **T-01**: five runs of exactly 10 L into a calibrated measuring
    > jug, recording run number / actual / screen reading / variance, to **±0.5% tolerance**. Olonade
    > owns identifying the meter from the dispenser service manual and tapping the pulse wire. Until
    > then the app carries one named placeholder — `domain/hardware/MeterCalibration.kt`
    > (`PULSES_PER_LITRE = 100`, collapsed from two copies 2026-09-02, commit `5866037`) — which is
    > bench scaffolding expected to be deleted, not a default. See new #23 for the protocol change
    > this implies.
2. **Nozzle shutoff timeout.** Spec recommends 3s. Confirm 3s as production default. Should it be operator-pushable per-station, or device-local?
3. **Relay open-on-boot.** Spec invariant says relay must default OPEN. Confirm the Android side actively asserts this on app start (vs. relying on hardware default).
4. **USB serial protocol.** Existing mock assumes `PULSE:NNNN` strings. Confirm production framing (line-delimited? checksum? heartbeat?).
   - *Phase 7a proposal (built + bench-verified 2026-06-11, pending vendor/Olonade ratification):* line-delimited `TYPE:<cum>*<cs>`, `<cs>` = XOR-8 of the bytes before `*`. Device→app `PULSE`/`HB`/`BOOT`/`ERR`; app→device `RLY:1`/`RLY:0`. Cumulative counter (app takes deltas → dropped lines self-heal). The illustrative `PULSE:0042817*7C` in the framing note is wrong — real XOR-8 is `5D`. *7a-hardening (2026-06-30): added app→device `PING*<cs>` (liveness heartbeat, XOR-8 `10`, ≈1/s) and device→app `ERR:WDOG` (comms-loss watchdog trip). Standing requirement: the app sends `PING` while connected; the adapter fails the relay closed if it goes stale mid-dispense.*
22. **Pre-pay permanent mid-dispense disconnect — UI recovery gap.** With the app-side pause/resume removed (#21, 2026-07-08) and only *fill-up* carrying a pulse-gap watchdog, a **permanent** mid-dispense link loss on a **fixed/pre-pay/cash-fixed** flow leaves the app showing `FixedDispensing`/`CashFixedDispensing` **indefinitely** at the last litre count — the collectors no-op `PulseMessage.Disconnected` and there is no fixed-flow timeout. **Fuel is physically OFF** (firmware watchdog on UPS power, or instant power loss on a bus-powered rig), so it is **safe-but-stuck**, not unsafe; it clears only on a power-cycle (boot-resume) or attendant action. This is a deliberate consequence of the **fixed-cable assumption** (a permanent yank shouldn't be reachable in the kiosk; only a power cut, which boot-resume covers). **Open decision:** is "safe-but-stuck" acceptable for V1, or do the fixed-flow collectors need a bounded recovery — e.g. an N-second no-pulse timeout → a "pump interrupted, see attendant" terminal state, or resume-else-refund? Bench-observed 2026-07-10 while confirming the 7a-hardening merge. Relates to #21 (link-loss handling) and #2 (shutoff timeout).

23. **How does the sealed K-factor reach the app?** OQ #1 settles that the board owns the constant and the app reads it — but not the wire format, and the current framing cannot carry it. `SerialFrameParser` requires `TYPE:<single numeric payload>` and `BOOT` must parse as one Long (`SerialFrameParser.kt:44`), so a two-field `BOOT:<cum>:<ppl>` breaks the parser and its 19 tests. **Proposal: a separate `CAL:<ppl>*<cs>` frame** — keeps `BOOT` byte-compatible with the firmware already bench-verified in 7a, and lets the board re-report calibration on request without faking a reboot. Needs Olonade's agreement *before* the adapter firmware is written, since the protocol is otherwise settled. Two consequences on the app side, both real work rather than blockers: (a) no K-factor = no naira→litres cutoff = the pump **must refuse to sell**, so `CanStartTransactionUseCase` needs a third `Missing` case alongside price and fuel type; (b) `CAL` arrives asynchronously after USB attach, so there is a window where the app is up but uncalibrated — either a "waiting for adapter" hold, or cache the last-seen sealed value in `DeviceConfig` as **driver-writable / operator-read-only** (keeps the pump sellable across a USB re-enumeration without creating a tamper surface). Which of those is acceptable under NIS 348 is a question for Olonade, not a developer call.

24. **`max(adapter_eeprom, android_persisted)` compares two different scales.** Prototype Specification v1.0 (Software → power-cut transaction recovery) says the system resumes the higher pulse count. As specified this is not implementable against the current data model: the app persists a **per-transaction** cumulative (`pulseBaseline` is zeroed at every transaction start — `CustomerViewModel.kt:380,649,757,864`), whereas the adapter's EEPROM count is a **lifetime free-running totaliser**. The lifetime value is always the larger, so a literal `max()` returns it and the app bills the customer for every litre the pump has ever sold. To make the comparison meaningful the adapter needs a **session mark**: the app signals session-zero at relay-open, the adapter records the lifetime value at that mark, and recovery reads `lifetime_now − lifetime_at_mark`. **This is linked to the open "totaliser vs 10k-entry ring buffer" reading** — if "stores last 10,000 pulse counts" turns out to mean per-dispense records rather than rollover headroom, that ring buffer *is* the session-scoped data and the mark comes for free. Worth putting both to Olonade as one question. See also #25.

    > **Partly answered 2026-09-02 by Olonade's bench sketch.** The code implements a **single
    > lifetime totaliser** (`{sequence, pulseCount}`) wear-levelled across `MAX_SLOTS = 100` slots,
    > with recovery scanning for the highest sequence — i.e. **your reading, not the ring-buffer
    > reading**: the 100 slots spread EEPROM wear, they are not 100 dispense records. Note it
    > matches *neither* reading of the spec's "10,000" figure (100 slots x 8 bytes), so that number
    > is still unexplained. **The session mark is therefore still needed** — the sketch offers no
    > per-dispense scoping, so `max()` still compares a lifetime totaliser against a per-transaction
    > count and this question stands as written.

    > **SETTLED 2026-09-02 on physics — `HW-C-04` cannot mean a ring buffer, because `HW-C-05`
    > forbids it.** The ring-buffer reading of "stores last 10,000 pulse counts" needs 10,000
    > records; at a 4-byte count that is **40 KB**, and even a 2-byte delta is **20 KB**. The spec's
    > own MCU choice (`HW-C-05`: STM32F103 **or ATmega328P**) gives the ATmega328P **1 KB** of
    > EEPROM, and the bench Mega 2560 of `HW-C-10` only **4 KB** — verified by compiling `E2END + 1`
    > for both targets against AVR core 1.8.7, not read off a datasheet. The ring buffer is short by
    > **20-40x**, and the STM32F103 has no true EEPROM at all (flash emulation). So `HW-C-04` and
    > `HW-C-05` are in direct contradiction under that reading and consistent under the totaliser
    > one. **Treat "10,000 pulse counts" as loose wording for rollover headroom.** This is a
    > correction to send Olonade, not a question — though the count still wants confirming, since
    > "10,000 pulses" is only ~100 L at the placeholder K-factor, which would be a strange thing to
    > size a lifetime totaliser to. Consequence for **OQ-03/#24 stands unchanged**: no per-dispense
    > scoping exists anywhere, so the session mark must be added to the protocol.

25. **Pulses counted while the tablet is down are silently discarded — live on `main` today.** Independent of any EEPROM work, and the sharper half of #24. In the production topology the adapter is **UPS-powered and the tablet may not be**, so the adapter can outlive a tablet restart. If the tablet dies mid-dispense the firmware watchdog closes the relay after `HEARTBEAT_TIMEOUT_MS` (3 s) — but fuel flows for those 3 s and the adapter counts it. Because the *adapter* never rebooted it sends no `BOOT`, so on the tablet's return `PulseAccumulator.onPulse` hits its uninitialised branch (`PulseAccumulator.kt:43-47`), adopts the running count as a baseline and contributes **0** — roughly 1.5 L at the placeholder K-factor, delivered to the customer and billed to nobody. A second, smaller leak exists on every recovery path: the app persists only every `PULSE_PERSIST_EVERY_N = 25` pulses, so up to 24 pulses before any cut are never written. Both always under-count, so the station absorbs the loss rather than the customer. **Decision needed:** do these pulses land on the live transaction, or in a reconciliation log? They must land somewhere explicit — absorbing them into a new baseline is the current behaviour and is what the spec's recovery rule exists to prevent.

26. **The fixed-dispense cutoff is a USB round trip, and the overrun can exceed the TEST-01 tolerance.** Raised 2026-09-04. On the fixed/pre-pay/cash-fixed flows the app — not the adapter — decides when to stop: the firmware counts a pulse, frames it, ships it over USB, the app compares litres against the cutoff (`CustomerViewModel.kt:680-682`, and the same shape at `:918`), then sends `RLY:0` back down the wire. Every one of those hops is fuel on the ground. Budget: **0–30 ms** in the firmware's own `PULSE_TX_MIN_MS` throttle before the pulse is even transmitted, **~1–15 ms** of USB plus Android scheduling inbound, a coroutine hop, then `stopFuelFlow()`'s `withContext(Dispatchers.IO)` thread hop and the outbound write (`UsbSerialRelayController.kt:80`), then the firmware's `handleSerial()`. Call it **50–150 ms of controllable latency**, on top of a relay-coil + solenoid + fluid-coast term of 10–50 ms that no software change can touch. At 40 L/min that is **35–100 mL** of unbilled fuel per fixed sale — always in the customer's favour, so the station absorbs it.

    **The money is the small half.** On a 10 L run 100 mL is **1%**, which is twice the ±0.5% that `TEST-01-detail` demands and that `docs/FIELD_RUN_SHEET_2026-09-04.md` §0a flags as the pass/fail question. If the relay is gating real fuel during a calibration run, **the round trip alone can fail the accuracy gate** — and it would present as a meter/K-factor problem, which it is not. This is a reason to keep the relay out of the dispenser for the first calibration visit (run sheet §0b already recommends exactly that, for different reasons).

    **Proposal — the adapter owns the cutoff for fixed dispense; the app hands it a pulse budget.** Extend the app→device relay-open command to carry a limit: **`RLY:1:<pulses>*<cs>`**. The adapter converts it to an absolute `stopAt = readCount() + n` at command time (its counter is EEPROM-restored and lifetime-scoped since 7g, so a relative budget is the only safe wire form), then tests `pulseCount >= stopAt` **inside the pulse ISR** and drops the relay there — `digitalWrite` is two register writes and `onPowerFail()` already sets the relay from an ISR, so there is precedent. That removes the entire round trip and the `PULSE_TX_MIN_MS` throttle, leaving only the mechanical term. Five constraints, three of which are traps:

    - **Send pulses, not litres.** If the wire carries litres the adapter needs the K-factor, and there are then two copies of a constant that **#23 says is not even sealed yet**. Keeping the budget in pulses leaves the app sole owner of the K-factor, the price and the naira→litres maths, and leaves the adapter dumb: *count N, then cut*. It also means this change does not block on #23.
    - **The limit must ride in the same frame as the relay-open — never a separate arming step.** A `LIM:<n>` followed by `RLY:1` has a window in which the first frame is dropped or checksum-rejected and the second lands: a **prepaid sale free-flows with no ceiling**. One frame, one checksum. Stronger still, consider making *every* `RLY:1` require a limit, with the open-ended fill-up (Flow 1) passing a large bounded ceiling rather than a bare `RLY:1` — then a malformed limit degrades to `ERR:CMD` and no fuel, instead of to "unlimited".
    - **The reconnect re-assert will double-dispense unless it sends the *remaining* budget.** `UsbSerialRelayController.kt:47-52` re-commands `RLY:1` on the link's down→up edge (that is what resumes a prepaid fill after a transient). With a limit attached, re-asserting the *original* target lets a fill that dropped at 9 of 10 L resume with a fresh 10 L allowance. The controller today knows nothing about volume, so this is the substantive app-side work: `RelayController.startFuelFlow(limitPulses)` plus a live remaining-count the re-assert path can read. **This is the one that bites in production rather than on the bench.**
    - **A firmware-initiated stop needs to be reported, and needs to commit the totaliser.** New device→app frame **`STOP:<cum>*<cs>`** — it fits `SerialFrameParser`'s existing `TYPE:<single numeric payload>` shape, so unlike the `BOOT:<cum>:<ppl>` problem in #23 this needs **no parser surgery**. The EEPROM commit must happen from `loop()`, not the ISR, mirroring what the `RLY:0` and `ERR:WDOG` paths already do.
    - **Keep the app-side cutoff as a backstop — layer it, do not replace it.** Two independent cutoffs, both fail-safe. Once the adapter is doing the work the app's check should essentially never fire; if it does, that is a defect signal and should be logged loudly rather than silently papering over a missed limit.

    **Synergy with #24 — this may pay for the session mark.** #24 needs "the app signals session-zero at relay-open, the adapter records the lifetime totaliser at that mark". `RLY:1:<pulses>` **is that signal** — same frame, same instant. If both changes are specified together the adapter can latch `sessionStart = readCount()` on the same command that arms the limit, and `lifetime_now − lifetime_at_mark` becomes readable for power-cut recovery at no extra protocol cost. Worth putting #23, #24 and #26 to Olonade as **one protocol-revision conversation** rather than three.

    **Scope.** Fixed/pre-pay/cash-fixed only. Flow 1 (open-ended fill-up) and the pay-after flows have no cutoff known ahead of time and are unaffected — they keep the current attendant/app-driven stop. **Decision needed from Olonade:** is the relay-open frame allowed to grow a payload (it is app→device, so it does not touch the device→app framing that 7a bench-verified), and is a firmware-owned cutoff acceptable under NIS 348 given the adapter is specified read-only on the *pulse* path — the relay is a separate output, but "the board decides when to stop selling" is a metrology-adjacent claim worth confirming rather than assuming. Relates to #2 (shutoff timeout), #23 and #24.

## Payment integration

*(#5 webhook signing and #6 station virtual account are resolved — see Resolved. Numbers left as gaps; they're stable identifiers.)*

7. **Late-payment / QR-expiry reconciliation.** *Pump side resolved* (`phase7_blocker_resolution.md`): the pump is stateless about late arrivals — it only honours transactions it's actively waiting on (PENDING_PAYMENT window) and is idempotent on `transactionId`, so replays are no-ops. **Backend-side policy still open** (their item 6): auto-refund vs. wallet credit vs. manual review for money that lands after the window. Doesn't block pump code; pick before field test.
8. **Price-per-litre push channel.** *Direction resolved* (`phase7_blocker_resolution.md`): hybrid — pump fetches `GET /api/pump/config` on boot and again before every `/authorise` (the correctness guarantee), and accepts pushes for idle-screen freshness. **Push channel: FCM** — *developer decision 2026-08-04, pending boss ratification (their item 2).* The tablet will have Google Play Services; we are **advising for it** rather than treating it as a discovered constraint, on the grounds that FCM beats holding a persistent WebSocket open on a kiosk device (battery, reconnect handling, no socket-liveness code to own). Supporting evidence: the bench tablet is an **SM-T220 / Galaxy Tab A7 Lite**, a Samsung consumer device that ships with Play Services — so the assumption already holds on the hardware in hand. Manager expected to confirm the production units match. **Contingency if a Play-less unit is ever ordered:** the fetch-on-boot + fetch-before-`/authorise` path is unaffected (it carries the correctness guarantee), so the fallback is degraded idle-screen freshness — poll on a timer — not a WebSocket build-out, unless push latency turns out to matter. Same decision also covers the PAID-notification push and future credential rotation. **Note:** FCM is best-effort delivery, so it must stay a freshness optimisation only — never the thing a sale prices off.

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

    > **Partly settled device-locally by Phase 7b (2026-09-02).** `fuelType` and price are now set on
    > the tablet via the operator config screen, because the Reference documents no `/config`
    > endpoint at all and `/authorise` requires a `fuelType` (`API_CONFORMANCE_AUDIT.md` §6 #4).
    > `DeviceConfig` gained `fuelType: FuelType?` (schema v3); null blocks all transactions exactly
    > as a null price does. This is **not** a decision against `/config` — when it ships, the screen
    > becomes the manual override and the backend-unreachable fallback. `virtualAccountNumber`
    > stays for now: obsolete by OQ #6, but still feeding `buildNipTransferQr` until 7c replaces
    > that path with Paystack. Shutoff timeout (OQ #2) remains open.

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
  **Accepted risk recorded 2026-09-02 (Phase 7b):** the operator config screen — which sets fuel
  type and price — sits behind that *same* shared PIN, so **any attendant who can authorise a sale
  can also change the fuel price**. Accepted for V1 rather than inventing a second PIN outside the
  agreed model; the alternative (leaving config debug-only) would leave production pumps with no way
  to set a `fuelType` at all, which `/authorise` requires. Revisit with role-based PINs in V2. The
  screen is not hidden from attendants, so a price change is at least performed deliberately rather
  than through an undocumented gesture.
