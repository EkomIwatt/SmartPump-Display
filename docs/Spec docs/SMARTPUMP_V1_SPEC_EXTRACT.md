# SmartPump V1 — Canonical Spec Extract

**Purpose.** Claude Code cannot read the source spec documents. This file is a faithful text
extraction of them, structured so it can be grepped, cited, and diffed against the codebase.

**Provenance.** Extracted from the Balanceè SmartPump project docs:
`Prototype_specs_1..7`, `Screen_spec_1..8`, `V1_EXECUTION_TRACKER`, `success_metrics`.
These files carry a `.pdf` extension but are **ZIP archives of JPEG page screenshots**.
`pdftotext` returns nothing. See Appendix A for the extraction method.

**Status of this file.** Reference material, not the codebase. Where this document and the
implementation disagree, that disagreement is recorded in §11 (Deltas) rather than silently
resolved. Do not "fix" code to match this file without checking §11 first — several deltas are
open decisions, not bugs.

**Citation scheme.** Every requirement has a stable ID (`HW-C-04`, `FLOW-2-03`, …). Use the ID
when referring to a requirement so we can trace it back.

---

## 0. Extraction fidelity — read before trusting a number

- Page captures are **cropped on the right edge** in most Screen_spec pages. Right-hand columns
  of some screen mockups are cut off. Body text and code blocks are complete.
- **Prototype_specs_4 "Problem 2: Connectivity" is truncated** in the source capture. Only the
  Power section survived. Connectivity requirements below are inferred from other sections and
  marked as such.
- **Prototype_specs_6 (timeline)** is almost entirely cut off in the capture. Use
  `V1_EXECUTION_TRACKER` for schedule.
- Numbers below were read visually rather than OCR'd wherever they matter. Where OCR was the
  only source, the entry is marked `[OCR]` and should be re-verified before it drives code.

---

## 1. Scope and the three core claims

`SCOPE-01` — V1.0 is **one upgrade kit, one dispenser nozzle, one live station**.

`SCOPE-02` — The prototype proves the core claim before anything else is built: accurate litre
tracking plus payment integration that survives a Nigerian power cut and works offline.

The three claims, in the spec's own priority order:

`SCOPE-03` — **Survive a power cut without losing the transaction.** When NEPA cuts and the
generator kicks in (5–15 seconds), the active fuel transaction must not be lost, corrupted, or
double-billed. The pump must resume exactly where it left off. The spec calls this "the number
one reason Nigerian operators will abandon the system if it fails."

`SCOPE-04` — **Accept payment and unlock the pump with no internet.** In the absence of 4G the
customer must still be able to pay via USSD bank transfer. The pump authorises locally and
queues the transaction for cloud sync. "No connectivity = no excuse for refusing a customer."

`SCOPE-05` — **Show the true litres dispensed, independently of the old pump controller.** The
pulse-tap adapter reads the physical flow meter directly. The Android display shows litres from
this independent signal. Even if an attendant presses the old reset or recall button, the locked
display cannot be manipulated. This is the anti-fraud claim.

`SCOPE-06` — **Signal chain:** Flow meter (existing, calibrated) → Pulse-tap adapter (new
hardware) → Android display unit (new hardware).

`SCOPE-07` — The existing flow meter and its calibration seal are **never touched**. The
pulse-tap adapter is a read-only tap: it draws no power from and sends no signal to the existing
meter circuitry.

### Explicitly out of scope for V1.0

`SCOPE-08` — All of the following are V2.0. Adding any of them to V1.0 delays the field test and
muddies the pass/fail criteria:

- Ad screen content management
- Customer loyalty / RFID cards
- Multi-station dashboard
- ATG tank gauge integration
- Fleet card management
- Multi-nozzle forecourt controller
- Staff shift management app

---

## 2. Component inventory

The system is six components, labelled A–F in the spec. Screen_spec_8 revisits each one after
fill-up mode was added and marks what changed.

| ID | Component | Owner | V1 status |
|----|-----------|-------|-----------|
| A | Android display unit | Ekomobong | Hardware unchanged, **logic updated** |
| B | Payment panel (QR + NFC) | — | Unchanged |
| C | Pulse-tap adapter board | Olonade | Hardware unchanged, **logic updated** |
| D | LiFePO4 UPS | — | Unchanged |
| E | Relay board (pump lock) | Olonade | Hardware unchanged, **logic updated** |
| F | Cashier tablet | — | Unchanged |

---

## 3. Hardware specifications

### 3.1 Component A — Android display unit

Replaces or mounts alongside the existing LED panel on the dispenser cabinet. Customer-facing
screen and payment terminal in one unit.

| ID | Requirement |
|----|-------------|
| `HW-A-01` | Screen: 7" IPS, 1024×600, outdoor-readable 600+ nits |
| `HW-A-02` | OS: Android 11+ (Go Edition for cost efficiency) |
| `HW-A-03` | Processor: quad-core 1.6GHz minimum — Rockchip RK3326 or equivalent |
| `HW-A-04` | 2GB RAM / 16GB storage (transaction cache offline) |
| `HW-A-05` | Enclosure: IP65 — dust-tight, water jet resistant. Operating temp −10°C to 60°C |
| `HW-A-06` | Input: capacitive touch **plus a physical emergency stop button (hardware, not software)** |
| `HW-A-07` | Payment: NFC reader (ISO 14443) + EMV chip slot + QR code display panel |
| `HW-A-08` | Comms: 4G LTE (dual SIM) + Wi-Fi 2.4GHz + Bluetooth 4.2 (local POS pairing) |
| `HW-A-09` | Power input: 9–36V DC wide-range (handles generator voltage sag) |
| `HW-A-10` | Power draw: ~18W active, <4W standby `[OCR]` |
| `HW-A-11` | Mounting: standard VESA 75mm + custom-fabricated pump-face adapter bracket |
| `HW-A-12` | ATEX-certified enclosure preferred; anti-glare tempered glass; tamper-evident seal on back panel |

Software requirements new in V1 (all five are Ekomobong's):

| ID | Requirement |
|----|-------------|
| `SW-A-01` | Supports two transaction modes: fixed/pre-pay and fill-up/post-pay |
| `SW-A-02` | Attendant swipe-up menu: FILL UP AUTHORISE / AUTHORISE CASH / CASH RECEIVED |
| `SW-A-03` | Nozzle shutoff detection: 3-second pulse timeout after active flow |
| `SW-A-04` | Dynamic QR generation after fill-up for exact verified amount |
| `SW-A-05` | USSD SMS parsing: listens on pump SIM via BroadcastReceiver |
| `SW-A-06` | Webhook payload gains `mode: "fixed" \| "fillup"` on `POST /pump/authorise` |

### 3.2 Component C — Pulse-tap adapter board

The spec calls this **"the most important piece of hardware in the entire system."** A read-only
signal tap between the existing flow meter's pulse output and the Android unit. It does not
interfere with the existing meter in any way — passive observation only.

| ID | Requirement |
|----|-------------|
| `HW-C-01` | Function: optical isolator tap on flow meter pulse line — read-only, no back-feed |
| `HW-C-02` | Pulse input: supports 5V **and** 12V pulse signals (covers Gilbarco, Wayne, Tokheim) |
| `HW-C-03` | Output: USB serial to Android unit **plus raw pulse count stored in onboard EEPROM** |
| `HW-C-04` | EEPROM: stores last 10,000 pulse counts — survives power cuts, readable independently |
| `HW-C-05` | MCU: STM32F103 or ATmega328P — ultra-low power, hardened |
| `HW-C-06` | Isolation: 2500V galvanic — existing meter circuitry is never at risk |
| `HW-C-07` | Form factor: DIN rail mount, 70×50×25mm, fits inside dispenser electronics bay |
| `HW-C-08` | Calibration: pulse-per-litre constant **loaded at commissioning, sealed post-calibration** |
| `HW-C-09` | Implementation (per Screen_spec_8): Arduino + **4N35 optocoupler** + EEPROM |
| `HW-C-10` | Bench BOM (per tracker): Arduino Mega × 2, 4N35 optocouplers, breadboards, jumpers, resistors |

Mode behaviour — this is the part that constrains the Android app:

`HW-C-11` — Hardware unchanged between modes. The adapter **always counts and always sends
`PULSE:XXXX`**. Fill-up mode is managed by the Android app, not the adapter. The adapter does not
need to know which mode it is in.

`HW-C-12` — Fixed mode: Android stops the relay when count reaches `litres_authorised`.
Fill-up mode: Android monitors for the 3s no-pulse timeout and signals nozzle shut.
Screen_spec_8 states plainly: **"Olonade's firmware is correct as-is for both modes."**

`HW-C-13` — This board is the prototype's primary research output. PCB design to be open-sourced
post-field-test. Positioned as a thesis design target for university EE students.

### 3.3 Component D — LiFePO4 UPS power bridge

Bridges the 5–15 second gap between mains cut and generator stabilisation. The spec calls it
"the single most Nigeria-specific engineering challenge."

| ID | Requirement |
|----|-------------|
| `HW-D-01` | Chemistry: LiFePO4 — stable, safe near fuel vapour |
| `HW-D-02` | Capacity: 10Ah / 12V = 120Wh — provides 6+ minutes at full system load. **Screen_spec_8 says 12V 20Ah — see `DELTA-08`** |
| `HW-D-03` | Switchover: <20ms automatic, imperceptible to Android OS and active transaction |
| `HW-D-04` | Integrated MPPT charge controller, works on generator and mains |
| `HW-D-05` | Rated −20°C to 60°C |
| `HW-D-06` | Sealed, vented metal box, mounted inside dispenser lower bay away from fuel |
| `HW-D-07` | Battery state-of-charge reported to Android unit **via I²C** — alerts operator when <20% `[OCR]` |

`HW-D-08` — Chemistry rationale (quoted intent): LiFePO4 does not undergo thermal runaway at
high temperatures and does not emit flammable gases. It is the only chemistry acceptable inside a
fuel dispenser enclosure near petrol vapour. Li-Ion, LiPo and lead-acid are all unacceptable.

### 3.4 Component E — Relay board (pump lock control)

Controls pump lock/unlock by opening/closing the relay. Hardware unchanged; trigger logic is
handled by the Android app.

| ID | Requirement |
|----|-------------|
| `HW-E-01` | Default state: **OPEN (pump locked)** on idle |
| `HW-E-02` | Close trigger: payment confirmed (any method) |
| `HW-E-03` | Open trigger (fixed mode): pulse count reaches `litres_authorised` |
| `HW-E-04` | Open trigger (fill-up mode): Android detects nozzle shutoff via 3s pulse timeout → sends GPIO signal |
| `HW-E-05` | Open trigger (emergency): system power loss → relay defaults OPEN |

### 3.5 Components B and F — unchanged in V1

`HW-B-01` — Payment panel: QR + NFC reader. Now also serves the post-fill-up QR. No hardware
change required — the QR *content* changes, not the panel.

`HW-F-01` — Cashier tablet: existing Balanceè dashboard plus pump status module. Fill-up
transactions appear in the same feed as fixed transactions. **No new UI needed in V1.**

### 3.6 BOM

| Item | Cost |
|------|------|
| Android display unit | ~$280–350 |
| NFC/EMV payment module | ~$80–120 |
| Pulse-tap adapter board | ~$60–90 |
| LiFePO4 UPS (10Ah) | ~$90–130 |
| Enclosure, cables, etc. | ~$80–100 |
| Cashier tablet + printer | ~$200–280 |
| **Total BOM** | **~$790–1,070** |

---

## 4. Software architecture

`SW-01` — **Pump unit app** (Android, on dispenser). Runs in kiosk mode. Responsibilities:
display pulse-tap litres; accept NFC/QR payments; maintain local SQLite queue; persist data to
storage immediately.

`SW-02` — **Cashier app** (Android tablet, in booth). Operator-facing, PIN-protected. Shows pump
status, litres per transaction, shift summaries, manual override authorisations.

`SW-03` — **Cloud backend** (minimal for v1.0). Hosted on Render or AWS Lagos. Receives
transaction syncs. Web browser dashboard for operators. Simple price push updates.

### Two critical software behaviours called out by name

`SW-04` — **Offline transaction queue.** In the absence of 4G, USSD confirmation via SMS pulls
double duty. Local SQLite DB stores `PENDING_SYNC` records until signal returns.

`SW-05` — **Power-cut transaction recovery.** Adapter EEPROM + Android `fsync()` storage.
**On reboot, the system resumes the higher pulse count to ensure customer fairness.**
That is `max(adapter_eeprom_count, android_persisted_count)`. See `DELTA-03`.

### Price distribution

`SW-06` — Price per litre is pushed from the operator app via the Balanceè backend. The Android
unit stores the current price and uses it to calculate litre cutoffs and post-fill-up amounts.

`SW-07` — If no price has been pushed yet, show **"Price not set — contact operator"** and
**block transactions**. This is a hard block, not a warning.

---

## 5. Android state machine and the five flows

### 5.1 Top-level state machine

```
IDLE → MODE_SELECT → {
  PRE_PAY → AMOUNT_SELECT → PAYMENT_METHOD → AWAITING_PAYMENT,
  FILL_UP → FILLUP_CONFIRM      → AWAITING_ATTENDANT_AUTH
}
```

`UI-01` — State 0 IDLE: "SMARTPUMP READY" / "TAP TO PAY" / `Start Transaction` button /
"↑ Swipe up — Attendant".

`UI-02` — State 1 MODE_SELECT: "HOW DO YOU WANT TO FUEL?" with two buttons — **PRE-PAY**
(Fixed amount) and **FILL UP** (Pay after). This is the routing decision.

`UI-03` — PRE-PAY proceeds to amount selection then payment method. FILL UP **skips amount
selection entirely** and goes to the fill-up confirm screen.

`UI-04` — The attendant still authorises the fill-up from the attendant menu regardless of what
the customer does on the mode-select screen.

`UI-05` — State 1A PRE-PAY amount presets: ₦2k, ₦5k, ₦10k, ₦20k, ₦50k, Custom.

`UI-06` — Payment methods offered, in order: Balanceè App (labelled FASTEST), Bank QR / Transfer,
NFC / Tap card, USSD *737#, Cash — see attendant. Then Confirm.

### 5.2 Flow 1 — Fixed pre-pay (digital) · `V1 REQUIRED — primary flow`

Customer selects amount and payment method. System waits for payment confirmation. Relay closes
on webhook. Counts to target. Opens. Done.

Screen sequence: QR WAITING → PAYMENT CONFIRMED → COMPLETE.

`FLOW-1-01` — Webhook payload:

```json
POST /pump/authorise
{
  "mode": "fixed",
  "amount_naira": 5000,
  "litres_authorised": 5.75,
  "transaction_id": "BLC-00847",
  "nozzle_id": "1",
  "price_per_litre": 870
}
```

`FLOW-1-02` — On receipt: set state = `DISPENSING`, close relay, start counting.

`FLOW-1-03` — Open relay when pulse count reaches `litres_authorised`.

`FLOW-1-04` — QR expiry: if no webhook received within **5 minutes**, cancel the transaction and
return to idle.

`FLOW-1-05` — **Do not leave the relay in any state other than OPEN when idle.**

`FLOW-1-06` — Dispensing screen shows: litres dispensed (large), "of N L authorised",
₦ used / ₦ authorised, station name, price/L, txn ID. Green state.

`FLOW-1-07` — Complete screen offers `Share receipt`.

### 5.3 Flow 2 — Fill-up, cash after · `V1 REQUIRED — most common flow in Nigeria`

"The most common Nigerian scenario. Customer says 'fill am.' Attendant swipes up and taps
FILL UP AUTHORISE. System runs open-ended. When the nozzle auto-shuts, screen shows the verified
total. Attendant collects cash and taps CASH RECEIVED."

Screen sequence: FILLING (open-ended) → TANK FULL (amount due) → ATTENDANT CONFIRM.

`FLOW-2-01` — FILLING screen: litres dispensed, "filling… nozzle shuts automatically", running
total in ₦. No litre target. Teal = fill-up mode.

`FLOW-2-02` — TANK FULL screen: final litres marked **"verified count · cannot be changed"**,
AMOUNT DUE = litres × price/L.

`FLOW-2-03` — ATTENDANT CONFIRM: CASH RECEIVED button logs litres, amount, attendant. This
creates the audit record of litres vs cash.

#### Nozzle shutoff detection — `SW-A-03`, critical

`FLOW-2-04` — **There is no hardware signal from the nozzle to the Android unit.** Shutoff is
detected by pulse timeout: if the pulse-tap adapter was sending pulses (fuel was flowing) and
then stops sending pulses for 3 consecutive seconds, the app treats this as nozzle shutoff. At
that point: open the relay, lock the final litre count, display the total.

```java
// Nozzle shutoff detection
lastPulseTime = System.currentTimeMillis();
// In pulse listener:
lastPulseTime = now;
// In background timer (check every 500ms):
if (flowActive && (now - lastPulseTime) > 3000) {
  onNozzleShutoff(); // open relay, lock count
}
```

`FLOW-2-05` — The 3-second timeout is **configurable**. Too short = false positives on slow
flow. Too long = customer waits. 3s is the recommended starting value.

`FLOW-2-06` — Interaction with power cuts: during a mains cut mid-fill-up, the app **must not
falsely detect nozzle shutoff**. This is an explicit test case (see `TEST-12`).

### 5.4 Flow 3 — Fill-up, digital after · `V1 REQUIRED — fills the digital post-pay gap`

Same fill-up start as Flow 2. After the nozzle shuts, instead of cash, a QR is generated encoding
a bank transfer for the exact verified litre amount. Customer scans on any bank app.

`FLOW-3-01` — The QR is **not static**. It is generated after the fill-up completes using the
final verified litre count.

`FLOW-3-02` — The QR encodes a **NIP bank transfer to the station's virtual account number** for
the exact calculated amount. This is different from the pre-pay QR, which is generated *before*
fuelling from a fixed amount.

`FLOW-3-03` — The Balanceè backend provides the virtual account number per station. It is stored
on the Android unit **during setup**.

```java
// After nozzle shutoff in fill-up mode:
finalLitres = pulseCount × litresPerPulse;
amountDue   = finalLitres × currentPricePerLitre;
qrContent   = generateNIPTransferQR(stationAccount, amountDue, txnId);
showQRScreen(qrContent, amountDue, finalLitres);
```

`FLOW-3-04` — QR screen copy: "Open any bank app · scan · confirm. GTBank · Opay · Palmpay ·
any bank." Marked "Dynamic — changes per transaction."

`FLOW-3-05` — Note `litresPerPulse` here, not pulses-per-litre. The spec's canonical direction is
litres **per pulse**. Reciprocal of the constant currently in the codebase.

### 5.5 Flow 4 — Cash, fixed amount · `V1 REQUIRED`

Customer states a cash amount. Attendant enters it on the pump screen. Android calculates the
litre cutoff from the pushed price. Relay cuts at exactly that litre count.

Screen sequence: ATTENDANT ENTERS ₦ → DISPENSING TO CUTOFF → COMPLETE.

`FLOW-4-01` — The attendant sees the litre cutoff **before** authorising. The authorise screen
shows cash amount, current price/L, and computed litres cutoff.

`FLOW-4-02` — Gold = cash mode (colour coding is per-mode; teal = fill-up, green = confirmed
digital, gold = cash).

#### Litre cutoff calculation — rounding rule

```java
// On AUTHORISE CASH button tap:
cashAmount   = parseFloat(inputField.value);  // e.g. 5000
currentPrice = getStoredPrice();              // pushed from operator app, e.g. 870
litresCutoff = cashAmount / currentPrice;     // 5.75L
// Round down to avoid dispensing more than paid:
litresCutoff = Math.floor(litresCutoff * 100) / 100;  // 5.75
startFixedDispense(litresCutoff, "cash", cashAmount);
```

`FLOW-4-03` — **Rounding is floor to 2 decimal places, always down.** The stated reason is to
avoid dispensing more than was paid for. This is the client-side half of the rounding contract;
the server-side half is still open (`OQ-06`).

### 5.6 Flow 5 — USSD, zero internet · `V1 REQUIRED — offline coverage for low-connectivity stations`

Customer dials a USSD code. The bank processes it and sends an SMS to the pump unit's SIM.
Android parses the SMS and unlocks the relay. Works on any 2G feature phone. **No internet on
either device required.**

Screen sequence: USSD CODE DISPLAYED → WAITING FOR SMS → SMS CONFIRMED.

`FLOW-5-01` — The pump displays USSD codes for the major banks, pre-filled with amount and
reference: GTBank `*737*5000*847#`, Access `*901*5000*847#`, Zenith `*966*5000*847#`,
UBA `*919*5000*847#`.

`FLOW-5-02` — Waiting screen states "Bank sends SMS after USSD completes. Usually 10–30 seconds,"
and displays amount, ref, and SIM status (carrier + signal).

`FLOW-5-03` — Confirmed screen shows METHOD = OFFLINE.

#### SMS parsing

`FLOW-5-04` — The Android unit must listen for incoming SMS on the pump SIM. Each bank sends a
different SMS format. The parser must extract three things: (a) confirmation that payment
succeeded, (b) the amount, (c) the transaction reference that matches the pending transaction.

```java
// GTBank example SMS:
// "Your GTBank acct was debited ₦5,000.00 for BALANCEE-847 on 30/04/26..."
// Extract: amount=5000, ref=847

// Android SMS listener:
SmsReceiver extends BroadcastReceiver {
  onReceive(context, intent) {
    String sms = intent.getStringExtra("pdu_parsed");
    PaymentConfirmation p = SMSParser.parse(sms);
    if (p.matches(pendingTxn)) authorisePump(p.amount);
  }
}
```

`FLOW-5-05` — Bank coverage scope: start with **GTBank (*737#) for V1**. Add **Access and Zenith
before the field test**. Full Nigerian bank coverage is V2.

`FLOW-5-06` — Acceptance gate: pump unlocks within **90 seconds** of SMS confirmation (`TEST-05`).

---

## 6. Attendant interface

`ATT-01` — The attendant never sees the customer payment screen. Their interface is hidden behind
a swipe-up gesture. **Three actions only:** FILL UP AUTHORISE, AUTHORISE CASH, CASH RECEIVED.
"That is everything an attendant ever needs to do."

`ATT-02` — The menu must **not** be accessible to customers during normal use.

`ATT-03` — Button enable rules:

```
FILL_UP_AUTHORISE → enabled when state = IDLE
AUTHORISE_CASH    → enabled when state = IDLE
CASH_RECEIVED     → enabled ONLY when state = FILLUP_AWAITING_CASH
```

`ATT-04` — CASH RECEIVED is greyed out and not tappable unless a fill-up transaction is awaiting
cash confirmation. Rationale given: **prevents accidental taps from logging phantom cash
transactions.**

`ATT-05` — Gesture spec:

```
GestureDetector detects upward swipe from bottom 20% of screen
→ slides up attendant overlay (translateY animation)
→ swipe down or tap outside = dismiss
```

`ATT-06` — Menu labels and subtitles: "FILL UP AUTHORISE / Open-ended · nozzle shuts",
"AUTHORISE CASH ₦___ / Enter amount · cutoff auto", "CASH RECEIVED / Disabled — no pending".
Footer: "Swipe down to return to customer screen".

`ATT-07` — **In V1 there are no roles.** Anyone with access to the pump screen can perform any
attendant action. Role-based access (attendant ID, manager approval) is V2.

> Naming inconsistency in the source: the prose says `AWAITING_CASH_CONFIRM`, the code block says
> `FILLUP_AWAITING_CASH`. Same state. See `DELTA-10`.

---

## 7. Power and connectivity

`PWR-01` — NEPA cuts (0ms): mains drops, UPS switchover in <20ms, Android OS detects nothing.

`PWR-02` — Generator start (0–8 seconds): system runs on the LiFePO4 battery buffer.

`PWR-03` — Resumption: continuous operation. **Power event logged.**

`PWR-04` — Recovery target: <30 seconds from power restoration to correct litre count showing on
the Android screen (`METRIC-05`).

> **Gap.** The spec's "Problem 2: Connectivity" section is truncated in the source capture. What
> survives elsewhere: 4G LTE dual SIM (`HW-A-08`), offline SQLite `PENDING_SYNC` queue
> (`SW-04`), USSD/SMS as the offline payment path (`FLOW-5-*`), and backend sync on reconnect.
> The inbound-push mechanism is **not specified anywhere in these documents** — see `OQ-02`.

---

## 8. Regulatory constraints

`REG-01` — **NMDPRA** governs all downstream petroleum. Key contact: Metrology department, Lagos
office (Mobolaji Bank Anthony Way, Ikeja). The spec states the NMDPRA briefing is
**non-negotiable**.

`REG-02` — **SON** governs accuracy standards under **NIS 348**. Requirement stated bluntly:
**the adapter must be read-only.**

`REG-03` — Positioning: frame the product as the government's compliance tool for digital
mandates, not as a device that touches metrology.

`REG-04` — The NMDPRA one-pager must state the technical approach as "read-only optical tap on
existing flow meter, no modification to calibrated components."

`REG-05` — Acceptance: NMDPRA field trial letter or written acknowledgement on file
(`METRIC-12`).

**Engineering consequence.** `HW-C-08` (pulse-per-litre constant sealed post-calibration) is a
metrology requirement, not a convenience choice. A calibration constant that a station manager
can edit at runtime is a tamper surface under `REG-02`. See `DELTA-02`.

---

## 9. Acceptance criteria

### 9.1 Success metrics — "V1 is done when all of these are true, no exceptions"

| ID | Metric | Definition | Target |
|----|--------|-----------|--------|
| `METRIC-01` | Accuracy | Litres counted by adapter vs calibrated vessel over 100L dispensed | ±0.5L max |
| `METRIC-02` | Fill-up accuracy | Android screen vs calibrated vessel during fill-up (3 runs) | ±0.5L max |
| `METRIC-03` | Power cut survival | Transaction count resumes correctly after power cut — bench, 10 consecutive | 10/10 |
| `METRIC-04` | Fill-up power cut | UPS bridges cut during open-ended fill-up, correct recovery | 5/5 |
| `METRIC-05` | Recovery time | Power restored to correct litre count showing on Android screen | <30 sec |
| `METRIC-06` | Parallel accuracy | Daily variance vs station stock records during 14-day parallel run | <1% every day |
| `METRIC-07` | Uptime (field) | System available for transactions during trading hours, 30-day period | ≥99% |
| `METRIC-08` | Double-billing | Customer charged twice for one transaction — entire field test period | **Zero** |
| `METRIC-09` | Unauthorised dispense | Pump unlocks without confirmed payment — entire field test period | **Zero** |
| `METRIC-10` | Fraud catch | Audit trail reveals dispense/payment gap invisible to manual system | At least 1 |
| `METRIC-11` | Operator NPS | Station owner satisfaction — 6-month check-in interview | ≥8/10 |
| `METRIC-12` | Regulatory | NMDPRA field trial letter or written acknowledgement on file | In hand |

### 9.2 Lab test protocol — T-01 through T-12

Every test must be documented: test ID / date run / who ran it / pass or fail / evidence file
(video link or CSV).

| ID | Test | Criterion |
|----|------|-----------|
| `TEST-01` | Flow accuracy | ±0.5L over 10L, 20L, 50L × 3 runs each |
| `TEST-02` | Power cut mid-transaction | Count resumes correctly, no double-billing |
| `TEST-03` | Power cut at payment moment | No charge to customer, pump stays locked |
| `TEST-04` | Extended 30-minute outage | All transaction records intact on restore |
| `TEST-05` | Offline USSD payment | Pump unlocks within 90 seconds of SMS confirmation |
| `TEST-06` | Manipulation attempt | Pressing old pump recall/reset buttons does nothing to Android display |
| `TEST-07` | Heat soak | 8 hours at 55°C, zero failures in any component |
| `TEST-08` | Voltage sag | 9V to 36V input sweep, continuous operation throughout |
| `TEST-09` | 100-transaction endurance | Zero discrepancies between SQLite log and shift report |
| `TEST-10` | Isolation test | 240V fault applied to adapter input, no damage propagates |
| `TEST-11` | Fill-up accuracy | 3 fill-up runs |
| `TEST-12` | Power cut during fill-up | 5 runs |

### 9.3 Test protocol detail from Prototype_specs_5

`TEST-11-detail` — Dispense via fill-up mode until nozzle auto-shuts. Measure actual litres into
a calibrated vessel. Compare to Android screen count. Run 3 times. **Evidence required: video +
3-row table (run / actual / screen reading / variance).**

`TEST-12-detail` — Start fill-up dispense. Cut mains power mid-flow. Confirm UPS bridges.
**Confirm Android does not falsely detect nozzle shutoff during the cut.** Confirm fill-up
resumes and runs to real nozzle shutoff. Run 5 times. Evidence: video of all 5 runs.

`TEST-01-detail` — Dispense exactly 10L into a calibrated measuring jug. Compare pulse adapter
reading. Record each run in a table: run number / actual / screen reading / variance. 5 runs.
If any run fails ±0.5%, the demo is rescheduled — failing data is not presented.

---

## 10. What the spec says about ownership

- **Ekomobong** — Android: pump app, all five flows, attendant UI, nozzle shutoff detection,
  dynamic QR, SMS listener, SQLite queue, kiosk/Device Owner mode, USB serial comms.
- **Olonade** — Embedded: pulse-tap adapter firmware, EEPROM persistence, optocoupler front end,
  relay board, locating and tapping the flow meter pulse wire in the dispenser electronics bay.
- **Olayemi** — Coordination: field log, parallel run tracker, weekly check-ins, demo summaries,
  NMDPRA one-pager. Explicitly does not write firmware or Android code.
- **Founder** — CAC registration, NMDPRA meetings, component ordering, API spec definition.

`OWN-01` — The tracker assigns **"Define and share the pump webhook API spec"** to
**Founder + software team**, not to Ekomobong. The API contract is an inbound dependency.

---

## 11. Deltas — where this spec and the current implementation disagree

These are **open items, not bugs**. Each needs a decision before code moves.

`DELTA-01` — **EEPROM totaliser is specified but absent from the codebase.**
`HW-C-03`/`HW-C-04` require a cumulative pulse count in adapter EEPROM, surviving power cuts and
independently readable. Nothing in the repo implements this. Scope as a firmware sub-phase.
Constraints that hold: write only at end-of-dispense via `EEPROM.put` (never per pulse — AVR
EEPROM is ~100k cycles and would be destroyed within an hour at 50 pps); it remains a reporting
figure, with the app as system of record.

`DELTA-02` — **Where the K-factor lives.** `HW-C-08` says the pulse-per-litre constant is loaded
at commissioning and **sealed post-calibration**, on the board. The competing proposal puts it in
the operator config screen behind a manager PIN. Under `REG-02` (SON / NIS 348, adapter must be
read-only) a runtime-editable calibration constant is a tamper surface. Suggested resolution:
board holds the sealed constant and reports it in the `BOOT` frame; app treats it as read-only
and displays it; operator config keeps fuel type and price only. Bench can stay app-side until a
sealed board exists.

`DELTA-03` — **Power-cut recovery rule.** `SW-05` requires `max(adapter_eeprom, android_persisted)`
and the recovered delta belongs somewhere explicit. The current `PulseAccumulator.onBoot()`
adopts whatever `BOOT` carries as a new baseline and contributes 0 fuel. These are different
behaviours. Pulses the adapter counted while the tablet was down must land either on the live
transaction or in a reconciliation log — not be silently absorbed.

`DELTA-04` — **Payment direction is inverted.** The spec has the backend calling
`POST /pump/authorise` **into** the pump (`FLOW-1-01`), with the pump receiving a webhook.
The current design is pump-initiated (`POST /api/pump/authorise` outbound, then hybrid
push-plus-poll on `GET /api/pump/transactions/{id}`). Both can be made to work, but the spec's
payload shape, the 5-minute QR expiry (`FLOW-1-04`), and the `mode` field (`SW-A-06`) are written
against the inbound model. Reconcile before the API contract is frozen.

`DELTA-05` — **Payment provider.** The spec references the Balanceè backend and a NIP virtual
account per station (`FLOW-3-02`, `FLOW-3-03`); the tracker references a Moniepoint sandbox
webhook. The current implementation targets Paystack. Confirm which is authoritative.

`DELTA-06` — **USSD is V1-required in the spec, deferred in the current plan.** `SCOPE-04` is one
of the three core claims. `FLOW-5-*` is marked `V1 REQUIRED`. `TEST-05` is a lab gate with a
90-second criterion. Preserving architectural seams (`PaymentProcessor`, `PENDING_SYNC`,
`getPendingSync()`) does not satisfy a gate that requires a working unlock. Either the gate moves
or the work comes back into V1.

`DELTA-07` — **Dispense-control migration contradicts the spec.** `HW-C-11`/`HW-C-12` state the
adapter does not know which mode it is in, that Android manages mode, and that "Olonade's
firmware is correct as-is for both modes." A `DISPENSE:<target_pulses>` protocol moving the
control loop into firmware inverts that. If it goes ahead it is a spec amendment requiring
sign-off, not a refactor.

`DELTA-08` — **UPS capacity conflict.** Prototype spec says 10Ah/12V/120Wh (`HW-D-02`);
Screen_spec_8 says 12V 20Ah. Affects runtime budget and BOM cost.

`DELTA-09` — **`PULSES_PER_LITRE = 100` is hardcoded** at `CustomerViewModel.kt:72` and mirrored
at `MockPulseSource.kt:106`. The spec's canonical direction is `litresPerPulse` (`FLOW-3-05`),
and the real value comes from `TEST-01` calibration. Collapse to a single definition before the
real number arrives.

`DELTA-10` — **State naming.** `AWAITING_CASH_CONFIRM` (prose) vs `FILLUP_AWAITING_CASH` (code)
in Screen_spec_7. Pick one.

`DELTA-11` — **Emergency stop is specified as hardware** (`HW-A-06`), not a software button.
Confirm it exists on the ordered display unit and is wired to the relay path.

`DELTA-12` — **Battery SoC over I²C** (`HW-D-07`) with an operator alert below 20% is not in the
current architecture.

`DELTA-13` — **USB cable drop mid-fill leaves the relay open.** Not addressed anywhere in the
spec. Requires a firmware-level dead-man watchdog on the adapter. Spec gap, not a spec conflict.

---

## 12. Open questions

`OQ-01` — **Flow meter K-factor.** No value anywhere in the spec. Derived by `TEST-01`
(5 runs × 10L into a calibrated jug, ±0.5%). Blocked on Olonade identifying the meter from the
dispenser service manual. Everything downstream — litre display, naira cutoffs, relay open
points — scales off this constant.

`OQ-02` — **Push channel to the pump.** The spec's connectivity section is truncated and no
inbound-push mechanism is specified. Inbound HTTP to a 4G device behind carrier NAT is not
reliable. FCM with WebSocket fallback is the working assumption; blocked on confirming Google
Play Services availability on the ordered tablet.

`OQ-03` — **EEPROM shape.** "Stores last 10,000 pulse counts" (`HW-C-04`) reads as either a
single cumulative totaliser with rollover headroom or a 10,000-entry ring buffer. These are very
different firmware. Olonade to confirm.

`OQ-04` — **Does the `BOOT` frame carry the K-factor**, or only the cumulative count? Depends on
how `DELTA-02` resolves.

`OQ-05` — **Bench pulse meter characteristics.** Output type (reed switch / hall / NPN
open-collector), voltage swing, datasheet pulses-per-litre, max flow rate → peak pulse frequency,
and whether external excitation is needed. Kelvin owns the bench meter. Note the bench BOM
already includes 4N35 optocouplers (`HW-C-10`), so the isolated front end is funded.

`OQ-06` — **Server-side rounding contract.** The client rule is fixed: floor to 2dp
(`FLOW-4-03`). The server must round identically or the `amount === expectedLitres × price`
check will reject valid sales.

`OQ-07` — **Money units.** Naira vs kobo across the API surface. The spec's payload uses
`amount_naira` and `price_per_litre: 870` — whole naira. Confirm this holds for all endpoints.

`OQ-08` — **Late-payment reconciliation policy.** What happens when a webhook or SMS lands after
the 5-minute QR expiry (`FLOW-1-04`) has already cancelled the transaction.

`OQ-09` — **Canonical API spec.** `OWN-01` assigns it to founder + software team. Check for
OpenAPI/Swagger at `/docs`, `/swagger`, `/openapi.json`, `/redoc` before asking.

`OQ-10` — **Staging access** with a test activation code.

---

## Appendix A — How to re-extract the source documents

The spec files carry `.pdf` extensions but are ZIP archives of JPEG page screenshots. The
bundled `.txt` files inside each archive are empty (0 bytes). `pdftotext` and `pypdf` both
return nothing.

```python
import zipfile, os

for name in ['Prototype_specs_1', 'Screen_spec_1', 'V1_EXECUTION_TRACKER']:
    z = zipfile.ZipFile(f'{name}.pdf')
    os.makedirs(name, exist_ok=True)
    for info in z.infolist():
        if info.filename.endswith('.jpeg'):
            z.extract(info, name)
```

```bash
tesseract page.jpeg - --psm 3
```

**Caveat.** `Prototype_specs_*` pages are light-on-white and OCR cleanly. `Screen_spec_*` pages
are dark-mode UI screenshots with low-contrast grey body text; OCR fails on them badly, including
after inversion, upscaling and thresholding. Those pages were transcribed by reading the images
directly rather than by OCR.

## Appendix B — Source map

| Document | Contents |
|----------|----------|
| `Prototype_specs_1` | Scope discipline, three core claims, signal chain, out-of-scope list |
| `Prototype_specs_2` | Android unit hardware, pulse-tap adapter, UPS, relay board, BOM |
| `Prototype_specs_3` | Software architecture, offline queue, power-cut recovery |
| `Prototype_specs_4` | Power sequence. Connectivity section truncated |
| `Prototype_specs_5` | Test protocol, Phase 1 lab testing |
| `Prototype_specs_6` | Timeline. Almost entirely cropped in capture |
| `Prototype_specs_7` | NMDPRA and SON regulatory groundwork |
| `Screen_spec_1` | Idle + mode select, top-level state machine, price push rule |
| `Screen_spec_2` | Flow 1 — fixed pre-pay, webhook payload, relay logic |
| `Screen_spec_3` | Flow 2 — fill-up cash, nozzle shutoff detection |
| `Screen_spec_4` | Flow 3 — fill-up digital, dynamic QR generation |
| `Screen_spec_5` | Flow 4 — cash fixed, litre cutoff calculation and rounding |
| `Screen_spec_6` | Flow 5 — USSD offline, SMS parsing |
| `Screen_spec_7` | Attendant interface, swipe-up menu, enable rules |
| `Screen_spec_8` | Component-by-component logic updates (A–F) |
| `V1_EXECUTION_TRACKER` | Roles, weekly cadence, T-01…T-12, metrics table, BOM order |
| `success_metrics` | Gate thresholds, cost reality check |
