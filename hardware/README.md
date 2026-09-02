# SmartPump pulse adapter — firmware + bench rig (Phase 7a + 7g)

The Arduino sketch in `smartpump_pulse_adapter/` is the **other end** of the Android USB-serial
driver (`UsbSerialConnection` + `SerialFrameParser` + `UsbSerialRelayController`). Flash it to an
Arduino **Uno R3 or Mega 2560**, plug the board into the tablet, and the `debugRealHw` app
dispenses against it.

> **2026-09-02 — the sketch now also carries the Phase 7g EEPROM totaliser**, merged in from the
> separate bench sketch. See [EEPROM totaliser](#eeprom-totaliser-phase-7g) for what it does, and
> for what it deliberately does **not** do yet.

## Protocol (must match the Kotlin side byte-for-byte)

Line-delimited (`\n`), `TYPE:payload*CS`, where **`CS` = XOR-8 of every byte before the `*`**, two
uppercase hex digits.

| Direction | Frame | Meaning |
|---|---|---|
| device → app | `PULSE:<cum>*<cs>` | a fuel pulse; `<cum>` is the adapter's running count |
| device → app | `HB:<cum>*<cs>` | keep-alive, ~2 s when idle |
| device → app | `BOOT:<cum>*<cs>` | sent once at power-up; carries the EEPROM-restored totaliser |
| device → app | `ERR:<code>*<cs>` | a rejected/garbled inbound command, `ERR:WDOG` on a watchdog trip, `ERR:PWR` on a power-fail save |
| app → device | `PING*<cs>` | liveness heartbeat, sent ~every 1 s while the link is up |
| app → device | `RLY:1*<cs>` | energise relay (fuel on) — one-shot edge command |
| app → device | `RLY:0*<cs>` | de-energise relay (fuel off) — one-shot edge command, and the totaliser commit point |

`BOOT`'s payload is **no longer always `0`** — since 7g it carries the restored lifetime totaliser.
This is safe by construction: `PulseAccumulator.onBoot()` adopts whatever it carries as the baseline
and contributes zero fuel.

**Nothing may be printed outside this framing while the app is attached.** `SerialFrameParser`
classifies any unframed line as `SerialFrame.Invalid`, so a stray banner produces a dead link rather
than a visible error. Human-readable output is behind `DEBUG_BANNERS`, default `false`.

### Comms-loss heartbeat watchdog (7a-hardening)

The relay is **fail-closed**, and the adapter — **not** the app — is its safety authority. While
dispensing, the adapter must keep hearing the app's `PING` heartbeat; if none arrives within
`HEARTBEAT_TIMEOUT_MS` (3 s) it presumes the comms are dead (USB data drop, frozen/crashed
controller) and closes the relay on its own GPIO. **Lose comms = stop dispensing** — and the adapter
does *not* need to know `litres_authorised` to do it.

It never re-energises on its own: once tripped, only an explicit `RLY:1` resumes fuel. The app sends
the heartbeat from `UsbSerialConnection` while the port is open; on reconnect `UsbSerialRelayController`
re-asserts `RLY:1` (if still mid-dispense) so a brief USB drop self-heals and the prepaid fill resumes.

In **production** the adapter + relay board are powered from the **UPS**, not the tablet's USB, so the
adapter stays alive to enforce this even when the data link drops. `HEARTBEAT_TIMEOUT_MS` (3 s) is
≈3× the heartbeat period — normal USB latency never false-trips it, uncontrolled flow stays bounded.

Worked checksums (sanity-check your serial monitor against these):

```
PULSE:1        -> 54      HB:0     -> 00      BOOT:0   -> 1C
PULSE:0042817  -> 5D      RLY:1    -> 4C      RLY:0    -> 4D
ERR:WDOG       -> 64      PING     -> 10      ERR:PWR  -> 2A
ERR:NOCS       -> 6E      ERR:CSUM -> 77      ERR:CMD  -> 35
```

> Note: the framing doc's illustrative `PULSE:0042817*7C` is **wrong** — the real XOR-8 is `5D`.
> Flag this to Olonade when confirming the production protocol.

The app takes the **delta** between successive cumulative counts, so a dropped `PULSE` line
self-heals on the next one and the exact send cadence doesn't matter.

## Wiring (Uno R3 / Mega 2560)

| Pin | Role | Notes |
|---|---|---|
| `D7` | Relay control out | to a relay module IN, or an LED + resistor |
| `D13` (`LED_BUILTIN`) | Relay mirror | lights while dispensing — lets you demo with a bare board |
| `D2` | Real flow-meter pulse in | **must be interrupt-capable**; `INPUT_PULLUP`, FALLING edge |
| `D3` | Power-fail sense in | **must be interrupt-capable**; see EEPROM totaliser below |
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

## EEPROM totaliser (Phase 7g)

A single **lifetime** pulse count, wear-levelled across 64 slots of `{pulseCount, sequence, crc}`
(10 bytes each = 640 B, fitting the Uno's 1 KB and the Mega's 4 KB). Recovery scans every slot and
takes the highest `sequence` that still passes its CRC.

**Written only at end-of-dispense (on `RLY:0`, and on a watchdog trip) and on power failure** —
never per pulse. At 50 pps a per-pulse write would burn through the ~100k-cycle endurance within the
hour. It is a **reporting** figure; the Android app remains system of record for litres sold.

`crc` is the last field on purpose. `EEPROM.put()` writes ascending, so the CRC lands last and acts
as a commit marker: a write torn by the power cut it exists to survive leaves a bad CRC, that slot is
rejected, and the previous slot's older-but-valid record wins.

**Power-fail sense (`D3`)** is expected **active-low** — a "power good" signal (e.g. an opto
energised from the incoming rail) holds the pin low, and losing power releases it so the internal
pull-up drags it high. Hence `POWER_FAIL_EDGE = RISING`; flip to `FALLING` if the sense circuit is
inverted. With nothing wired, the pull-up holds it high and the edge never arrives, so an unwired rig
simply never saves on power loss rather than false-triggering. The reservoir capacitor must hold the
rail up for the commit — worst case ~10 × 3.3 ms, usually far less since `EEPROM.put()` skips bytes
that already match.

The ISR order is deliberate: **fuel off first** (a couple of register writes), then the EEPROM
commit, then a best-effort `ERR:PWR`, then halt.

### What this deliberately does NOT do yet

Prototype Specification v1.0 (Software → power-cut transaction recovery) calls for resuming
`max(adapter_eeprom, android_persisted)`. **That is not implementable as written** and is not
attempted here: this totaliser is *lifetime*-scoped while the app's persisted count is
*per-transaction*, so a literal `max()` always returns the lifetime value. It needs a **session
mark** — the app signals session-zero at relay-open, the adapter records the totaliser at that mark,
and recovery reads `lifetime_now − lifetime_at_mark`. That is a protocol change awaiting Olonade
(`OPEN_QUESTIONS` #24), as is the `CAL` frame for the sealed pulses-per-litre constant (#23).

## Config flags (top of the `.ino`)

| Flag | Default | Effect |
|---|---|---|
| `ENABLE_AUTO_PULSE` | `true` | synthesise pulses while the relay is energised (meter-free demo) |
| `ENABLE_BUTTON` | `true` | inject pulses while `D4` is held |
| `RELAY_ACTIVE_LOW` | `false` | set `true` for active-LOW relay boards (LOW = energised) |
| `AUTO_PPS` | `50` | synthetic pulse rate — 50 pps ≈ 30 L/min at 100 pulses/L |
| `PULSE_DEBOUNCE_US` | `250` | ISR debounce for a **real meter**, in microseconds; `0` disables |
| `ENABLE_POWER_FAIL_SAVE` | `true` | arm the `D3` power-fail interrupt |
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
   sends `RLY:1`, the board's `D13` LED lights, synthetic pulses flow, and litres tick up. Ending the
   transaction sends `RLY:0` and the LED goes out.

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
