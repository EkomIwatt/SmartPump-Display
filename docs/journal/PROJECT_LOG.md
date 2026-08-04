# SmartPump Display — Project Log

## Current status — 2026-08-04

Strict-design rebuild is complete and merged to `main`: all 5 flows, the attendant overlay, persistence/boot-resume, kobo money, and Room migrations are in. **Phase 7a (real USB-serial hardware) is merged to `main`** (Arduino pulse driver + relay, bench-verified). `main` is pushed to `origin`.

**`feature/phase-7a-hardening` is MERGED to `main`** (merge commit `9b76f42`; branch deleted) — comms-loss heartbeat watchdog (firmware PING dead-man + app heartbeat + reconnect `RLY:1` re-assert; the earlier app-side disconnect pause/resume was removed 2026-07-08) **plus** the **Balancee Pump API network-layer foundation** (signed client, typed errors + retry, Keystore-encrypted creds). Both merge gates closed before merge — #10 (Keystore crypto, device-verified 2026-07-08) and #2 (firmware-watchdog safety, device-verified 2026-07-10). The boss-facing watchdog safety summary + report shipped on top (`ceef973`, `8b72775`). `main` = `8b72775` = `origin/main` — build green on both variants, nothing outstanding on this line of work.

**Phase 8 (CustomerViewModel unit tests) is DONE and MERGED to `main`** (merge commit `d2c4283`, 2026-08-04; branch commits `88be743`/`98fa167`/`a128457`/`0bd45f3`) — 23 new pure-JVM tests (money/cutoff, dispensing completion, boot-resume, lifecycle) via hand-written fakes + an `UnconfinedTestDispatcher` rule; test-only bar one build flag. Post-merge verification on `main`: full suite green at **81 tests** (12 classes, 0 failures/errors/skips) and `compileDebugRealHwKotlin` clean. **Pushed: `main` = `origin/main` = `f5038d8`; branch `feature/phase-8-vm-tests` deleted.**

**⚠️ API conformance audit — 2026-08-05.** The Pump API Reference PDF landed in `docs/` on 2026-08-04 (first sight of the *primary* doc; the network layer was built in July against our summary of it). Line-by-line audit found **9 issues** — see [`API_CONFORMANCE_AUDIT.md`](API_CONFORMANCE_AUDIT.md), tracked as TODO #11–#18. Headlines: **(#11, critical)** responses are enveloped in `{status,message,data}` and we parse the inner shape → **all 5 calls fail against the real server**, and the MockWebServer fixtures encode the same wrong assumption so the green suite proved nothing; **(#12, security)** debug `Level.BODY` logging prints `apiKey`+`signingSecret` from the `/activate` body — no leak yet (logcats grepped clean; activation never ran), fix before first activation; **(#13)** the once-only `pumpId` is never persisted. **Decided:** `amount` is **naira** (app stays kobo; mapper owns the ÷100). **Backend gaps:** `GET /config` and `GET /transactions/{id}` don't exist, and **nothing tells the pump its `fuelType`** — which `/authorise` requires. Signing, headers, retry policy and the Keystore store all verified **correct**.

**Next:** fix #11 (envelope) → #12 (credential logging) → #14/#15/#13/#16 with the activation flow. Send the #18 backend asks immediately (longest lead time). Then unblocked Phase 7 sub-phases (7b operator config — interim device-local config screen / 7e backend sync) + payment feature flows (#8).

Older entries (Week 1 → Room migrations) are archived in [PROJECT_LOG_ARCHIVE.md](PROJECT_LOG_ARCHIVE.md).

---

## Prompt for Claude

> **After completing any project phase (or any meaningful unit of work the user labels a "phase", "stage", "run", or "milestone"), append a new entry to this file before ending the turn.**
>
> Each entry must follow the template under "Entry template" below. Keep the tone plain and non-technical in the **Summary** section so a non-engineer can follow along; put technical specifics in **Technical notes**.
>
> Rules:
> - Add new entries at the **bottom** of the log, under the "Entries" section. Never rewrite or delete previous entries — the log is append-only history.
> - Use the real date from the environment context (do not guess).
> - Reference commit hashes when the phase was committed; write "uncommitted" if it was not.
> - If a phase was abandoned or rolled back, still log it and say so — do not silently drop it.
> - Do not log routine tweaks, single-file fixes, or in-progress work. Only completed phases/milestones.
> - If the user explicitly says "don't log this," skip the entry for that turn.

---

## Entry template

```
### Phase N — <short title>
**Date:** YYYY-MM-DD
**Status:** done | partial | rolled back
**Commit(s):** <hash> or "uncommitted"

**Summary (plain language):**
<2–4 sentences a non-engineer can follow. What can the app now do that it couldn't before?>

**Technical notes:**
- <key change 1>
- <key change 2>
- <gotchas, dependencies added, decisions made>

**Next:**
<what the next phase is expected to cover, if known>
```

---

## Entries

> Entries before Phase 7a are in [PROJECT_LOG_ARCHIVE.md](PROJECT_LOG_ARCHIVE.md). New entries still go at the bottom of this file.

### Phase 7a — Hardware: real USB-serial pulse driver + relay (bench-verified)
**Date:** 2026-06-11
**Status:** done (happy path bench-verified; one disconnect-robustness gap logged for follow-up)
**Commit(s):** 97f310d (plan), 122e1b0 (parser + tests), 32acf20 (driver + build type), a7b8fef (Arduino sketch); merged to `main` from `feature/phase-7a-hardware`

**Summary (plain language):**
The app can now run a real fuel dispense off actual hardware, not just the simulator. We wrote the firmware for an Arduino (the little board that will sit between the pump's pulse sensor and the tablet), and the Android side that talks to it over the USB cable. On the bench with an Arduino Uno: starting a transaction opens the pump (the board's light comes on), the litres count up as real electrical pulses arrive, and finishing the sale closes the pump (light goes off). Both directions were confirmed. The whole thing is built as a separate "real-hardware" version of the app that installs alongside the normal simulator version, so the boss demo has a rock-solid fallback — if the rig ever misbehaves, you just open the other app. The boss demo can show genuine hardware dispensing.

One rough edge found during testing and deliberately deferred (see Known limitation): if you yank the USB cable in the *middle* of a pre-pay dispense, the screen freezes instead of recovering cleanly. The fill-up flow handles this fine; the fixed-amount flows don't yet. Not a demo issue (you don't unplug mid-sale on purpose), but it's the next thing to harden.

**Technical notes:**
- **Pure core (`122e1b0`):** `data/hardware/serial/SerialFrameParser` (stateless line → typed `SerialFrame`; validates the `TYPE:<cum>*<cs>` framing + XOR-8 checksum, never throws) and `PulseAccumulator` (stateful cumulative→delta: dropped lines self-heal off the running count, BOOT re-baselines, backward jumps re-sync to 0 so no negative delta reaches the litre maths). 19 unit tests; checksums hand-computed as golden vectors (the framing doc's illustrative `PULSE:0042817*7C` is wrong — real XOR-8 is `5D`).
- **Driver (`32acf20`):** `UsbSerialConnection` (@Singleton owner of the one physical port via mik3y `usb-serial-for-android` 3.8.0 — single read loop, line-buffers to `\n`, parses, republishes typed frames on a hot `SharedFlow`; `writeLine()` for relay; `connected` StateFlow; USB permission via runtime `requestPermission` + persistent grant through the manifest attach filter; non-zero read timeout so writes aren't starved). `UsbSerialPulseSource` is a cold `observe()` per dispense that resets the session count on each relay-open and runs `PulseAccumulator` — so the VM's `pulseBaseline + msg.count` maths is unchanged (true drop-in for the mock). `UsbSerialRelayController` writes `RLY:1`/`RLY:0` (XOR-8 framed), optimistic `isDispensing` always cleared on stop so a failed write can't strand the app believing fuel still flows.
- **Wiring/build (`32acf20`):** `HardwareModule` switched from `@Binds` mocks to `@Provides` + `Provider`, branching on `BuildConfig.MOCK_HARDWARE` so only the selected impl is instantiated. New `debugRealHw` build type (`initWith` debug, `applicationIdSuffix .realhw`, `MOCK_HARDWARE=false`) installs alongside the mock `debug` app. Manifest gained the `USB_DEVICE_ATTACHED` intent-filter + `res/xml/usb_device_filter.xml` (Arduino/CH340/FTDI/CP210x vendors) for auto-launch + persistent permission.
- **Firmware (`a7b8fef`):** `hardware/smartpump_pulse_adapter/*.ino` — emits `BOOT` at power-up (relay asserted OFF first → upholds relay-open-on-boot), `HB` ~2s when idle, throttled `PULSE` carrying the free-running cumulative; reads checksum-validated `RLY:1`/`RLY:0` to drive the relay pin and mirrors state on the onboard `D13` LED (so a bare Uno demos with no extra parts). Meter-free demo synthesises pulses while dispensing (`AUTO_PPS=50` ≈ 30 L/min); optional button + real-meter-on-INT0 paths included. `hardware/README.md` has protocol, worked checksums, wiring, flashing, and the bench checklist.
- **Bench result (2026-06-11):** Uno flashed; `BOOT`/`HB` seen on serial. `debugRealHw` installed; USB permission granted. Fill-up before plug-in → no count (correct); after plug-in → litres count (correct). Relay confirmed both directions via the onboard `D13` LED: `RLY:1` lit it on authorise, normal completion sent `RLY:0` and cleared it. Fill-up unplug-mid-dispense → flow-gap watchdog moved to amount-due (correct).
- Both variants compile; lint clean; serial unit tests green. `main`'s mock demo path is unchanged.

**Known limitation (deferred to a 7a-hardening pass):**
- A mid-dispense USB disconnect is **not handled gracefully in the fixed-dispensing flows** (pre-pay, cash-fixed, USSD). Fill-up has a 3s flow-gap watchdog and recovers; the fixed flows have none, so a yank freezes `FixedDispensing` (litres stop, screen holds) instead of moving to a safe state. Reconnect resumes but stutters — leading suspects: the Uno reboots to relay-OFF on replug while the app still believes `isDispensing` (no `RLY:1` re-assert), the `USB_DEVICE_ATTACHED` filter may relaunch the activity, and `PulseMessage.Disconnected` is ignored in the fixed collector. Needs logcat to pin the exact sequence. Tracked in `OPEN_QUESTIONS` (mid-dispense link loss); relates to OQ #3 (relay-on-boot) and #7 (relay closing late). **Not a demo blocker** — only triggers on a deliberate cable-pull mid-sale.

**Next:**
After the demo: 7a-hardening — add disconnect handling to the fixed-dispensing flows (a watchdog and/or an explicit "pump disconnected — reconnect" state) and a reconnect-mid-dispense relay/session re-assert policy; capture logcat from a repro first. Then resume the Phase 7 sub-phases by external-blocker availability (7b operator config / 7e backend sync are the unblocked first movers).

---

### Phase 7a-hardening — mid-dispense disconnect: fail-safe relay + fixed-flow pause/resume
**Date:** 2026-06-30
**Status:** done (code + bench-protocol; build green. Field logcat verification + VM unit tests still to come)
**Commit(s):** dbf4cb6 (§1 firmware watchdog), c416322 (§2 app keepalive), 8453d85 (§3 app disconnect handling), <docs> — on branch `feature/phase-7a-hardening`, not yet merged to `main`

**Summary (plain language):**
We closed the rough edge found during the Phase 7a bench test: yanking the USB cable in the middle of a *prepaid* sale used to freeze the screen. Two things now make that safe. First, the Arduino itself became the safety authority over fuel: it only keeps the pump on while the app keeps telling it "still going" (about once a second); the moment the app goes quiet — cable pulled, app frozen — the board shuts the pump off on its own within ~2 seconds. Fuel can no longer keep flowing just because the tablet lost contact. Second, the app no longer freezes: if the link drops mid-sale, the screen shows a clear "pump disconnected — reconnecting" hold, remembers how much was dispensed, and **automatically carries on to the litres the customer paid for** the instant the cable is back. Because the customer prepaid, we never charge them for the partial amount — we owe them the full litres, so we resume rather than cut the sale short (that's different from a fill-up, where you pay for whatever flowed). The whole thing can be demoed on the simulator too, via the existing debug "inject disconnect".

**Technical notes:**
- **§1 — firmware dead-man watchdog (`smartpump_pulse_adapter.ino`):** relay is now fail-closed. `serviceRelayWatchdog()` closes the relay if no valid `RLY` command arrived within `RELAY_DEADMAN_MS = 2000` (≈3× the app keepalive period, under the 3 s fill-up shutoff window). Emits best-effort `ERR:WDOG` (XOR-8 `64`) on trip. `lastRelayCmdMs` updated on every accepted `RLY:1/0`. Protocol header + `hardware/README.md` (protocol table, worked checksum, bench checklist) updated. Android build untouched.
- **§2 — app relay keepalive (`UsbSerialRelayController`):** while `isDispensing`, a coroutine on an app-lifetime IO scope re-asserts `RLY:1` every `KEEPALIVE_MS = 700`. This feeds §1's watchdog *and* re-energises the relay automatically when the port reopens after a brief unplug (no VM involvement). All relay writes (`start`/`stop`/keepalive) funnel through one `Mutex` so the keepalive can't race a start/stop on the single physical port; `isDispensing` is gated off before the keepalive job is cancelled in `stopFuelFlow()` so a late `RLY:1` can't follow the final `RLY:0`. Mock `MockRelayController` unchanged.
- **§3 — app fixed-flow disconnect handling (`CustomerViewModel` + new state):** new `TransactionState.PumpDisconnected` (snapshot: flow, txnId, price, amount, litresTarget, litresSoFar, method). The pre-pay/USSD collector (`startDispensing`) and the cash-fixed collector (`startCashFixedDispensing`) now `when` over the message: `Disconnected` → pause into `PumpDisconnected` **without** cancelling the collector or dropping relay intent (so §2 keepalive keeps trying); `Heartbeat`/`Pulse` while paused → flip back to the dispensing state and continue. The live collection keeps its session pulse count across the reconnect (the accumulator's `onBoot` re-baselines the Uno's reset counter), so resume needs **no** `pulseBaseline` surgery. `bootResume()` gained a `PumpDisconnected` branch that restarts the underlying dispense toward its prepaid target (covers a power cut while paused). New `PumpDisconnectedScreen` (WarningRed, dispensing-family card language) wired into `CustomerStateHost`; `StateColors` + `txnRefFor` branches added.
- **Design call (resolves OQ #21, pending boss ratification):** fixed flows **pause-and-resume**, not treat-as-shutoff, because the customer prepaid for the full litres. Fill-up keeps its 3 s flow-gap shutoff (it bills what flowed by design and so was never affected).
- **Verification:** `:app:compileDebugKotlin`, `:app:compileDebugRealHwKotlin`, `:app:testDebugUnitTest` all green (existing 19 serial unit tests still pass). Firmware reviewed but not re-flashed this pass — the §1/§2 watchdog+keepalive loop wants a bench re-run (unplug-mid-fixed-dispense → LED off within ~2 s → replug → resumes from where it paused) before merge. Manual mock-build check available via debug "inject disconnect".

**Deferred (intentionally):**
- **CustomerViewModel unit tests** for the pause/resume/boot-resume paths — these need the fakes+Turbine VM test harness that **Phase 8** is scoped for (gated, with two open decisions). The disconnect/resume logic is now the obvious first Phase 8 target rather than a reason to stand the harness up early here.
- **Field logcat verification** of the exact reconnect sequence on the real rig (the original bench note's worry about a `USB_DEVICE_ATTACHED` activity relaunch — not reproduced on the demo path with the persistent grant; revisit only if logcat shows it).

**Next:**
Bench re-run of the watchdog+keepalive loop on the Uno, then merge `feature/phase-7a-hardening` → `main`. Phase 8 (VM tests) picks up the disconnect path first. Then the unblocked Phase 7 sub-phases (7b operator config / 7e backend sync).

---

### Phase 7a-hardening (addendum) — watchdog re-keyed on a dedicated PING heartbeat
**Date:** 2026-06-30
**Status:** done (code + protocol; build green. Same bench re-run still pending before merge)
**Commit(s):** 07d7ccb — on branch `feature/phase-7a-hardening`

**Summary (plain language):**
After the boss reconciled this against the project docs, we firmed up *how* the pump knows the tablet is still there. The earlier version treated the "keep dispensing" command itself as the heartbeat. The agreed production design separates the two: the tablet now sends a tiny, dedicated "still alive" ping about once a second, and the pump-side board watches **that**. If the ping goes quiet mid-sale — cable knocked out, app frozen — the board shuts the pump off on its own, without needing to know how many litres were bought. It's the cleanest statement of the rule: *lose contact, stop dispensing.* This works in the field because the production board runs off the station UPS, not off the tablet's USB, so it stays powered and in control even if the data cable drops. Behaviour the customer sees is unchanged — a brief drop still auto-resumes their prepaid litres once the link is back.

**Technical notes:**
- **Protocol:** new app→device `PING*<cs>` (XOR-8 `10`), distinct from the device→app `HB`. `RLY:1`/`RLY:0` revert to one-shot edge commands. `ERR:WDOG` retained as the trip notice.
- **Firmware:** watchdog now keys on `lastHeartbeatMs` (fed by `PING`) vs `HEARTBEAT_TIMEOUT_MS = 3000`; `RLY:1` seeds the heartbeat clock to avoid a start-of-dispense race. Renamed from the `RELAY_DEADMAN_MS`/`lastRelayCmdMs` form.
- **App:** `UsbSerialConnection` owns the heartbeat — a framed `PING` every `HEARTBEAT_PERIOD_MS = 1000` on an app-lifetime IO scope while the port is open; `writeLine` is now `synchronized(writeLock)` so heartbeat and relay writes can't interleave on the one port. `UsbSerialRelayController` drops the RLY keepalive and instead **re-asserts `RLY:1` on the `connection.connected` down→up edge** when `isDispensing` (the watchdog is fail-safe and never re-energises itself). `§3` `PumpDisconnected` pause/resume UI is unchanged.
- **Residual gap (documented, not fixed):** the heartbeat covers USB-drop / crash / process-death. A pure *main-thread* ANR where background (IO) threads keep running would keep the heartbeat alive while litre-counting (Main-confined) is stalled — the adapter wouldn't trip. The app's own litre-cutoff remains the primary control; the watchdog is the backstop. Gating the heartbeat on main-thread liveness is a possible future hardening if that failure mode matters.
- **Verification:** `:app:compileDebugKotlin` + `:app:compileDebugRealHwKotlin` + `:app:testDebugUnitTest` green. Firmware not re-flashed — folds into the one pending bench re-run (now: confirm `PING*10` ~1/s; unplug mid-dispense → relay off within ~3 s; replug → `RLY:1` re-assert → resume).

**Next:**
Unchanged from the parent entry — bench re-run, then merge. (The bench step now also checks the `PING` cadence + 3 s trip.)

---

### Docs reconciliation — Pump API Reference / blocker-resolution ingest
**Date:** 2026-07-03
**Status:** done (docs only, no code)
**Commit(s):** uncommitted

**Summary (plain language):**
A new doc (`docs/phase7_blocker_resolution.md`) arrived that answers most of the payment/onboarding
blockers — and changes the shape of the whole payment side. The old design had the bank's backend
calling *into* the pump (a "webhook"); the new one has the **pump doing the calling** — it asks the
backend to start a sale, gets a Paystack QR back, the customer pays Paystack, and the pump finds out
it was paid by a mix of a push message and a quick repeated check. We went through the project's
authority docs and brought them in line with this, marked which old open questions are now answered,
and — importantly — corrected one mistake in the new doc: it claimed the offline USSD flow was dead,
but that flow (for stations with no internet) is only **deferred to a future update**, not cut.

**Technical notes:**
- **`OPEN_QUESTIONS.md`:** #5 (webhook signing → now *outbound* HMAC-SHA256, 4 signed headers), #6
  (station virtual account → obsolete, Paystack owns payments), #14 (receipt sharing → system share
  sheet) moved to **Resolved** (all "pending boss ratification that the API Reference is canonical").
  #7 (late-payment) and #8 (price-push channel: FCM-vs-WebSocket pending a Play-Services answer) and
  #13 (DeviceConfig → `/activate` + `/config`) annotated in place. USSD/SMS block (#9–#12) re-headed
  **DEFERRED to a future update** with the two-USSD-paths correction. Numbers kept stable (gaps left).
- **`phase7_blocker_resolution.md`:** struck-and-corrected two claims against reality — (a) USSD is
  *not* obsolete (offline mode ≠ Paystack USSD; deferred, not dropped); (b) the "7a stays mocked"
  line is stale — 7a real hardware is built/bench-verified/merged; this doc has no firmware impact.
- **`flows.md` / `state-machine.md`:** added payment-inversion banners (webhook→pump-initiated
  Paystack QR + push/10s-poll on `GET /api/pump/transactions/{id}`; Flow 3 NIP-QR replaced by a
  Paystack `authorizationUrl`; states unchanged, only the confirmation *trigger* changes). Flow 5
  marked deferred in both. Flow bodies left intact pending ratification — banners hold authority.

**Next:**
Build the network client layer (Retrofit/OkHttp + the outbound HMAC signing interceptor) as the
Phase 7c/7e foundation — the one piece safe to build before the boss ratifies the reference, since
the signing scheme is fully specified and unit-testable offline.

---

### 7a-hardening follow-up — remove app-side disconnect pause/resume (keep firmware watchdog)
**Date:** 2026-07-08
**Status:** done
**Commit(s):** uncommitted (this entry committed alongside the change)

**Summary (plain language):**
The "pump disconnected" pause-and-resume screen — added in the 7a-hardening work so a mid-fill USB
cable-yank would hold the sale and resume where it left off — was causing recurring bugs. We're now
building on the assumption that **the USB cable is fixed in the kiosk** (it can't be yanked) and the
only real failure is a power cut, which the UPS covers. So that whole app-side "disconnected" state
and its screen were removed to simplify things. Crucially, the **safety cut-off stays**: the Arduino
still stops the pump on its own if it stops hearing from the tablet (e.g. the app freezes while fuel
is flowing) — a fixed cable does nothing to prevent that, so the backstop had to remain. A genuine
brief USB glitch also still self-heals and keeps counting; the customer just sees the normal
dispensing screen pause a moment instead of a dedicated "disconnected" screen.

**Technical notes:**
- Removed `TransactionState.PumpDisconnected`, `PumpDisconnectedScreen.kt` (deleted), and its
  branches in `CustomerStateHost`, `StateColors.borderColor()`, and `CustomerViewModel`
  (boot-resume, `txnRefFor`, both dispensing collectors, the four `to*/toDisconnected` mappers).
  The pre-pay/USSD/cash-fixed collectors now no-op `PulseMessage.Disconnected`/`Heartbeat`
  (the `PulseMessage` types are retained — they still map real wire events).
- **Kept untouched:** `UsbSerialConnection`'s outbound `PING` heartbeat (feeds the firmware
  dead-man watchdog), `UsbSerialRelayController`'s down→up reconnect `RLY:1` re-assert, and the
  Arduino sketch. Comms-loss *safety* is therefore unchanged; only the disconnect *screen/pause* is gone.
- Backward-compat: a device persisted mid-`pump_disconnected` across this upgrade fails to
  deserialise and falls back to `Idle` on boot (same graceful path as the kobo migration).
- Verified green: `:app:compileDebugKotlin` and `:app:compileDebugRealHwKotlin` both BUILD SUCCESSFUL.
- Docs reconciled: `OPEN_QUESTIONS.md` #21 got a dated revision (parts 1–2 kept, part 3 removed);
  `TODO.md` #2 bench checklist updated (watchdog + reconnect only, no pause/resume).

**Next:**
Unchanged — Uno bench re-run of the firmware watchdog + reconnect re-assert, then merge
`feature/phase-7a-hardening` → `main`. Then Phase 8 (CustomerViewModel unit tests).

---

### Phase 7a-hardening — bench verification: firmware watchdog safety confirmed on device (merge gate #2 closed)
**Date:** 2026-07-10
**Status:** done (watchdog safety verified on the real rig; branch merge-ready, merge pending)
**Commit(s):** committed alongside this entry — on branch `feature/phase-7a-hardening`

**Summary (plain language):**
This was the final safety check on the real tablet-and-Arduino rig before merging the comms-loss
watchdog work. The scare from the previous bench session — where a normal sale kept cutting off after
about six seconds — turned out **not** to be a code fault. On a freshly installed build the rig ran
six sales back-to-back with no trouble, and the tablet's "still-alive" pings to the board stayed
perfectly steady the whole time. The earlier cut-offs were a flaky-power quirk of the *bench* setup:
the little board browns out when it runs off the tablet's own USB port, and momentarily drops the
connection. Production avoids this entirely by powering the board from the station UPS, and even when
the glitch does happen it fails *safe* (it stops fuel, never over-pours). The safety test itself then
passed cleanly: with fuel flowing we force-killed the app to simulate a freeze/crash, and the Arduino
shut the pump off on its own about three seconds later — exactly as designed. That was the last thing
blocking the merge.

**Technical notes:**
- **Rig:** Samsung SM-T220 (Galaxy Tab A7 Lite, Android 14, USB-C; wireless adb since the USB-C port
  hosts the Uno). Freshly built + installed `debugRealHw` carrying two temporary `Log` diagnostics
  (`PING tx ok=<bool>` per heartbeat; `device ERR frame:<code>` for any `SerialFrame.Error`) — **since
  reverted** (source now byte-identical to HEAD).
- **Repro attempt (did not reproduce):** 6 dispenses back-to-back — 4 fill-up, 1 pre-pay, 1
  post-replug — each 23–50 s, all to normal completion. `PING tx ok=true` **steady at 1 Hz through
  every dispense**; **zero `ERR:WDOG`**; no write failures. The "consistent ~5.9 s trip" from the prior
  session was absent → **PING-starvation-under-pulse-load is ruled out.**
- **Root cause reframed (not a code bug):** an **intermittent bus-power USB disconnect**. Signature is
  `USB get_status request failed` → self re-enumeration (device path `001/060`→`061`). When it lands
  mid-dispense, the read-error `handleDetach()` teardown stops the heartbeat → firmware trips at +3 s →
  app pulse-gap watchdog ends the sale (a fixed ~3 + 3 s offset *after* the disconnect; whether it
  strikes mid-dispense is intermittent). Bench artifact — the bare Uno + relay browns out off the
  tablet's OTG port; production powers the adapter from the UPS; the failure is fail-safe.
- **Merge gate #2 — safety check PASSED (the reframed primary case):** mid-dispense
  `adb shell am force-stop app.balancee.smartpump.display.realhw`. Last `PING` 14:38:56.443, process
  confirmed killed 14:38:57.423, **relay physically opened ~3 s later** (watched on `D13`). `ERR:WDOG`
  is guaranteed by construction — `serviceRelayWatchdog()` runs `setRelay(false)` then `sendError("WDOG")`
  inside the one `if`, so the relay dropping *is* that branch executing. This proves the "app
  frozen/crashed mid-flow, cable still connected" mode that the UPS + fixed-cable assumption do **not**
  cover.
- **Evidence retained:** `docs/logcats/bench-multirun_realhw_2026-07-10.log` (the 6 clean dispenses),
  `docs/logcats/forcestop-test_2026-07-10.log` (the safety proof). A stale Android-Studio `package:mine`
  export that captured zero app logs was removed.
- **Non-blocking follow-up (post-merge):** spontaneous-disconnect robustness — tolerate a sub-second USB
  glitch with a fast reconnect-and-resume vs. keep the current fail-safe (stop fuel). Validate on
  **external 5 V** (powered USB-C hub + PD pass-through, Route A); the `get_status` re-enumerations are
  expected to vanish on stable power. Does not hold the merge.

**Next:**
Merge `feature/phase-7a-hardening` → `main` (pending explicit go). Then Phase 8 (CustomerViewModel
unit tests) → unblocked Phase 7 sub-phases + payment feature flows (#8).

---

### Phase 7a-hardening — merged to `main` + boss watchdog safety deliverable
**Date:** 2026-07-10
**Status:** done
**Commit(s):** `9b76f42` (merge), `ceef973` (safety summary + OQ #22), `8b72775` (report to `/reports`)

**Summary (plain language):**
With both merge gates closed, the hardening branch was merged into `main` and the branch deleted.
That folds the comms-loss safety watchdog and the Balancee payment network-layer foundation into the
mainline. On top of the merge, we produced the boss-facing deliverable that explains the watchdog
safety story in plain terms — a one-page feature summary and a formatted report (HTML + PDF) now
living under `/reports`. `main` is pushed; there is nothing left outstanding on the hardware/hardening
line of work — the next scheduled work is the Phase 8 test harness.

**Technical notes:**
- **Merge (`9b76f42`):** `feature/phase-7a-hardening` → `main`, no-fast-forward merge commit; source
  branch deleted (confirmed absent from `git branch -a`). Brings in the full lineage `1f2a3ea …
  4a10824` (firmware watchdog, PING heartbeat re-key, app-side pause/resume removal, network layer,
  KeyStore crypto, both gate closures). Build green on `debug` + `debugRealHw`.
- **Boss deliverable (`ceef973`, `8b72775`):** watchdog safety feature summary written for a
  non-engineer audience; report moved into `/reports` as an HTML + PDF pair. New open question logged
  as OQ #22.
- **State after:** `main` = `origin/main` = `8b72775`. Both merge gates (#2 firmware-watchdog safety,
  #10 Keystore crypto) verified on device before merge.

**Next:**
Phase 8 (CustomerViewModel unit tests — disconnect/boot-resume + litre-cutoff paths first), then the
unblocked Phase 7 sub-phases (7b operator config / 7e backend sync) and the boss-gated payment feature
flows (#8).

---

### Phase 8 — CustomerViewModel unit tests (harness + money/dispensing/boot-resume/lifecycle)
**Date:** 2026-07-31
**Status:** done
**Commit(s):** `88be743` (A: infra), `98fa167` (B: harness + money + completion), `a128457` (C: boot-resume + lifecycle) — on branch `feature/phase-8-vm-tests`

**Summary (plain language):**
The app's highest-risk piece — the ~1,000-line "brain" that runs the money maths, opens and closes
the pump, and resumes a half-finished sale after a power cut — had no automated tests. It now has 23,
all passing. They pin down the behaviour that matters: fuel stops exactly at the litres the customer
paid for (and never over-pours, even on a big pulse jump), amounts too small to buy any fuel are
refused, the pump is forced closed on start-up, cancelling a sale shuts fuel and wipes the running
count, and a sale interrupted by a power cut picks up from where it left off rather than restarting or
double-charging. This is test-only work — no behaviour of the shipping app changed. The full project
test suite is green at 81 tests.

**Technical notes:**
- **Purely additive (per the two settled decisions):** the only `src/main` touch is a build flag.
  `testOptions { unitTests.isReturnDefaultValues = true }` lets the VM's direct `android.util.Log`
  calls return defaults instead of throwing "not mocked" — chosen over introducing a production
  `Logger` interface. DAO/Room tests deliberately **deferred** to keep Phase 8 pure-JVM (no
  Robolectric, no androidTest).
- **Harness (`CustomerViewModelTestSupport.kt`):** hand-written fakes (no mocking library) for
  `PulseSource`/`RelayController`/`PaymentProcessor`/`DeviceConfigRepository`/`PulseRepository`/
  `TransactionRepository`, wired to the **real** `CanStartTransactionUseCase`. A `MainDispatcherRule`
  pins `Dispatchers.Main` to one `UnconfinedTestDispatcher` (shared `TestCoroutineScheduler`), so
  `viewModelScope` runs eagerly and assertions read `ui.value` synchronously — **Turbine proved
  unnecessary** and was not added. Because `init` runs `bootResume()` eagerly, the fakes are seeded
  **before** `VmHarness.build()`. Test price fixed at `TEST_KOBO_PER_LITRE = 100_000` (₦1000/L) so
  litres = pulses/100 give round numbers.
- **Coverage (23 tests):** money/cutoff (cutoff = amount÷price, the floor-to-0.01L
  never-over-dispense guard, below-minimum → recoverable Error, price-not-set guard, audit-record
  accuracy); completion (fixed/pre-pay/cash-fixed stop at target, no overrun on a pulse jump, relay
  closed, correct `Transaction` saved, method recorded); fill-up (open-ended count + nozzle-shutoff →
  `FillupTankFull` with locked litres/amount); boot-resume (every `bootResume` branch, restarting from
  the persisted pulse baseline); lifecycle (relay-open-on-boot invariant, cancel teardown, dismiss,
  prepay expiry auto-cancel via `advanceTimeBy`).
- **No production bug surfaced** — the tests pin current behaviour and all passed as written. (Had one
  failed, fixing it would have been a separate flagged change, not folded into a test commit.)
- **Verification:** `:app:testDebugUnitTest` → **BUILD SUCCESSFUL, 81 tests, 0 failures, 0 errors**
  (23 new + 58 existing). JBR (Java 21) via `JAVA_HOME`.

**Next:**
Merge `feature/phase-8-vm-tests` → `main`. Then the unblocked Phase 7 sub-phases (7b operator config /
7e backend sync) and the boss-gated payment feature flows (#8, pending the 7 boss confirmations).

---

### Phase 7 network layer — Balancee Pump API client foundation
**Date:** 2026-07-04 (committed on `feature/phase-7a-hardening`; logged here 2026-07-10)
**Status:** done (transport + credential foundation; payment *feature* flows still gated on boss confirmations)
**Commit(s):** 29fe12b (docs reconciliation), 4af9514 (network layer), ff8fd11 (PumpApiClient), ac73feb (encrypted creds), ccf0534 (debug cleartext), 6df836f (backend URLs), 91fa772 (Keystore instrumented test / gate #10)

**Summary (plain language):**
Alongside the watchdog work, this branch also built the plumbing for the app to talk to Balancee's
backend over the internet — the signed HTTP client, consistent error handling, and secure on-device
storage of the pump's login credentials — *without* yet wiring up the actual payment screens (those
still wait on a set of confirmations from the boss). This is the safe-to-build foundation: the request
signing is fully specified, so it can be written and unit-tested offline now, ahead of the parts that
are still provisional.

**Technical notes:**
- **Signed client (`4af9514`):** Retrofit/OkHttp network layer with the outbound HMAC-SHA256 signing
  interceptor per the Pump API Reference (4 signed headers).
- **Transport wrapper (`ff8fd11`):** `PumpApiClient` over all 5 endpoints; `ApiResult`/`ApiError`
  typed-error funnel (`safeApiCall`), `retryingApiCall` backoff (retry on the idempotent upload);
  interceptor throws typed `PumpNotActivatedException`. MockWebServer tests green. **DTO↔domain mapping
  deliberately deferred** (client kept transport-only while the money unit and `/config` shape are
  provisional).
- **Encrypted creds (`ac73feb`):** `KeystorePumpCredentialsStore` — AES-256-GCM key in the Android
  KeyStore + ciphertext in private SharedPreferences, decrypted creds cached for the synchronous
  `current()` hot path. Chose KeyStore-direct over the deprecated `security-crypto` lib; `NetworkModule`
  binding swapped, `InMemoryPumpCredentialsStore` deleted.
- **Gate #10 (`91fa772`):** first androidTest in the project — 5 instrumented tests
  (`KeystorePumpCredentialsStoreTest`) pass on a physical device: not-activated, save→current
  round-trip, isActivated toggle, persistence across a fresh instance, `clear()` wipe, corrupt-blob →
  null fallback + ciphertext purge. Runtime AES-GCM-at-rest confirmed.
- **Config (`ccf0534`, `6df836f`):** debug-only cleartext `network-security-config` for the local
  backend (10.0.2.2/localhost/127.0.0.1; `debugRealHw` reuses it; release stays cleartext-denied);
  real prod/dev backend URLs wired.

**Next:**
Payment feature flows (#8) — activate → persist creds; authorise → Paystack QR; PAID via push + 10 s
poll; price-config fetcher; WorkManager upload job — **blocked on the 7 boss confirmations (#6).**
Sandbox-testable; live money gated behind the 14-day parallel run.

---

### API conformance audit — Reference PDF vs the built network layer
**Date:** 2026-08-05
**Status:** done (audit + decisions; no code fixes yet)
**Commit(s):** this entry + `docs/journal/API_CONFORMANCE_AUDIT.md`

**Summary (plain language):**
The official API document from the backend team was added to the project folder yesterday — the first
time we've been able to read the *primary* document rather than our own written summary of it. We
went through it line by line against the code we built in July and found nine problems. The most
serious: every response from their server arrives wrapped in a standard outer layer
(`status`/`message`/`data`), and our code expects the contents without the wrapper — so as it stands
**not one of our five API calls would work against the real server**. Our tests didn't catch this
because they were written from the same wrong assumption, so they were only ever checking that we
agreed with ourselves. Second most serious: in test builds we print the server's reply to `/activate`
into the device log, and that reply contains the pump's permanent secret key — the one thing the API
document says in capital letters to store securely and never expose. Nothing has actually leaked
(that step has never been run, and the committed logs are clean), but it had to be caught before the
first real activation, because that key is issued exactly once.

We also settled a money question that had been open for a month: prices go to the backend in **naira**,
not kobo. Their worked example (₦7,000 for 10 litres = ₦700/litre) only makes sense that way. Usefully,
their server rejects any mismatch outright, so a wrong guess here breaks loudly at the till rather than
silently overcharging a customer 100×.

Three of the nine aren't ours to fix: two endpoints we've designed against **don't exist in their API
at all**, and one consequence is sharp — nothing in their system currently tells a pump *which fuel it
sells*, yet their own sale endpoint requires that. That now sits on the critical path for the whole
payment phase.

**Technical notes:**
- **Method:** all 526 lines of the Reference (`pdftotext -layout`) vs `data/network/`,
  `domain/network/`, `di/NetworkModule.kt`. Full write-up with evidence, file:line refs, severities
  and fixes in `docs/journal/API_CONFORMANCE_AUDIT.md`; tracked as TODO #11–#18.
- **Root cause:** the layer was built against `phase7_blocker_resolution.md` (our summary of a v3
  `.docx`). The summary was correct on **endpoint inventory and the signing scheme** — and those parts
  of the code are verified correct. Compression dropped the **response envelope**, the **once-only
  `pumpId`**, and the **`fuelType` requirement**. Every defect is payload-shape or lifecycle-value;
  none is a logic defect.
- **Critical (#11):** `PumpApiService` returns inner DTOs; server wraps everything in
  `{status:Boolean, message, data}`. Top-level `status` is Boolean vs our `String` → type mismatch, and
  all other fields are one level down → missing-field failure. Affects all five calls incl. unsigned
  `/activate`. `PumpApiClientTest` fixtures are unenveloped, so green tests proved nothing.
- **Security (#12):** `HttpLoggingInterceptor.Level.BODY` under `BuildConfig.DEBUG`; `redactHeader()`
  covers headers only, so the `/activate` **body** (`apiKey` + `signingSecret`) prints. `debugRealHw`
  is a debug build and `docs/logcats/` is committed practice. Grepped `docs/logcats/` for
  `signingSecret|apiKey|bal_live|sec_|X-Signature` → **0 hits**; exposure is prospective only.
- **Lifecycle (#13, #16):** `pumpId` is returned once by `/activate`, required in `/authorise` +
  `/upload` bodies, and absent from `PumpCredentials` → unrecoverable without revoke-and-reissue.
  Name collision with `DeviceConfig.pumpId` ("PUMP 1" label vs UUID) flagged for a rename. `deviceId`
  is ours to mint, has no generator, and must be stable forever.
- **Decision — `amount` is NAIRA** (Reference states no unit anywhere; §4.2 example `7000`/`10 L`
  → ₦700/L). App stays kobo internally; the repository mapper owns the ÷100 as the single flip point.
  Server's exact `amount === expectedLitres × stationPricePerUnit` check (`400 Amount mismatch`) makes
  a wrong unit fail closed. **Open:** whether `amount` accepts decimals — integer-only would reject a
  ₦33,166.05 fill-up outright (exact check), constraining pricing to whole naira/L (business call).
- **Verified correct, no action:** `PumpRequestSigner` (HMAC-SHA256 over `timestamp + "." + rawBody`,
  lower-case hex — now confirmed against their Node reference impl), `PumpSigningInterceptor` (signs
  the already-serialised body; honours "do not re-serialize after signing"; `@Unsigned` exempts
  `/activate`), all four headers, upload-only retry, Keystore store, `FuelType` enum.
- **Backend gaps (#18):** `GET /api/pump/config` and `GET /api/pump/transactions/{id}` are **our
  proposals, not their endpoints** — the Reference documents exactly three (`/activate`, `/authorise`,
  `/transactions/upload`), confirmed by its §5 cheat sheet. `/activate` returns credentials only, so
  there is **no source for `fuelType`**, which `/authorise` requires. `PAID` is a real status (§2
  diagram) but missing from the §5 list.
- **Standing lesson:** where a spec exists, build fixtures from its **literal examples**. Had
  `PumpApiClientTest` used the Reference's verbatim JSON, #11 would have been caught in July.

**Next:**
Fix #11 (envelope) first — nothing else is testable until responses parse, and it yields the corrected
fixtures. Then #12 before any real activation. #14/#15/#13/#16 alongside the activation flow. Send the
#18 asks to the boss immediately (longest lead time). Interim for `/config`: build the device-local
operator config screen as 7b's first half behind the existing `DeviceConfigRepository` seam — also the
backend-unreachable fallback, so not throwaway.
