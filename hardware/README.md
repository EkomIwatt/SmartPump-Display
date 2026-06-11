# SmartPump pulse adapter — firmware + bench rig (Phase 7a)

The Arduino sketch in `smartpump_pulse_adapter/` is the **other end** of the Android USB-serial
driver (`UsbSerialConnection` + `SerialFrameParser` + `UsbSerialRelayController`). Flash it to an
Arduino Uno, plug the Uno into the tablet, and the `debugRealHw` app dispenses against it.

## Protocol (must match the Kotlin side byte-for-byte)

Line-delimited (`\n`), `TYPE:payload*CS`, where **`CS` = XOR-8 of every byte before the `*`**, two
uppercase hex digits.

| Direction | Frame | Meaning |
|---|---|---|
| device → app | `PULSE:<cum>*<cs>` | a fuel pulse; `<cum>` is the adapter's free-running count |
| device → app | `HB:<cum>*<cs>` | keep-alive, ~2 s when idle |
| device → app | `BOOT:<cum>*<cs>` | sent once at power-up (count starts at 0) |
| device → app | `ERR:<code>*<cs>` | a rejected/garbled inbound command |
| app → device | `RLY:1*<cs>` | energise relay (fuel on) |
| app → device | `RLY:0*<cs>` | de-energise relay (fuel off) |

Worked checksums (sanity-check your serial monitor against these):

```
PULSE:1        -> 54      HB:0   -> 00      BOOT:0 -> 1C
PULSE:0042817  -> 5D      RLY:1  -> 4C      RLY:0  -> 4D
```

> Note: the framing doc's illustrative `PULSE:0042817*7C` is **wrong** — the real XOR-8 is `5D`.
> Flag this to Olonade when confirming the production protocol.

The app takes the **delta** between successive cumulative counts, so a dropped `PULSE` line
self-heals on the next one and the exact send cadence doesn't matter.

## Wiring (Uno R3)

| Pin | Role | Notes |
|---|---|---|
| `D7` | Relay control out | to a relay module IN, or an LED + resistor |
| `D13` (`LED_BUILTIN`) | Relay mirror | lights while dispensing — lets you demo with a bare Uno |
| `D2` (INT0) | Real flow-meter pulse in | optional; `INPUT_PULLUP`, counts on FALLING edge |
| `D3` | Manual pulse button | optional; momentary button to GND, injects pulses while held |
| USB | Serial + power | to the tablet (OTG) |

Nothing beyond the Uno is required for a working demo — the built-in LED on `D13` shows the relay
state and `ENABLE_AUTO_PULSE` makes litres tick without any meter.

## Config flags (top of the `.ino`)

| Flag | Default | Effect |
|---|---|---|
| `ENABLE_AUTO_PULSE` | `true` | synthesise pulses while the relay is energised (meter-free demo) |
| `ENABLE_BUTTON` | `true` | inject pulses while `D3` is held |
| `RELAY_ACTIVE_LOW` | `false` | set `true` for active-LOW relay boards (LOW = energised) |
| `AUTO_PPS` | `50` | synthetic pulse rate — 50 pps ≈ 30 L/min at 100 pulses/L |

`AUTO_PPS` and the app's `PULSES_PER_LITRE = 100` together set the apparent flow rate. If you wire a
**real** meter, set `ENABLE_AUTO_PULSE = false` so you don't get synthetic pulses on top of it.

## Flashing

Arduino IDE: open `smartpump_pulse_adapter/smartpump_pulse_adapter.ino`, select **Arduino Uno** +
the right COM port, Upload. Or with `arduino-cli`:

```
arduino-cli compile -b arduino:avr:uno smartpump_pulse_adapter
arduino-cli upload  -b arduino:avr:uno -p COM5 smartpump_pulse_adapter
```

Open Serial Monitor at **115200 baud** to watch the frames (`BOOT:0*1C`, then `HB:0*00` every ~2 s).

## Running against the app

1. Build/install the **`debugRealHw`** variant (Android Studio Build Variants → `debugRealHw`, or
   `./gradlew :app:installDebugRealHw`). It installs **alongside** the mock `debug` app (different
   icon/app id `…​.realhw`).
2. Plug the Uno into the tablet (OTG). Android shows *"Open SmartPump for this USB device?"* — tick
   **always** and OK. (Persistent grant via the manifest USB attach filter.)
3. Run a **Cash Fixed** or **Fill-up** transaction. Authorise from the attendant panel → the app
   sends `RLY:1`, the Uno's `D13` LED lights, synthetic pulses flow, and litres tick up. Ending the
   transaction sends `RLY:0` and the LED goes out.

## Bench checklist (tomorrow AM)

- [ ] Sketch flashes; Serial Monitor @115200 shows `BOOT:0*1C` then `HB` every ~2 s.
- [ ] Plug into tablet → USB permission dialog appears → grant.
- [ ] Authorise a dispense → `D13` LED on, litres count up smoothly.
- [ ] Litres rate looks right (~30 L/min at `AUTO_PPS = 50`); tune `AUTO_PPS` if needed.
- [ ] End/complete the dispense → LED off (relay `RLY:0` received).
- [ ] Unplug mid-idle → app shows a disconnect; replug → reconnects (attach filter).
- [ ] Mock `debug` app still on the tablet as the safety-net demo.
