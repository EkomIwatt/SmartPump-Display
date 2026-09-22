# 11f — bench gate run sheet (Uno rig)

_Written 2026-09-22 for the Phase 11 bench gate. Steps are `PHASE_11_PLAN.md` §11f, made concrete
for **this** rig: Uno, **auto-pulse**, **external power**. Fill in the Result column as you go;
the trace on the tablet is the evidence, this sheet is the index into it. Move to `closed/` once
11f is logged._

## The app build — already on the tablet

`bench/11f-trace` (`c05e54b`) = the Phase 11 branch + a throwaway trace, installed as the
`…display.realhw` app (Room v6, migrated cleanly on the tablet). **Never merged.** Mock payments
(inherited from `debug`), so the pre-pay in step 2 costs nothing.

**Where to read the trace:** swipe up → PIN → operator screen → **Fuel log** at the bottom. Cyan
monospace rows, **newest first**, last 80. What the rows mean:

| Row | Meaning |
|---|---|
| `OUT RLY:1:<limit>:<tag>*cs` | the app armed a sale; `<limit>` in pulses (100 / L) |
| `OUT RES:<tag>` / `OUT SES?` | resume / ask the board what it holds |
| `OUT RLY:0` | the app turned the relay off |
| `IN  ARM tag=… start=…` | the board's latched session start — **the #36 number** |
| `IN  STOP tag=… cut=…` | the board cut at its limit; `cut − start` must equal the limit |
| `IN  HB cum=…` | idle keep-alive, logged only when the count **moved** |
| `IN  BOOT cum=…` / `IN  ERR:…` | board restarted / refused or watchdog |
| `LINK UP` / `LINK DOWN` | USB link |
| `VM RESUME …` | what the app decided on relaunch (`held=tag … base …` = resumed from the board) |
| `VM STOP …` / `VM BACKSTOP …` | which cut ended the sale in the app. Either is fine (spec §5 — a race); what matters is `STOP`'s `cut − start` |

Afterwards, plug the tablet into the PC and tell me — I pull the DB and read every row, so you do
not have to transcribe anything the screen shows.

## Laptop link — adb over Wi-Fi (set up 2026-09-22)

The tablet's USB-C port belongs to the Uno during the run, so adb goes over Wi-Fi. **The address
changes with the network** — it was `192.168.100.42:5555`, and after the tablet moved networks it
is `10.48.35.57:39517`. Always read the current one out of `adb devices`; if it is gone, re-plug
the tablet into the laptop, `adb tcpip 5555`, then `adb connect <tablet ip>:5555`.

```
# live trace — leave running in its own terminal (logcat IS usable this time)
adb -s 10.48.35.57:39517 logcat -s BenchTrace

# the instant app kill for steps 3 and 4
adb -s 10.48.35.57:39517 shell am force-stop app.balancee.smartpump.display.realhw
```

Force-stop kills the process, so no PING → the board's 3 s watchdog; relaunch from the launcher.

## Flash the Uno — bench settings, do NOT commit them

In `hardware/smartpump_pulse_adapter/smartpump_pulse_adapter.ino`:

- `ENABLE_AUTO_PULSE = true` (shipped `false`, meter config) — 50 pps = 0.5 L/s while the relay is open
- `ENABLE_BUTTON = false`, `ENABLE_POWER_FAIL_SAVE = false`, `DEBUG_BANNERS = false` — as shipped

Board **Arduino Uno**, upload, then `git checkout -- hardware/` so the meter config is what stays in
the repo. Pin 2 still tied to 5 V. Uno on **external power** (barrel jack / VIN) *and* USB to the
tablet. The first boot of the new EEPROM layout may report `BOOT cum=0` once — expected.

> This firmware refuses a pre-Phase-11 app (no fuel). Don't point the mock `debug` app or an old
> `realhw` build at this Uno.

Sizes below assume 100 pulses/L and 0.5 L/s. Enter litres directly with the ₦/L toggle on the
amount step.

## Steps

| # | Do | Pass when | Result |
|---|---|---|---|
| 1 | **7g totaliser.** Note the first `IN BOOT cum=A`. Cash-fixed **2 L**. Note `STOP cut=B`. Pull **both** external power and USB, wait 5 s, restore. Note the new `BOOT cum=C`. Then cash-fixed **1 L**. | `C ≥ B` (not 0). The 1 L sale starts at **0.00 L** on screen and ends at 1.00. | |
| 2 | **Board-owned stop.** Cash-fixed **2 L**, then a pre-pay (mock) for about 2 L. | Each: `OUT RLY:1:<limit>:…` then `ARM start=S`, `STOP cut=X` with **`X − S = limit`**; screen shows the authorised litres. | |
| 3a | **App killed, limit first.** Cash-fixed **2 L**; at ~1.3 L on screen, **force-stop from the laptop** (Laptop link). Relaunch. | LED off at 2 L **by `STOP`** (0.7 L left ≈ 1.4 s; the watchdog needs 2–3 s after the last PING). On relaunch `VM RESUME … held=…`, the sale completes at 2.00 L. `cut − start = 200`. | |
| 3b | **App killed, watchdog first.** Cash-fixed **10 L**; force-stop from the laptop at ~2 L. Wait ~10 s. Relaunch. | LED off ~3 s after the kill (`ERR:WDOG`, board side). On relaunch it resumes (`RES` → `ARM` with the **same start**) and still ends on `STOP` with `cut − start = 1000`. Compare with 7h's ~1.5 L given away: here it is **counted**, not lost. | |
| 4 | **#36 — offset stays flat.** Cash-fixed **10 L**; force-stop (laptop) + relaunch at ~2, ~4 and ~6 L. | Every `ARM` for that tag carries the **same `start`**, and the final `VM STOP stopped=` equals `cut − start` (1000). 7h drifted 2389 → 2456 over the same exercise. | |
| 5 | **USB unplug / replug.** Cash-fixed **10 L**; at ~3 L unplug the USB from the **tablet** (Uno stays up on external power), wait ~5 s, replug. | `LINK DOWN`, then `LINK UP`, `OUT RES:<tag>` (or same-tag `RLY:1`), `ARM` with the **same start**, then `STOP` at the **original** limit (`cut − start = 1000`). No fresh allowance. | |
| 6 | **Bad frames** — Uno on the **laptop** Serial Monitor, 115200, line ending *Newline*. Send `RLY:1*4C` (bare), then `RLY:1:0:5*49` (limit 0). Control: `RLY:1:500:5*4C`, then `RLY:0*4D`. | Both bad ones → `ERR:CMD*35`, **D13 stays dark**. Control → `ARM:5:<n>`, D13 on (it drops by itself ~3 s later with `ERR:WDOG` — nothing is PINGing; expected). | |
| 7 | **Fill-up — ceiling, not nozzle idle.** Auto-pulse never goes idle, so a fill-up here runs to the **200 L** ceiling: ~6 min 40 s. Start one and leave it. | Ends on `STOP` with `cut − start = 20000`, `VM STOP fill-up CEILING`, and a gold **"Fill-up hit the safety limit"** row. **Nozzle-idle is deferred to Friday** (the Mega, real signal). | |
| 8 | **Coast.** After each `STOP`, the next `IN HB cum=` minus `cut`. | Expect **0** here: synthetic pulses stop the instant the relay drops. The real figure needs a meter — Friday. | |

## Friday (Olonade's Mega) — not today

Power-sense on pin 3 goes high on power loss; hold-up ≥ ~85 ms; flip `ENABLE_POWER_FAIL_SAVE`;
power-cut mid-sale → exact count, original limit; sense line disconnected → session discarded.
Plus what the Uno could not show: **nozzle-idle** (step 7) and **coast** (step 8) on a real signal.
Auto-pulse **off** there — meter config.
