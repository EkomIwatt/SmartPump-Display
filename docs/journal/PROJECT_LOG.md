# SmartPump Display — Project Log

## Current status — 2026-06-30

Strict-design rebuild is complete and merged to `main`: all 5 flows, the attendant overlay, persistence/boot-resume, kobo money, and Room migrations are in. **Phase 7a (real USB-serial hardware) is merged to `main`** (Arduino pulse driver + relay, bench-verified). `main` is pushed to `origin`.

**Active:** `feature/phase-7a-hardening` — comms-loss heartbeat watchdog (firmware + app) + fixed-flow disconnect pause/resume. Built, build green, **pending a Uno bench re-run before merge**.

**Next:** merge the hardening branch → Phase 8 (CustomerViewModel unit tests, disconnect path first) → unblocked Phase 7 sub-phases (7b operator config / 7e backend sync).

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
