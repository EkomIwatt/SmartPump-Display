# Friday on Olonade's Mega — run sheet

_Written 2026-09-24 for Friday 2026-09-25, the last hardware session before the 14-day run. It
finishes what the Uno could not ([`11F_RUN_SHEET.md`](11F_RUN_SHEET.md), step 5 and the "Friday"
note) and turns on power-fail save. Steps come from `PHASE_11_PLAN.md` §11f and
`docs/serial-protocol.md` §6.4–6.5. Fill in the Result column as you go. The evidence is the trace
on the tablet; this sheet is just the index into it. Move to `closed/` once it is logged._

**What "done" looks like tonight:** steps 1–4 pass (plus 5 and 6 if the rig can run them) → Phase 11 (app + firmware + 7g's totaliser)
merges to `main`. If step 3 or 4 fails, **flash the firmware with the flag off** before you leave.
The flag-off build is the one already proven on the Uno.

---

## Before you leave home

- [ ] **Ask Olonade two things, by message, before you go:**
  1. **What drives D2 on his rig?** It could be a real meter with liquid through it, a signal
     generator, or a button. This decides steps 5 and 6 (table below).
  2. **What is the capacitor on the sense circuit, and what does the Mega draw?** This is for the
     hold-up check (step 1c).
- [ ] **Install the trace build on the tablet.** Branch `bench/11f-trace` is now `b2b8dd2`. It is
  the Phase 11 branch **including the #53 and #54 fixes**, plus the same throwaway trace as Monday.
  **Never merge it.**
  ```
  git checkout bench/11f-trace
  ./gradlew :app:installDebugRealHw        # JAVA_HOME = Android Studio's jbr
  git checkout feature/phase-11-adapter-cutoff
  ```
  Installs over Monday's `…display.realhw` (same Room v6, no migration). Mock payments, so step 3's
  pre-pay costs nothing.
- [ ] **Pack a mains DC adapter for the Mega (7–12 V, ≥1 A).** Never a PP3 (#56). Pack a
  multimeter too. A scope is welcome if Olonade has one.
- [ ] Laptop with the repo, Arduino IDE (board = **Arduino Mega or Mega 2560**), and the tablet
  already paired for adb over Wi-Fi (below).

### What drives D2 decides the firmware config

| Olonade's D2 source | `ENABLE_AUTO_PULSE` | `ENABLE_BUTTON` | Step 5 (nozzle idle) | Step 6 (coast) |
|---|---|---|---|---|
| **Real meter + liquid through a solenoid** (best) | `false` | `false` | close the nozzle / valve | **real figure** |
| Signal generator switched by the relay | `false` | `false` | switch the generator off | ≈ 0, not the real figure |
| Button on D4, nothing else | `false` | `true` | release the button | not measurable |
| Nothing (auto-pulse only) | `true` | `false` | **cannot run**, same as the Uno | not measurable |

The app still uses the placeholder **100 pulses/L** (the K-factor is #22). So with a real meter,
**the litres on the screen are wrong**. That does not matter tonight. Every pass condition below is
in **pulses** (`cut − start`), which the K-factor does not touch.

---

## Laptop link: adb over Wi-Fi

The tablet's USB-C port belongs to the Mega during the run. At Olonade's the network is new, so
the address will be too:

```
# once, with the tablet plugged into the laptop:
adb tcpip 5555
# unplug, then (tablet IP from Settings → About → Status):
adb connect <tablet-ip>:5555
adb devices                                   # use this exact address below

# live trace — leave running in its own terminal
adb -s <addr> logcat -s BenchTrace

# the instant app kill
adb -s <addr> shell am force-stop app.balancee.smartpump.display.realhw
```

If there's no shared Wi-Fi, a phone hotspot works for both laptop and tablet. **Do not open the
Arduino IDE's Serial Monitor on the Mega mid-run.** It resets the board (DTR) and kills the session.

**Reading the trace:** it's the same as Monday. Swipe up → PIN → operator screen → **Fuel log**,
newest first. The rows table is in [`11F_RUN_SHEET.md`](11F_RUN_SHEET.md#the-app-build--already-on-the-tablet).
One addition: `IN  ERR:PWR` means the board saw the power fail and saved before it died. You'll
usually **not** see it, because the link goes down first; that is expected.

---

## Flash the Mega

In `hardware/smartpump_pulse_adapter/smartpump_pulse_adapter.ino` (branch
`feature/phase-11-adapter-cutoff`):

- `ENABLE_AUTO_PULSE` / `ENABLE_BUTTON`: set per the table above.
- `ENABLE_POWER_FAIL_SAVE = true`. It has to be on for step 1: with it off the firmware never
  enables D3's pull-up, so the sense reading would mean nothing. It's safe to flash on: a false
  trigger only turns the relay off, saves and halts.
- `DEBUG_BANNERS = false`.
- `POWER_FAIL_EDGE = RISING`, unless step 1a says otherwise.

Upload, then unplug the laptop and plug the Mega into the tablet. **Mega on the mains DC adapter
_and_ USB to the tablet.** The first boot of the Phase 11 EEPROM layout reads the old totaliser as
absent and reports `BOOT cum=0` **once**. That is expected and it's why this happens now, not
during the run.

**Keep the flag change uncommitted until step 4 passes.** Then commit it. That's the one config
change that ships (see "After"). The D2-source flags are bench settings. **Don't commit those.**

---

## Steps

| # | Do | Pass when | Result |
|---|---|---|---|
| 1a | **Sense line: pin and polarity.** Mega on the DC adapter only (no USB, so it really loses power when you pull it). Multimeter from **D3** to GND with power good. Then pull the DC adapter and watch `L` (the on-board LED). | **LOW (≈0 V) with power good, and `L` lights briefly as the power goes** (it may be faint; a scope on pin 13 is better). That's `onPowerFail` running, so the edge and pin are right. If it's **HIGH with power good** the sense is inverted: set `POWER_FAIL_EDGE = FALLING` and ask Olonade before going on. If the line isn't on D3 at all, stop: the pin is fixed (the Mega's interrupt pins that match the Uno are 2 and 3). | |
| 1b | **Where does the sense tap?** Ask Olonade (or trace it): does the sense watch the **DC jack input**, or the **5 V rail**? | Note the answer; it decides how you cut power in steps 3–4 (see "Cutting power" below). | |
| 1c | **Hold-up ≥ ~85 ms.** The power-fail commit is up to 25 EEPROM bytes at ~3.4 ms each. Scope: time from the D3 edge to the 5 V rail falling below ~4.5 V. No scope: `t ≈ C × ΔV / I` (e.g. 2 200 µF × 2 V ÷ 60 mA ≈ 73 ms, which is *short*). | **≥ 85 ms**, or at least ≥ 40 ms with steps 3–4 passing repeatedly. The usual commit is only the changed bytes (count, state, sequence, crc), far less than the worst case, so a pass on 3–4 is real evidence but not proof of the worst case. Record the figure. | |
| 2 | **Smoke test with the flag on.** Mega on DC adapter + USB to the tablet. Cash-fixed **2 L** (real meter: run liquid until it stops). | `OUT RLY:1:200:<tag>` → `ARM start=S` → `STOP cut=X`, **`X − S = 200`**. If `L` (the on-board LED) lights and the board goes silent *without* a power cut, the sense is false-triggering: flag back off, stop. | |
| 3 | **Power cut mid-sale → resumes under the original limit.** Cash-fixed **10 L**. Note `ARM start=S`. At ~3 L on screen, **cut the Mega's power** (see below). Wait 5 s, restore. | After restore: `BOOT cum=B` with **B ≥ the last count the app saw** (never lower). Then `OUT RES:<tag>` → `ARM` with **the same start S**, the sale continues, and it ends on `STOP cut=X` with **`X − S = 1000`**. No `ADAPTER_SESSION_LOST` row. Run it **three times**; one pass isn't enough. | |
| 3b | **USB unplug / replug only (Monday's step 5, as written).** Cash-fixed **10 L**; at ~3 L unplug USB **from the tablet** (Mega stays up on the mains adapter), wait ~5 s, replug. | `LINK DOWN` → `LINK UP` → `OUT RES:<tag>` (or a same-tag `RLY:1`) → `ARM` **same start** → `STOP` with `cut − start = 1000`. The watchdog will have held the session (`ERR:WDOG` may show). No fresh allowance. | |
| 4 | **Sense disconnected → session discarded, not resumed.** **Power the Mega off first**, then disconnect the sense wire from D3 (pulling it while powered is itself a rising edge and fires the save). Power up, cash-fixed **10 L**, cut power at ~3 L as in step 3, restore. | The board boots with **no** session: `RES` → `ERR:NOSESSION`, and the app records `ADAPTER_SESSION_LOST` for what flowed uncounted. The sale still never exceeds its limit in total. That's the same shape as Monday's step 5 result, and it's the **correct** answer here (spec §6.4: an OPEN record means the count is stale). **Reconnect the sense wire, with the board powered off.** | |
| 5 | **Nozzle idle.** Needs a D2 source you can stop (table above). Start a **fill-up**, flow ~2 L, then stop the flow. | ~3 s after the last pulse: `VM NOZZLE IDLE fill-up salePulses=…`, `OUT RLY:0`, and the screen goes to tank-full with the litres it showed. The sale records those litres. **Also try:** start a fill-up and **never flow**. Note what happens after 30 s (see "Known gap" below). | |
| 6 | **Coast.** After each `STOP` in steps 2–4, wait ~5 s for flow to settle, then read the next `IN HB cum=H`. | Record **H − cut** per sale. That's the fuel that flows after the cut, which the station gives away. There's no pass mark; TEST-01 wants the number. With a real meter expect a small positive figure; with a generator, ≈ 0. | |
| 7 | **#54 device check.** The fix for #54 is on this build. After any step where USB was replugged (3b), run **one** more cash-fixed 2 L to its end. | Exactly **one** `VM STOP cash …` line for it. Monday's failure was a second, stale `VM STOP` from a sale that had already finished. | |
| 8 | **#55: count the echo.** No extra action. After each `LINK UP` in steps 3, 3b and 4, count the rows until the trace settles. | Record the count per `LINK UP`: how many `ARM` rows answered one `RLY:1`/`RES`, how many `INVALID` rows. Monday saw a 30-row `ARM` burst. This is data for the fix, not a pass/fail. | |
| 9 | **Totaliser reconciles.** At the end: last `HB cum` minus the first `BOOT cum` after step 2's flash. | Equals the sum of every sale's `cut − start`, plus every coast (step 6), plus any `ADAPTER_SESSION_LOST` volume (step 4), plus anything poured outside a sale. Monday's version of this closed to one explained 1.45 L. | |

### Cutting power in steps 3–4: the trap

**The tablet's USB can keep the Mega alive.** If the tablet supplies 5 V down the cable (OTG), then
pulling the DC adapter drops the sense line. The board saves, cuts the relay and **halts**
(`L` lit, silent), but it never actually loses power, so it never reboots and never sends `BOOT`.
That looks like a hang.

- **Cleanest:** if a powered USB-C hub sits between tablet and Mega, put the hub and the Mega's DC
  adapter on **one power strip** and switch the strip. The whole rig loses power at once, like a
  station outage. The tablet stays up on its battery.
- **Otherwise:** pull the DC adapter, then **pull the USB at the Mega end** within a second. Wait
  5 s, plug both back.
- **If you ever see `L` lit and no frames:** that's the halt, not a crash. Unplug the USB for 5 s
  and replug; the board boots with the session it saved.

### Known gap to watch in step 5 (not a new defect until you see it)

The fill-up's idle watchdog only arms **after the first pulse** (`lastPulseMs > 0L`,
`CustomerViewModel.kt:950`). So a fill-up started with no flow at all has nothing to end it on the
app side. The relay stays open until someone taps, and the adapter's own watchdog only watches the
PING. Step 5's second half checks what actually happens. If the relay is still open after 30 s,
note it; it goes on `TODO.md` as a new item.

---

## After

- **All of 1–4 passed:** commit `ENABLE_POWER_FAIL_SAVE = true` (plus the edge, if step 1a
  changed it) on `feature/phase-11-adapter-cutoff`. Update the sketch comment that says the flag is
  off "until Olonade's Friday session". Then log the session and merge Phase 11 to `main`: app,
  firmware and 7g's totaliser together.
- **Step 3 failed but 3b and 4 passed:** reflash with the flag **off** before leaving. Phase 11 can
  still merge (the Uno gate stands), but power-fail recovery is not proven and V1_BLOCKERS gets a line.
- **Either way:** plug the tablet into the laptop afterwards and I'll pull the DB (`run-as` →
  `smartpump.db` + `-wal`) and read every row. Nothing on the screen needs transcribing.

| Item | Figure |
|---|---|
| Sense idle level / edge | |
| Hold-up (ms) | |
| Coast per sale (pulses) | |
| `ARM` rows per `LINK UP` (#55) | |
| Totaliser vs ledger (pulses) | |
