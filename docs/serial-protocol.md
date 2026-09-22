# SmartPump serial protocol — revision 2 (Phase 11: the adapter owns the cutoff)

_Drafted 2026-09-22 as Phase 11a. **Status: DRAFT — awaiting the user's confirmation.** No firmware or
app code is written against this until it is confirmed. Decisions still open are collected in
[§9](#9-decisions-for-you-to-confirm)._

This is the authority for the wire between the Android app and the pulse adapter from Phase 11 on.
It replaces the framing comments in `hardware/smartpump_pulse_adapter.ino`, `SerialFrame.kt` and the
7a-era `hardware/README.md` as the place to look. Those comments get updated in 11b/11c to point
here.

**Why revision 2:** OQ #26 (agreed 2026-09-22) moves the fixed-dispense cutoff onto the board, and
OQ #24's session mark rides on the same frame. See [`PHASE_11_PLAN.md`](journal/PHASE_11_PLAN.md).

---

## 1. Framing (unchanged from revision 1)

- USB CDC serial, **115200 8N1**, one frame per line, terminated by `\n` (a `\r` before it is
  tolerated in both directions).
- Every frame is `<body>*<cs>`. `<cs>` is the **XOR-8 of every ASCII byte of `<body>`** (everything
  before the last `*`), printed as **two uppercase hex digits**.
- Numbers are **unsigned decimal**, no sign, no spaces, no leading `+`. At most 10 digits and at
  most 4 294 967 295 (an AVR `unsigned long`). Anything else is malformed.
- Nothing may be printed outside this framing while the app is attached (`DEBUG_BANNERS = false`).
- Receive buffer on the board is 40 bytes; the longest frame below is 29 bytes with its checksum.

Checksums already in use, for cross-checking any implementation: `PING` → `10`, `RLY:0` → `4D`,
`RLY:1` → `4C`, `PULSE:1` → `54`, `HB:0` → `00`, `BOOT:0` → `1C`, `ERR:WDOG` → `64`, `ERR:PWR` →
`2A`. (All reproduced when this document's examples were computed.)

---

## 2. Two ideas this revision adds

### 2.1 The session (the "trip meter")

The board already keeps `count`, a free-running lifetime pulse count (EEPROM-restored since 7g).
A **session** is one sale's view of it. The board holds **at most one** session:

| Field | Set by | Meaning |
|---|---|---|
| `tag` | the app, in `RLY:1` | identifies the sale; 1 … 4 294 967 295 (**0 is invalid**) |
| `start` | the board, at arm time | `count` at the instant the relay was energised |
| `limit` | the app, in `RLY:1` | pulses this session may deliver; 1 … `MAX_LIMIT` |
| `state` | the board | `OPEN`, `HELD` or `DONE` — see §4 |
| `cut` | the board | `count` at the instant the limit was reached (`DONE` only) |

**The sale's pulse count is always `count − start`,** computed by the app from any frame carrying
`count` (`PULSE`, `HB`). The board's own number, subtracted from the board's own number: nothing
can fall in a gap between them. **This is what retires #36.** (Unsigned subtraction, so it is
correct across a counter wrap too — not that a wrap is reachable: 2³² pulses is ~43 million litres.)

### 2.2 The limit

The board cuts the relay itself when `count − start >= limit`. The test runs **in the pulse ISR**,
on the pulse that reaches the limit, so the cut has no USB round trip and no `PULSE_TX_MIN_MS`
throttle in it — only the mechanical term (relay coil, solenoid, fluid coast) remains.

- **The limit is in pulses, never litres.** The app stays the sole owner of the K-factor, the price
  and the naira→litres maths; the board only counts to N. This keeps Phase 11 independent of OQ #23.
- **Every `RLY:1` carries a limit.** A bare `RLY:1`, or one whose limit or tag is malformed, zero or
  out of range, is refused with `ERR:CMD` **and the relay stays off.** Fail to no fuel, never fail to
  unlimited.
- **The limit and the open are one frame, one checksum.** There is no separate arming frame, so there
  is no window in which a dropped arm leaves an open relay with no ceiling.

---

## 3. Frames

### 3.1 App → board

| Frame | Example (with checksum) | Meaning |
|---|---|---|
| `PING` | `PING*10` | Liveness, ~1 Hz while the link is up. **Unchanged.** Feeds the watchdog. |
| `RLY:1:<limit>:<tag>` | `RLY:1:500:7*4E` | **Arm and open.** Start (or re-acknowledge) session `tag` with `limit` pulses. Reply: `ARM`. |
| `RLY:0` | `RLY:0*4D` | **Relay off.** Bytes unchanged. An `OPEN` session becomes `HELD` (resumable); it is **not** ended. Commits the totaliser. No reply. |
| `RES:<tag>` | `RES:7*49` | **Resume** session `tag`: re-energise if it is `HELD` and under its limit. Reply: `ARM`, `STOP` or `ERR:NOSESSION`. |
| `SES?` | `SES?*7A` | **Query** the session the board holds, without changing anything. Reply: `ARM`, `STOP` or `ERR:NOSESSION`. |

**Removed:** bare `RLY:1` (`RLY:1*4C`). It is now refused with `ERR:CMD`.

### 3.2 Board → app

| Frame | Example (with checksum) | Meaning |
|---|---|---|
| `PULSE:<count>` | `PULSE:12500*53` | **Unchanged.** Throttled to one per 30 ms. |
| `HB:<count>` | `HB:12500*06` | **Unchanged.** ~2 s when no `PULSE` has been sent. |
| `BOOT:<count>` | `BOOT:0*1C` | **Unchanged.** Once at power-up. A boot means **there is no session** (§6.4). |
| `ARM:<tag>:<start>` | `ARM:7:12000*5A` | Session `tag` is `OPEN` or `HELD`, started at `start`. Reply to `RLY:1`, `RES`, `SES?`. |
| `STOP:<tag>:<cut>` | `STOP:7:12500*19` | Session `tag` reached its limit; the relay was cut at `count == cut` (so `cut − start == limit`). Sent **unsolicited** when it happens, and again as the reply to `RES`/`SES?` for a `DONE` session. |
| `ERR:<code>` | `ERR:NOSESSION*20` | Refusal or fault — codes below. |

`ERR` codes:

| Code | Example | When | Relay afterwards |
|---|---|---|---|
| `CMD` | `ERR:CMD*35` | unknown command; bare or malformed `RLY:1`; limit 0 or > `MAX_LIMIT`; tag 0 | **unchanged** (so off, unless an unrelated session is already `OPEN`) |
| `NOSESSION` | `ERR:NOSESSION*20` | `RES`/`SES?` and the board holds no session, or holds one with a different tag (`RES` only) | unchanged |
| `CSUM` | `ERR:CSUM*77` | checksum mismatch — **unchanged** | unchanged |
| `NOCS` | `ERR:NOCS*6E` | no `*<cs>` — **unchanged** | unchanged |
| `WDOG` | `ERR:WDOG*64` | watchdog tripped — **unchanged**; the session becomes `HELD` | off |
| `PWR` | `ERR:PWR*2A` | power-fail save — **unchanged** | off, then the board halts |

**Parsing note for the app (11c):** `ARM` and `STOP` carry **two** numbers, where every existing
frame carries one. This is new parser work, but it is additive — no existing frame changes shape,
which is what made OQ #23's `BOOT:<cum>:<ppl>` dangerous and does not apply here.

---

## 4. The board's session state machine

```
  (none) ──RLY:1 new tag──► OPEN ──limit reached (ISR)──► DONE     relay on only in OPEN
                            │  ▲
          RLY:0 / WDOG trip │  │ RES or same-tag RLY:1 (under the limit)
                            ▼  │
                            HELD

  power-up: any state ──► (none)          RLY:1 with a new tag: any state ──► OPEN (new session)
```

| In state | Receives | Does | Replies |
|---|---|---|---|
| none | `RLY:1:<n>:<t>` valid | `start = count`, `limit = n`, `tag = t`; relay on; watchdog clock seeded; → `OPEN` | `ARM:t:start` |
| any | `RLY:1` invalid | nothing | `ERR:CMD` |
| `OPEN`/`HELD`/`DONE`, **same tag** | `RLY:1:<n>:<t>` | **treated exactly as `RES:<t>`** (below). Never re-latches `start`, never changes `limit` — `<n>` is ignored | as `RES` |
| any session, **different tag** | `RLY:1:<n>:<t2>` | the old session is discarded; totaliser committed; new session as from none; → `OPEN` | `ARM:t2:start` |
| `OPEN` | pulse where `count − start >= limit` | **in the ISR:** relay off, `cut = count`, → `DONE`, flag the report. **In `loop()`:** send `STOP`, commit totaliser | `STOP:t:cut` (unsolicited) |
| `OPEN` | `RLY:0` | relay off; commit totaliser; → `HELD` | — |
| `OPEN` | watchdog trips | relay off; commit totaliser; → `HELD` | `ERR:WDOG` |
| `HELD`, same tag | `RES:<t>` | if `count − start < limit`: relay on, watchdog clock seeded, → `OPEN`; else → `DONE` | `ARM:t:start`, or `STOP:t:cut` |
| `OPEN`, same tag | `RES:<t>` | nothing (idempotent) | `ARM:t:start` |
| `DONE`, same tag | `RES:<t>` | nothing — **a finished session is never re-opened** | `STOP:t:cut` |
| none, or other tag | `RES:<t>` | nothing | `ERR:NOSESSION` |
| any | `SES?` | nothing | `ARM:t:start`, `STOP:t:cut`, or `ERR:NOSESSION` |
| any | power-up | relay off (unchanged invariant); **no session** | `BOOT:count` |

Two rules carry the safety of the whole design and are worth stating outright:

1. **Only a new tag can grant a new allowance.** Re-sending `RLY:1` with the same tag, `RES`, `SES?`
   and a reconnect can never add fuel to a sale — they can only re-open what is left under the limit
   the board already holds. That is what makes it safe for the app to retry blindly.
2. **The relay can only come on through `RLY:1` (new session) or `RES` (the held session, under its
   limit).** Nothing on the board re-energises on its own — not the watchdog, not a boot.

**Why `RLY:0` holds rather than ends the session:** the app sends `RLY:0` unconditionally on every
boot (the "relay defaults open on boot" invariant, `CustomerViewModel` init). If `RLY:0` ended the
session, that line would destroy the very session boot-resume is about to query. Holding is also
harmless when the sale really is over: the app never sends `RES` for a sale it is not dispensing,
and the next sale's new tag discards it.

**Why the tag is chosen by the app:** it makes the `ARM` for *this* sale distinguishable from a
stale session left on the board by an earlier one (e.g. a `RLY:0` lost on a dropped link). Without
it, "my `RLY:1` got no reply" and "an old session is still there" look the same to `SES?`, and the
wrong answer adopts a stale `start` and `limit`. The app picks a **random non-zero 32-bit** tag per
sale (§9, D2) and persists it **before** sending `RLY:1`.

---

## 5. What the app does (obligations on 11c–11e)

- **Choosing the limit.**
  - Fixed flows (Flow 1 pre-pay, Flow 4 cash-fixed, USSD): `limit = floor(litresCutoff × PULSES_PER_LITRE)`.
    Floor, so the board never delivers more than was paid for.
  - Fill-ups (Flows 2 and 3): `limit = ceiling` (§9, D4). The nozzle-idle shutoff stays in the app.
- **The app's backstop cutoff compares pulses with the same `limit`, never litres.** With a
  non-integer K-factor, `limit / PULSES_PER_LITRE` is slightly *below* `litresCutoff` (e.g. 3.35 L at
  98.7 pulses/L → limit 330 → 3.343 L), so a litres comparison would never fire and a sale whose
  `STOP` was lost would hang. Comparing `count − start >= limit` makes both cutoffs the same test.
  If the app's fires before a `STOP` arrives, write an event row: that is a defect, not a fallback.
- **Starting a sale.** Persist `tag` and `limit`, then send `RLY:1:<limit>:<tag>` and wait for
  `ARM:<tag>:…` (proposed: 500 ms). No `ARM` → **re-send the same frame** (safe by rule 1), up to 3
  times. Still nothing → the sale does not start and the app says so; the relay has either never come
  on, or is on under a limit the board enforces.
- **Counting.** Before `ARM` arrives, pulses are not shown (the `PULSE` frames still carry `count`,
  so nothing is lost — the app computes `count − start` once it knows `start`). Persist `start` when
  `ARM` arrives.
- **Ending a sale early or a fill-up on nozzle idle:** `RLY:0`, as today.
- **`STOP:<tag>:<cut>` for the current sale:** complete it exactly as the app's own cutoff does
  today, with sale pulses = `cut − start` (= `limit`). A `STOP` for another tag is logged and ignored.
- **Link down → up during a sale:** send `RES:<tag>` — **never** `RLY:1`. (Replaces today's re-assert
  of a bare `RLY:1`, which with a limit attached would hand a sale that stopped at 9 of 10 L a fresh
  10 L. Rule 1 makes this impossible even if the app got it wrong, but the app should not rely on
  that.)
- **`ERR:WDOG` during a sale with the link still up:** send `RES:<tag>` (§9, D5).
- **`BOOT` during a sale, or `ERR:NOSESSION` in reply to `RES`:** the board lost the session (§6.4) —
  hand to the view model to re-arm (§6.4).
- **Boot resume** (§6.2).

---

## 6. Edge cases

### 6.1 A frame is lost or corrupted

| Lost | Effect | Recovery |
|---|---|---|
| `RLY:1` (app → board) | no session, relay off | no `ARM` → app re-sends the same frame |
| `ARM` (board → app) | session `OPEN`, fuel flowing, app doesn't know `start` | app re-sends `RLY:1` with the **same tag** → treated as `RES` → `ARM` again (re-opening the session if the watchdog held it meanwhile), no new allowance |
| `STOP` | board cut at the limit; app still shows the sale running | the app's backstop fires on the next `PULSE`/`HB` (≤ 2 s), because `count − start` reaches `limit`; on reconnect `RES` gets `STOP` again |
| `RLY:0` | relay stays on under the session's limit | unchanged from revision 1 — `RLY:0` has no acknowledgement (§9, D7). For a fixed sale the board's limit now bounds it; for a fill-up, the ceiling does |
| `RES` / `SES?` | no reply | re-send; both are idempotent |
| `PULSE` | none — the next `PULSE`/`HB` carries a higher `count` | unchanged |

### 6.2 The app dies mid-sale (the case 7h step 8 measured at ~1.5 L)

The board keeps its session in RAM. The watchdog trips 3 s after the last `PING`: relay off, session
`HELD`. On restart:

1. The VM's boot line sends `RLY:0` — the session stays `HELD` (§4 on why).
2. Boot resume sends `SES?`.
   - `ARM:<tag>:<start>` with the persisted tag → the sale's exact pulses are `count − start`, read
     from the next `HB`. **No 7h gap estimation is needed for this case**: every pulse the board
     counted while the app was dead is in the figure, attributed to the right sale. If
     `count − start >= limit`, complete the sale; otherwise `RES:<tag>` and carry on.
   - `STOP:<tag>:<cut>` → the sale reached its limit while the app was dead; complete it.
   - `ERR:NOSESSION`, or another tag → the board rebooted or was replaced; §6.4.
3. **If the app died between persisting the tag and receiving `ARM`**, `SES?` still answers with
   that tag and its `start` — which is why the tag is persisted *before* `RLY:1` is sent.

**What this does to the give-away:** a fixed sale can no longer over-deliver on app death — the
board stops at the limit whether or not the app is alive. A fill-up's fuel during those 3 s is now
*counted and billed* on restart rather than lost. Both halves of the 7h step-8 finding close.

### 6.3 The USB link drops and returns mid-sale

The watchdog trips (link gone → no `PING`): relay off, `HELD`. On the down→up edge the relay
controller sends `RES:<tag>` → `ARM` → fuel resumes, **still under the original limit** (11f step 5).

### 6.4 The board reboots mid-sale

Power-up clears the session (V1 does not persist it — §9, D3), the relay comes up off, `BOOT` is
sent. The app learns of it from the `BOOT` frame, or from `ERR:NOSESSION` in reply to `RES`/`SES?`.

This is the **only** case that falls back to the app's own figure: the view model re-arms with a
**new tag** and `limit = original limit − pulses the app has counted` (its last persisted count
plus 7h reconciliation, as today). Pulses the board counted but never reported before it rebooted
are lost to both sides — exactly as today. With no power-fail save wired (`ENABLE_POWER_FAIL_SAVE =
false`) the board has no better number to offer.

### 6.5 Fuel after the cut (coast)

The ISR cuts on the pulse that makes `count − start == limit`, so `cut − start == limit` exactly. The
meter goes on counting whatever flows while the solenoid closes. Those pulses land in the lifetime
`count` but **belong to no session**: `STOP` reports `cut`, not the count a moment later. The sale's
figure is therefore exactly what was paid for, and the coast is fuel the station gives away — as it
does today, minus the USB round trip.

It is now **measurable**: `HB` count after the sale settles, minus `cut`. 11f should record it; it
is the mechanical term OQ #26 said software could not touch, and TEST-01 will want to know its size.

### 6.6 Old app against new firmware, and the reverse

App and firmware ship together; there is **no backwards compatibility**. Both mismatches fail to
**no fuel**:

- **Old app, new firmware:** the old app sends a bare `RLY:1` → `ERR:CMD`, relay stays off.
- **New app, old firmware:** the old firmware does not recognise `RLY:1:<n>:<t>` → `ERR:CMD`, relay
  stays off. The new app gets no `ARM` and refuses to start the sale (§5), so the screen says so
  instead of waiting for pulses that will not come.

### 6.7 Pulses from the demo sources

`ENABLE_AUTO_PULSE` and the button add pulses from `loop()`, not the ISR. The limit test must run on
**every** increment, whatever its source — 11b routes both through one function that increments and
tests.

---

## 7. Constants

| Name | Where | Value | Note |
|---|---|---|---|
| `MAX_LIMIT` | firmware | **1 000 000** pulses | sanity bound on a `RLY:1` limit only. 10 000 L at 100 pulses/L, 2 222 L at 450 |
| `HEARTBEAT_TIMEOUT_MS` | firmware | **3000** (unchanged) | §9, D6 on #38 |
| `ARM_TIMEOUT_MS` | app | 500 | wait for `ARM` before re-sending `RLY:1` |
| `ARM_ATTEMPTS` | app | 3 | then the sale does not start |
| fill-up ceiling | app | §9, D4 | in litres, converted with `PULSES_PER_LITRE` |

---

## 8. What changes, at a glance

| | Revision 1 (today) | Revision 2 |
|---|---|---|
| Relay on | `RLY:1` | `RLY:1:<limit>:<tag>`; bare `RLY:1` refused |
| Who stops a fixed sale | the app, over USB | the board, in the ISR; the app is a backstop |
| Sale's pulse count | app's own delta from the first frame it catches (#36: loses pulses at attach) | `count − start`, both from the board |
| Reconnect | re-send `RLY:1` | `RES:<tag>` |
| After an app death | 7h estimates the gap from a saved anchor | `SES?` gives the exact figure |
| Board-initiated stop report | none | `STOP:<tag>:<cut>` |
| New error codes | — | `ERR:NOSESSION` |

---

## 9. Decisions for you to confirm

Each has a recommendation. Say "confirmed" and 11b starts on these; change any of them and I update
this document first.

**D1 — The session is tagged by the app.** `RLY:1:<limit>:<tag>`, and `ARM`/`STOP` echo the tag.
This is a change from the plan, which had `ARM:<start>` with one number. Without the tag, a lost
`ARM` and a stale session from an earlier sale look identical to the app, and re-sending `RLY:1`
could grant a second allowance. With it, every retry is safe (§4, rule 1). The cost is two-number
frames, which the parser does not handle yet (§3.2) — small and additive. **Recommended.**

**D2 — The tag is a random non-zero 32-bit number per sale** rather than a counter. A counter
resets on reinstall and could collide with a stale session still on the board; a random tag
collides about once in four billion sales. It is persisted with the sale, so nothing extra has to
survive between sales. **Recommended.**

**D3 — The session is not persisted to EEPROM in V1.** A board reboot ends it (§6.4). The wear is
fine either way (40 slots of a 24-byte record, two commits per sale ≈ 2 million sales). The problem
is correctness: the power-fail save is off (`ENABLE_POWER_FAIL_SAVE = false`, no sense circuit on
any rig), so after a power cut the restored `count` is the last *commit*, not the last pulse. A
persisted session resumed from that count would re-grant every pulse the board lost, which is
over-dispensing. Revisit when the power-sense circuit exists. **Recommended: do not persist.**

**D4 — Fill-up ceiling: 200 L**, as an app constant in litres. It is a runaway backstop, not a
cutoff: it only matters if the app is alive (still PINGing) but has stopped acting on the
nozzle-idle shutoff. If a real sale ever hits it, the fill-up ends as it would on nozzle idle and
the customer pays for what flowed; the attendant starts a second sale. **200 L is a guess** — it
needs to be above the largest single fill the station serves. Trucks and buses may take more: tell
me if they do.

**D5 — On `ERR:WDOG` with the link still up, the app sends `RES` automatically.** A watchdog trip
with the link up means the app stalled for 3 s and recovered. Today the sale then sits with no fuel
until the attendant ends it. Resuming is safe because the board holds the limit. **Recommended.**

**D6 — Do not fold in #38 (watchdog 3 s → 2 s); close it as superseded.** #38's case was the ~1.5 L
given away when the app dies. §6.2 closes that differently: a fixed sale cannot pass its limit, and
fill-up fuel after an app death is counted and billed on restart. A shorter watchdog would now buy
almost nothing, and it would trip more often on a slow tablet (the SM-T220's collector lag is on the
record under #28). **Recommended: keep 3 s.**

**D7 — `RLY:0` stays unacknowledged.** A lost `RLY:0` is the same risk it is today, now bounded by
the limit or the ceiling. An acknowledgement would need retry logic on every stop path in the app
for a failure mode already bounded. **Recommended: leave it; revisit only if the bench shows lost
stops.**

**D8 — The nozzle-idle shutoff stays in the app** (the V1 default in the plan). Moving it to the
board would only bound an app crash during a fill-up, which the watchdog already bounds at 3 s, and
the fuel is billed anyway (§6.2). **Recommended: leave it in the app.**
