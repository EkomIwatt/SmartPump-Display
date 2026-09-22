# SmartPump pulse adapter — firmware + bench rig (Phase 7a + 7g + 11)

The Arduino sketch in `smartpump_pulse_adapter/` is the **other end** of the Android USB-serial
driver (`UsbSerialConnection` + `SerialFrameParser` + `UsbSerialRelayController`). Flash it to an
Arduino **Uno R3 or Mega 2560**, plug the board into the tablet, and the `debugRealHw` app
dispenses against it.

> **Phase 11 (2026-09-22) — the adapter owns the cutoff.** The app sends a pulse limit with the
> relay-open command and the board stops the relay itself, in the pulse ISR, and keeps a per-sale
> session. **The protocol is specified in [`docs/serial-protocol.md`](../docs/serial-protocol.md)**
> — that document is the authority; the summary below is for the bench.
>
> ⚠️ **This firmware needs the Phase 11 app (11c–11e).** An app from before Phase 11 sends a bare
> `RLY:1`, which this firmware refuses with `ERR:CMD` — **no fuel**. That is the designed failure
> (fail to no fuel), but it means a rig flashed with this sketch will not dispense from a
> pre-Phase-11 build.
>
> **Two rigs:** the Uno on the user's bench simulates the firmware and has no power-sense circuit;
> Olonade's Mega has the circuit. `ENABLE_POWER_FAIL_SAVE` stays `false` except on the Mega.

## Protocol (must match the Kotlin side byte-for-byte)

Line-delimited (`\n`), `<body>*<cs>`, where **`cs` = XOR-8 of every byte before the `*`**, two
uppercase hex digits. Full detail, state machine and edge cases:
[`docs/serial-protocol.md`](../docs/serial-protocol.md).

| Direction | Frame | Meaning |
|---|---|---|
| app → device | `PING` | liveness, ~1 s — feeds the watchdog |
| app → device | `RLY:1:<limit>:<tag>` | arm a session of `<limit>` pulses and open the relay. Same tag as the held session = `RES`. Bare `RLY:1` → `ERR:CMD`, no fuel |
| app → device | `RLY:0` | relay off; an open session is **held**, not ended |
| app → device | `RES:<tag>` | resume the held session under its original limit |
| app → device | `SES?` | report the session, change nothing |
| device → app | `PULSE:<count>` / `HB:<count>` | lifetime count (throttled / ~2 s idle) |
| device → app | `BOOT:<count>` | once at power-up; the EEPROM-restored count |
| device → app | `ARM:<tag>:<start>` | session open or held; sale pulses = `count − start` |
| device → app | `STOP:<tag>:<cut>` | the board cut the relay at the limit; `cut = start + limit` |
| device → app | `ERR:<code>` | `CMD` `NOSESSION` `CSUM` `NOCS` `WDOG` `PWR` |

**Nothing may be printed outside this framing while the app is attached.** `SerialFrameParser`
classifies any unframed line as `SerialFrame.Invalid`, so a stray banner produces a dead link rather
than a visible error. Human-readable output is behind `DEBUG_BANNERS`, default `false`.

**Watch it on a bare Serial Monitor** (115200, newline): type `RLY:1:500:7*4E` and the board
answers `ARM:7:<count>*<cs>` and lights `D13`; with pin 2 pulsing, it goes dark on its own after 500
pulses and prints `STOP:7:<count>*<cs>`. Without `PING`s it trips the watchdog after 3 s — type
`RES:7*49` to resume.

### Comms-loss heartbeat watchdog (7a-hardening)

The relay is **fail-closed**, and the adapter — **not** the app — is its safety authority. While
dispensing, the adapter must keep hearing the app's `PING` heartbeat; if none arrives within
`HEARTBEAT_TIMEOUT_MS` (3 s) it presumes the comms are dead (USB data drop, frozen/crashed
controller) and closes the relay on its own GPIO. **Lose comms = stop dispensing** — and the adapter
does *not* need to know `litres_authorised` to do it.

It never re-energises on its own: once tripped, the session is **held**, and only a `RES:<tag>`
from the app resumes it — **under the limit it already had**, so a resume can never hand out a
fresh allowance (Phase 11; before it, the app re-sent a bare `RLY:1`).

In **production** the adapter + relay board are powered from the **UPS**, not the tablet's USB, so the
adapter stays alive to enforce this even when the data link drops. `HEARTBEAT_TIMEOUT_MS` (3 s) is
≈3× the heartbeat period — normal USB latency never false-trips it, uncontrolled flow stays bounded.

Worked checksums (sanity-check your serial monitor against these):

```
PING            -> 10     RLY:0          -> 4D     SES?          -> 7A
RLY:1:500:7     -> 4E     RES:7          -> 49     ARM:7:12000   -> 5A
STOP:7:12500    -> 19     ERR:CMD        -> 35     ERR:NOSESSION -> 20
PULSE:1         -> 54     HB:0           -> 00     BOOT:0        -> 1C
ERR:WDOG        -> 64     ERR:PWR        -> 2A     ERR:CSUM      -> 77
ERR:NOCS        -> 6E     PULSE:0042817  -> 5D
```

> Note: the original framing doc's illustrative `PULSE:0042817*7C` is **wrong** — the real XOR-8 is
> `5D`.

## Wiring (Uno R3 / Mega 2560)

| Pin | Role | Notes |
|---|---|---|
| `D7` | Relay control out | to a relay module IN, or an LED + resistor |
| `D13` (`LED_BUILTIN`) | Relay mirror | lights while dispensing — lets you demo with a bare board |
| `D2` | Real flow-meter pulse in | **must be interrupt-capable**; `INPUT_PULLUP`, FALLING edge |
| `D3` | Power-fail sense in | only when `ENABLE_POWER_FAIL_SAVE` is on (default **off**) — otherwise unused. **Do not leave a button here** |
| `D4` | Manual pulse button | optional; momentary button to GND, injects pulses while held |
| USB | Serial + power | to the tablet (OTG) |

Nothing beyond the board is required for a working demo — the built-in LED on `D13` shows the relay
state and `ENABLE_AUTO_PULSE` makes litres tick without any meter.

> ### ⚠️ Only `D2` and `D3` work for the two interrupt inputs
>
> An Uno has external interrupts on **pins 2 and 3 only**; a Mega 2560 on **2, 3, 18, 19, 20, 21**.
> Pins 2 and 3 are the only pair valid on both boards, which is why the manual button moved to `D4`
> — it is polled and never needed an interrupt.
>
> This bites silently. `digitalPinToInterrupt()` returns `NOT_AN_INTERRUPT` (`-1`) for a
> non-interrupt pin; `attachInterrupt()` takes a `uint8_t`, so `-1` arrives as `255`, fails its
> `< EXTERNAL_NUM_INTERRUPTS` guard and **does nothing at all** — no compile error, no warning, no
> counting. The earlier bench sketch used pins **7 and 5**; verified against AVR core 1.8.7, those
> are interrupt-capable on **neither** Uno nor Mega, so it counted nothing on either board.

## EEPROM record (Phase 7g, extended in Phase 11)

The lifetime pulse count **plus the sale's session**, wear-levelled across **40 slots** of
`{magic, pulseCount, sequence, tag, start, limit, state, crc}` (25 bytes each = 1000 B, fitting the
Uno's 1 KB and the Mega's 4 KB). Recovery scans every slot and takes the highest `sequence` that
carries the right format marker **and** still passes its CRC.

`magic` (`SLOT_MAGIC`, now **`0x5351`**; 7g's 12-byte record was `0x5350`) exists because a CRC-16
alone is a bet, not a check: foreign bytes pass it roughly 1 time in 65,536. **Bump `SLOT_MAGIC`
whenever the struct changes**, so an older layout is rejected instead of misread.

> ⚠️ **The Phase 11 bump resets a board's totaliser to zero once**, on the first boot of this
> firmware (a 7g record is rejected as foreign). Harmless on bench boards — do it **before** the
> 14-day run, never during it.

To wipe an adapter's record, flash `hardware/eeprom_erase/` once (LED goes solid when it has erased
*and verified*), then flash the adapter sketch back. Do not do this on a deployed adapter — the
totaliser is the pump's lifetime count and is meant to reconcile against station stock records.

**When it is written** — never per pulse (at 50 pps that would wear the EEPROM out within the hour):

- **Flag off:** at end of dispense (`RLY:0`, watchdog trip, `STOP`), and only if the count moved —
  7g's rule. About one write per sale.
- **Flag on:** also every time the relay changes state for a session (arm, `RES`), so the newest
  record always says whether the relay was on. About two writes per sale; 40 slots × ~100 000
  cycles ÷ 2 ≈ **2 million sales**.

`crc` is the last field on purpose. `EEPROM.put()` writes ascending, so the CRC lands last and acts
as a commit marker: a torn write leaves a bad CRC, that slot is rejected, and the previous record
wins.

### The session across a reboot (spec §6.4)

- **Flag off:** a reboot always ends the session. The app gets `ERR:NOSESSION` and re-arms with what
  is left of the limit, from its own count.
- **Flag on:** the session comes back **only if the newest record was written with the relay off**
  (`HELD` or `DONE`) — then no metered fuel could have flowed after it and its count is current. A
  record written as the relay came **on** (`OPEN`) means fuel flowed afterwards and the power-fail
  save never landed; its count is stale, so the session is **discarded**. Resuming it would re-grant
  every pulse the board forgot. A failed power-fail save therefore degrades to the flag-off
  behaviour, never to over-dispensing.

**Power-fail sense (`D3`)** is expected **active-low** — a "power good" signal holds the pin low,
and losing power releases it so the internal pull-up drags it high. Hence `POWER_FAIL_EDGE =
RISING`; flip to `FALLING` if the sense circuit is inverted. **Confirm both against Olonade's
circuit before switching the flag on.** The reservoir capacitor must hold the rail up for the
commit: up to **25 bytes × ~3.4 ms ≈ 85 ms** worst case (7g's 12-byte record needed ~40 ms), usually
far less since `EEPROM.put()` skips bytes that already match.

The ISR order is deliberate: **fuel off first**, then the session marked held, then the EEPROM
commit, then a best-effort `ERR:PWR`, then halt.

### Still not here

The `CAL` frame for the sealed pulses-per-litre constant (`OPEN_QUESTIONS` #23). The limit is in
**pulses** precisely so this firmware never needs the K-factor.

## Config flags (top of the `.ino`)

| Flag | Default | Effect |
|---|---|---|
| `ENABLE_AUTO_PULSE` | `false` | synthesise pulses while the relay is energised (meter-free demo). Off since the 2026-09-02 meter config |
| `ENABLE_BUTTON` | `false` | inject pulses while `D4` is held. Off since the 2026-09-02 meter config |
| `RELAY_ACTIVE_LOW` | `false` | set `true` for active-LOW relay boards (LOW = energised) |
| `AUTO_PPS` | `50` | synthetic pulse rate — 50 pps ≈ 30 L/min at 100 pulses/L |
| `PULSE_DEBOUNCE_US` | `250` | ISR debounce for a **real meter**, in microseconds; `0` disables |
| `ENABLE_POWER_FAIL_SAVE` | `false` | arm the `D3` power-fail interrupt **and** let the session survive a reboot — **on only on Olonade's Mega**, which has the sense circuit |
| `POWER_FAIL_EDGE` | `RISING` | edge meaning "power lost" (active-low sense line) |
| `DEBUG_BANNERS` | `false` | unframed Serial Monitor banners — **must stay false with the app attached** |

`AUTO_PPS` and the app's `PULSES_PER_LITRE` together set the apparent flow rate. If you wire a
**real** meter, set `ENABLE_AUTO_PULSE = false` so you don't get synthetic pulses on top of it.

> ### ⚠️ `PULSE_DEBOUNCE_US` caps how fast you can count
>
> ```
> max_pulses_per_sec = 1e6 / PULSE_DEBOUNCE_US
> max_litres_per_min = max_pulses_per_sec × 60 / pulses_per_litre
> ```
>
> At the default 250 µs that is 4000 pps ≈ 2400 L/min at 100 pulses/L — far above any dispenser,
> while still swallowing contact ringing.
>
> **This is not the 150 ms used for the bench pushbutton.** 150 ms caps counting at 6.67 pps, about
> **4 L/min** against a real dispenser's 30–50 — and because the loss is flow-rate dependent, a
> K-factor derived through it is not a constant at all. **Calibration task T-01 (5 × 10 L, ±0.5%)
> is invalid if run with a debounce anywhere near that**, and it fails quietly: five runs that agree
> with each other and are all wrong. The button needs no debounce — it is polled and injects at
> `AUTO_PPS` while held, never through the ISR.

## Flashing

Arduino IDE: open `smartpump_pulse_adapter/smartpump_pulse_adapter.ino`, select **Arduino Uno** or
**Arduino Mega or Mega 2560** + the right COM port, Upload. Or with `arduino-cli`:

```
arduino-cli compile -b arduino:avr:mega smartpump_pulse_adapter     # Mega 2560
arduino-cli upload  -b arduino:avr:mega -p COM5 smartpump_pulse_adapter

arduino-cli compile -b arduino:avr:uno  smartpump_pulse_adapter     # Uno
```

The board is the **only** difference between the two targets — no source change. Nothing changes on
the Android side either: `usb_device_filter.xml` matches Arduino by vendor id with no product id,
and the Mega 2560 R3 is in the default CDC probe table.

Open Serial Monitor at **115200 baud** to watch the frames (`BOOT:0*1C`, then `HB:0*00` every ~2 s).

## Running against the app

1. Build/install the **`debugRealHw`** variant (Android Studio Build Variants → `debugRealHw`, or
   `./gradlew :app:installDebugRealHw`). It installs **alongside** the mock `debug` app (different
   icon/app id `…​.realhw`).
2. Plug the board into the tablet (OTG). Android shows *"Open SmartPump for this USB device?"* — tick
   **always** and OK. (Persistent grant via the manifest USB attach filter.)
3. Run a **Cash Fixed** or **Fill-up** transaction. Authorise from the attendant panel → the app
   sends `RLY:1:<limit>:<tag>`, the board answers `ARM`, `D13` lights, and litres tick up as pulses
   arrive. A fixed sale goes dark on its own at the limit (`STOP`); a fill-up ends on nozzle idle
   (`RLY:0`). **Needs a Phase 11 app build** — see the note at the top.

## Host test (no board needed)

`host_test/run.sh` compiles the **real** sketch against fake Arduino / Serial / EEPROM stubs, drives
the protocol (bad frames, the cutoff, retries, the watchdog, reboots with the flag off and on, a
power fail, the ring wrapping, a 7g record) and diffs against `host_test/expected.txt`. It proves the
logic, not the hardware. Needs `g++`, or `pip install ziglang` and `CXX="python -m ziglang c++"`.
Run it after any change to the sketch.

## Bench checklist

- [ ] Sketch flashes; Serial Monitor @115200 shows `BOOT:<n>*<cs>` then `HB` every ~2 s.
- [ ] Plug into tablet → USB permission dialog appears → grant.
- [ ] Authorise a dispense → `D13` LED on, litres count up smoothly.
- [ ] Litres rate looks right (~30 L/min at `AUTO_PPS = 50`); tune `AUTO_PPS` if needed.
- [ ] End/complete the dispense → LED off (relay `RLY:0` received).
- [ ] Unplug mid-idle → app shows a disconnect; replug → reconnects (attach filter).
- [ ] Mock `debug` app still on the tablet as the safety-net demo.

### Comms-loss heartbeat watchdog (7a-hardening)

- [ ] Serial Monitor shows `PING*10` arriving ~every 1 s once the app is connected.
- [ ] **PRIMARY — app-death watchdog (cable stays attached):** authorise a dispense, then mid-flow
      `adb shell am force-stop app.balancee.smartpump.display` (or pause the app in the debugger).
      USB VBUS keeps the board powered — the host supplies 5 V regardless of which app runs — so the
      board stays alive and the heartbeat stops. → `D13` LED off within ~3 s + Serial Monitor shows
      `ERR:WDOG*64`. **This is the safety case the deployment relies on** (fixed USB cable in the
      kiosk means the real hazard is a frozen/crashed app while fuel flows, not a pulled cable).
- [ ] Secondary/sanity — **unplug mid-flow** → `D13` off. NOTE: on a bus-powered bench board,
      unplugging cuts the board's *power* too, so this mostly proves the relay fails open on power
      loss, not that the watchdog fired. In production the adapter is UPS-powered and the app-death
      test above is what exercises the watchdog proper.
- [ ] Low-priority — replug → app reconnects, `UsbSerialRelayController` re-asserts `RLY:1`, LED
      relights, litres continue (not from zero). The app-side pause/resume *screen* was removed
      2026-07-08 (fixed-cable assumption); only the relay-layer re-assert remains for a rare transient.

### EEPROM totaliser (7g) — NOT YET RUN

- [ ] Note the count in `BOOT:<n>` at power-up. Run a dispense, complete it (`RLY:0`), then
      power-cycle the board → the new `BOOT:<n>` should carry the **higher** post-dispense count,
      not `0`.
- [ ] App-side sanity after that reboot: litres must start from **zero** for the next sale, not from
      the totaliser (`PulseAccumulator.onBoot()` adopts it as a baseline). This is the one that
      would show up as a customer being billed for the pump's whole service life.
- [ ] Virgin/erased EEPROM → `BOOT:0` and no crash in `recoverLatestState()`.
- [ ] Torn-write rejection: interrupt power *during* a save and confirm the previous record wins
      (needs the power-fail rig; until then this is covered only by the CRC design, not by test).
- [ ] `ERR:PWR*2A` appears on the Serial Monitor when the power-fail line trips — requires the sense
      circuit; unwired, the pull-up holds `D3` high and it never fires.

### Phase 11 — the adapter owns the cutoff (the 11f gate; `PHASE_11_PLAN.md` has the full list)

- [ ] Fixed pre-pay ends on the board's `STOP`; litres = the limit.
- [ ] App killed mid-sale: fuel stops at the limit or the watchdog, whichever is first — never
      beyond the limit. On restart the sale shows its exact count.
- [ ] #36: one sale, three restarts — the board-minus-app offset stays flat.
- [ ] USB unplug/replug mid-sale: resumes (`RES`), still stops at the **original** limit.
- [ ] Bare / malformed `RLY:1` → `ERR:CMD`, no fuel.
- [ ] Fill-up still ends on nozzle idle; the ceiling is not hit.
- [ ] Coast: after each `STOP`, the settled `HB` count minus `cut`.
- [ ] **Olonade's Mega, flag on:** power cut mid-sale → the sale comes back with its exact count
      and stops at its original limit; power cut with the sense line disconnected → the session is
      **discarded**, not resumed.
