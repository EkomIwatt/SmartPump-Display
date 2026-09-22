/*
 * SmartPump Display — pulse-adapter firmware (Phase 7a + 7g + 11)
 * Target: Arduino Uno R3 or Mega 2560 (any AVR with two external-interrupt pins)
 *
 * THE PROTOCOL IS SPECIFIED IN docs/serial-protocol.md (revision 2, Phase 11). That document is
 * the authority; this comment is a summary. The framing + checksum here MUST stay byte-for-byte
 * identical to the Android side (SerialFrameParser / UsbSerialRelayController).
 *
 * History: 7a — checksummed framing, relay control, comms-loss watchdog (bench verified
 * 2026-06-11 and 2026-07-10). 7g — wear-levelled EEPROM totaliser + power-fail save. 11 — the
 * adapter owns the cutoff: the app hands it a pulse limit and the board stops the relay itself,
 * in the pulse ISR, and keeps a per-sale session (the "trip meter", OQ #24's session mark).
 *
 * Framing (line-delimited, '\n', USB @ 115200 8N1): <body>*<cs>
 *   <cs> = XOR-8 of every ASCII byte BEFORE the '*', two UPPERCASE hex digits.
 *
 *   app -> device :  PING                 liveness, ~1 Hz — feeds the watchdog
 *                    RLY:1:<limit>:<tag>  arm a session of <limit> pulses and open the relay
 *                                         (same tag as the held session == RES). Bare RLY:1 and
 *                                         anything malformed -> ERR:CMD and NO FUEL.
 *                    RLY:0                relay off; an OPEN session becomes HELD (not ended)
 *                    RES:<tag>            resume the held session under its original limit
 *                    SES?                 report the session without changing anything
 *   device -> app :  PULSE:<count>        lifetime count, throttled
 *                    HB:<count>           keep-alive, ~2 s idle
 *                    BOOT:<count>         once at power-up (EEPROM-restored count)
 *                    ARM:<tag>:<start>    session is OPEN or HELD; sale pulses = count - start
 *                    STOP:<tag>:<cut>     session reached its limit; cut == start + limit
 *                    ERR:<code>           CMD NOSESSION CSUM NOCS WDOG PWR
 *
 *   Worked checksums: PING 10, RLY:0 4D, RLY:1:500:7 4E, RES:7 49, SES? 7A, ARM:7:12000 5A,
 *   STOP:7:12500 19, ERR:CMD 35, ERR:NOSESSION 20, ERR:WDOG 64, PULSE:1 54, HB:0 00, BOOT:0 1C.
 *
 * Two rules carry the safety of the design (spec §4):
 *   1. Only a NEW tag grants a new allowance. Retries, RES, SES? and reconnects can only re-open
 *      what is left under the limit the board already holds.
 *   2. The relay only comes on through RLY:1 (new session) or RES (held session, under its
 *      limit). Nothing re-energises on its own — not the watchdog, not a boot.
 *
 * NOTHING may be printed outside this framing while the app is attached — the app classifies any
 * unframed line as SerialFrame.Invalid. Human-readable banners are behind DEBUG_BANNERS.
 *
 * Comms-loss watchdog: while the relay is on, the app must keep sending PING. If none arrives
 * within HEARTBEAT_TIMEOUT_MS the relay closes on the adapter's own GPIO and the session is HELD.
 * The adapter is the safety authority for the relay; in production it runs off the UPS so it stays
 * alive to enforce this when the tablet or the link does not.
 *
 * EEPROM record (7g, extended in 11): the lifetime count plus the session, wear-levelled over
 * MAX_SLOTS slots, written only when the relay changes state for a session and on power failure —
 * never per pulse. The session part is only TRUSTED on power-up when ENABLE_POWER_FAIL_SAVE is on
 * and the newest record was written with the relay OFF (spec §6.4): an OPEN record means fuel
 * flowed after it, so its count may be stale and resuming from it would re-grant lost pulses.
 *
 * Still NOT here: the sealed pulses-per-litre constant and its CAL frame (OPEN_QUESTIONS #23).
 * The limit is in pulses precisely so this firmware never needs the K-factor.
 */

#include <EEPROM.h>

// ---- Configuration ----------------------------------------------------------------------
#define BAUD 115200

// Pins. PIN_PULSE_IN and PIN_POWER_SENSE must BOTH be external-interrupt capable. On an Uno
// that is pins 2 and 3 only; on a Mega, 2, 3, 18, 19, 20, 21. Pins 2 and 3 are the only pair
// valid on both boards, so they are used here and the button moved to a polled pin.
//
// (The earlier bench sketch attached interrupts to pins 7 and 5. On a Mega
// digitalPinToInterrupt() returns NOT_AN_INTERRUPT for those, which attachInterrupt()'s uint8_t
// parameter turns into 255 — failing its "< EXTERNAL_NUM_INTERRUPTS" guard and silently doing
// nothing. No compile error, no warning, and nothing is ever counted.)
// *** NEVER connect a dispenser pulse line straight to this pin. *** HW-C-02 requires the input
// to accept BOTH 5 V and 12 V signals (Gilbarco / Wayne / Tokheim), and 12 V on an AVR input
// destroys the pin. HW-C-01/HW-C-06 require an optical isolator with 2500 V galvanic isolation,
// and HW-C-09/HW-C-10 name the part: a 4N35, already in the bench BOM.
//
// 4N35 wiring for this sketch (LED side floats with the meter, transistor side with the Arduino):
//   meter pulse (+) --[R]-- 4N35 pin 1 (anode);  4N35 pin 2 (cathode) -- meter ground
//   4N35 pin 5 (collector) -- D2;                4N35 pin 4 (emitter) -- Arduino GND
//   R (about 15 mA through the LED):  5 V line -> 220-270 ohm;  12 V line -> 680 ohm - 1 kohm
// The opto pulls D2 down when the meter pulse is active, which is why this pin is INPUT_PULLUP
// and the interrupt is on FALLING. Do not tie the meter ground to the Arduino ground — keeping
// them separate is the entire point of the isolation.
const uint8_t  PIN_PULSE_IN    = 2;   // INT — flow-meter signal via 4N35 (Uno INT0 / Mega INT4)
const uint8_t  PIN_POWER_SENSE = 3;   // INT — power-fail early warning, ahead of the reservoir cap
const uint8_t  PIN_RELAY       = 7;   // relay module / LED driving the pump solenoid
const uint8_t  PIN_BUTTON      = 4;   // manual pulse inject, to GND — POLLED, no interrupt needed

const bool     RELAY_ACTIVE_LOW   = false; // true for active-LOW relay boards (LOW = energised)

// Where pulses come from. These two are the demo's main control:
//   AUTO true,  BUTTON either -> fuel flows by itself the whole time the relay is open. Hands-off,
//                               but you cannot pause or stop the flow.
//   AUTO false, BUTTON true   -> the button IS the nozzle trigger: hold to flow, release to stop.
//                               Nothing counts unless a button is wired to PIN_BUTTON.
// >>> METER CONFIG 2026-09-02: both OFF — the only pulse source is the real meter on D2. Any
// synthetic source left on would ride on top of the meter and corrupt the calibration. <<<
const bool     ENABLE_AUTO_PULSE  = false; // synthesise pulses while dispensing (hands-off demo)
const bool     ENABLE_BUTTON      = false; // polled D4 inject — off for meter work

// Injection rate for BOTH sources above — 50 pps is ~30 L/min at 100 pulses/L, so a 10 L fill
// takes about 20 s of holding. Raise it to make demo fills quicker.
//
// Note this is a RATE while held, not one pulse per press. One-pulse-per-press would need ~1000
// presses for a 10 L fill at 100 pulses/L, which is why the button is a hold-to-flow control.
// It is also why the button needs no debouncing: it is polled here, never routed through the
// pulse ISR, so contact bounce costs at most a pulse or two of jitter rather than a false count.
const unsigned int  AUTO_PPS      = 50;    // synthetic pulse rate (~30 L/min @ 100 pulses/L)

// Debounce for the REAL meter input, in MICROseconds, applied in the ISR.
//
// Read the arithmetic before changing this: the debounce sets a hard ceiling on countable flow.
//     max_pulses_per_sec = 1e6 / PULSE_DEBOUNCE_US
//     max_litres_per_min = max_pulses_per_sec * 60 / pulses_per_litre
// At the default 250 us that is 4000 pps — about 2400 L/min at 100 pulses/L, i.e. far above any
// dispenser, while still swallowing contact ringing. Set to 0 to disable entirely.
//
// It is deliberately NOT the 150 ms used for the bench pushbutton. 150 ms caps counting at 6.67
// pps — roughly 4 L/min against a real dispenser's 30-50 — and, because the loss is flow-rate
// dependent, a K-factor derived through it is not a constant at all. Calibration task T-01
// (5 x 10 L, +/-0.5%) is invalid if run with a debounce anywhere near that. The button does not
// need debouncing here because it is polled and injects at AUTO_PPS while held, never via the ISR.
// >>> METER CONFIG 2026-09-02: back to 250 us from the 150 ms demo value. <<<
//
// 250 us gives a 4000 pps ceiling — about 2400 L/min at 100 pulses/L, or 530 L/min even at a
// high-resolution 450 pulses/L meter. Comfortably clear of a dispenser's 30-50 L/min, while still
// filtering electrical ringing on the opto output.
//
// CHECK THIS AGAINST THE ACTUAL METER before the first calibration run:
//     peak_pps = max_flow_L_per_min / 60 * pulses_per_litre
//     PULSE_DEBOUNCE_US must be well under 1e6 / peak_pps
// A 450 pulses/L meter at 50 L/min peaks at 375 pps = 2.67 ms between pulses, so 250 us has ~10x
// headroom. If the meter turns out to be far higher resolution, drop this or set it to 0.
//
// Do NOT restore the 150 ms demo value with a meter attached. It caps counting at 6.67 pps
// (~4 L/min), the loss varies with flow rate so the error is not a constant offset, and TEST-01
// would return five agreeing and entirely wrong runs against its +/-0.5% gate.
const unsigned long PULSE_DEBOUNCE_US = 250;      // us — METER value (button demo used 150000)

const unsigned long HB_INTERVAL_MS   = 2000; // keep-alive cadence when idle
const unsigned long PULSE_TX_MIN_MS  = 30;   // min gap between PULSE frames (throttle the stream)

// Comms-loss heartbeat watchdog: while the relay is energised the app must keep sending PING
// (~every 1s). If none arrives for this long we fail the relay closed. Generous enough not to
// false-trip on USB latency, tight enough to bound uncontrolled flow. NOTE the app->device PING
// is distinct from the device->app HB.
const unsigned long HEARTBEAT_TIMEOUT_MS = 3000;

// Power-fail save. The sense line is expected ACTIVE-LOW ("power good" holds the pin low, e.g.
// via an opto energised from the incoming rail); losing power releases it and the internal
// pull-up drags it high, so we trigger on RISING. Flip to FALLING if the sense circuit is
// inverted. With nothing wired the pull-up holds the pin high and the edge never comes, so an
// unwired rig simply never saves on power loss — it does not false-trigger.
// DEFAULT false. Olonade's Mega rig HAS the sense circuit (learned 2026-09-22); the Uno bench
// simulator does not. Switch it on only on a board with the circuit, after confirming the pin (3)
// and the edge below match it — on a board without one it is actively harmful: D3 was the button
// pin before 7g, so on old bench wiring a button press fires onPowerFail() on release, and the
// board commits to EEPROM and HALTS, which looks exactly like a hang mid-demo.
//
// Phase 11: this flag ALSO decides whether the sale's session survives a reboot (spec §6.4) —
// off, a reboot always ends it; on, it comes back when the newest record was written with the
// relay off. The capacitor must hold the rail for the power-fail commit: up to 25 bytes at ~3.4 ms
// each, ~85 ms worst case (7g's 12-byte record needed ~40 ms). Check the hold-up covers it.
const bool ENABLE_POWER_FAIL_SAVE = false;
const int  POWER_FAIL_EDGE        = RISING;

// Human-readable banners for a bare Serial Monitor. MUST be false whenever the app is attached:
// unframed lines are parsed as SerialFrame.Invalid.
const bool DEBUG_BANNERS = false;

// Sanity bound on a RLY:1 limit, in pulses (spec §7). 10 000 L at 100 pulses/L, 2 222 L at 450.
// The app sends far less; this only refuses a garbled or absurd frame.
const unsigned long MAX_LIMIT = 1000000UL;

// ---- EEPROM wear-levelled record --------------------------------------------------------
// Field order is load-bearing. EEPROM.put() writes ascending, so "crc" lands LAST and acts as
// the commit marker: a write torn by a power cut leaves a stale/garbage crc, recovery rejects
// that slot, and the previous slot's older-but-valid data wins.
//
// (The bench sketch ordered "sequence" first with no crc. A cut mid-save could therefore commit
// a new highest sequence against a stale pulseCount left from a full lap of the ring — and
// recovery, picking purely on sequence, would elect exactly that corrupt slot. The failure case
// was the one the mechanism exists to survive.)
// A format marker leads the record. Without one, "is this slot ours?" rests entirely on a CRC-16,
// which foreign bytes pass about 1 time in 65,536 — and with many slots that is a real chance per
// boot of adopting someone else's data as a pulse count. Not hypothetical: this board has held
// records in two earlier layouts (the pre-7g bench sketch's 8 bytes, and 7g's 12).
//
// Bump SLOT_MAGIC whenever the struct changes, so an older layout is rejected rather than
// misread. 0x5350 was 7g's 12-byte record; 0x5351 is Phase 11's 25-byte record. The bump means a
// board's 7g totaliser reads as absent ONCE, on the first boot of this firmware, and restarts from
// zero — do that before the 14-day run, never during it.
const uint16_t SLOT_MAGIC = 0x5351;   // "SQ" — erased cells read 0xFFFF, so they never collide

// Session states (spec §4). Stored in the record as one byte.
const uint8_t SES_NONE = 0;
const uint8_t SES_OPEN = 1;   // relay on, limit armed
const uint8_t SES_HELD = 2;   // relay off (RLY:0, watchdog, power-fail), resumable under its limit
const uint8_t SES_DONE = 3;   // limit reached; never re-opened

struct PumpData {
  uint16_t      magic;        // bytes  0-1  — written first; identifies the record as ours
  unsigned long pulseCount;   // bytes  2-5
  unsigned long sequence;     // bytes  6-9
  unsigned long tag;          // bytes 10-13 — session, only trusted per spec §6.4
  unsigned long start;        // bytes 14-17
  unsigned long limit;        // bytes 18-21
  uint8_t       state;        // byte  22    — SES_*; OPEN means "relay was on when written"
  uint16_t      crc;          // bytes 23-24 — written last: commit marker
};

const int MAX_SLOTS         = 40;               // 40 * 25 B = 1000 B; fits Uno (1 KB) and Mega (4 KB)
const int SLOT_SIZE         = sizeof(PumpData);
const int EEPROM_START_ADDR = 0;

static_assert(sizeof(PumpData) == 25, "PumpData layout changed — bump SLOT_MAGIC and re-check MAX_SLOTS");
static_assert(MAX_SLOTS * SLOT_SIZE <= E2END + 1, "EEPROM ring does not fit this MCU");

// ---- State ------------------------------------------------------------------------------
// Everything the pulse ISR reads or writes is volatile, and anything wider than a byte is only
// touched outside the ISR with interrupts off.
volatile unsigned long pulseCount = 0;   // free-running lifetime count
volatile bool powerFailLatched = false;
volatile bool dispensing = false;        // relay energised

volatile uint8_t       sesState = SES_NONE;
volatile unsigned long sesTag   = 0;
volatile unsigned long sesStart = 0;
volatile unsigned long sesLimit = 0;
volatile bool          stopPending = false;  // ISR cut the relay at the limit; loop() reports it

unsigned long lastFrameMs     = 0;  // last frame of ANY type sent (gates HB)
unsigned long lastPulseTxMs   = 0;  // last PULSE frame sent (throttle)
unsigned long lastAutoMs      = 0;  // last synthetic-pulse tick
unsigned long lastSentCount   = 0;  // count at the last PULSE frame
unsigned long lastHeartbeatMs = 0;  // last PING received from the app (feeds the watchdog)
unsigned long lastSavedCount  = 0;  // count at the last EEPROM commit (skips no-op saves)

unsigned long currentSequence = 0;
int activeSlotIndex = MAX_SLOTS - 1;  // so the first save lands on slot 0

char rxBuf[40];
uint8_t rxLen = 0;

// ---- Relay ------------------------------------------------------------------------------
// Pin writes only; safe from an ISR. Callers own the session state.
void writeRelay(bool on) {
  dispensing = on;
  bool level = RELAY_ACTIVE_LOW ? !on : on;
  digitalWrite(PIN_RELAY, level ? HIGH : LOW);
  digitalWrite(LED_BUILTIN, on ? HIGH : LOW);    // visual cue on a bare board
}

// ---- Pulse counter + cutoff -------------------------------------------------------------
// Count one pulse and apply the limit. Call ONLY with interrupts disabled (from the ISR, or from
// addPulses). Every pulse source goes through here, so the demo sources are cut exactly like the
// meter (spec §6.7). The cut lands on the pulse that makes count - start == limit, so the STOP
// report's cut is always start + limit. Unsigned subtraction: correct across a counter wrap.
void countPulseLocked() {
  pulseCount++;
  if (sesState == SES_OPEN && (pulseCount - sesStart) >= sesLimit) {
    writeRelay(false);
    sesState = SES_DONE;
    stopPending = true;                          // STOP + EEPROM commit happen in loop(), never here
  }
}

void onPulseEdge() {
  if (PULSE_DEBOUNCE_US > 0) {
    static unsigned long lastEdgeUs = 0;
    // micros() is ISR-safe on AVR (it reads TCNT0 and folds in a pending overflow), unlike
    // millis(), which cannot advance while we are in here.
    unsigned long nowUs = micros();
    if (nowUs - lastEdgeUs < PULSE_DEBOUNCE_US) return;
    lastEdgeUs = nowUs;
  }
  countPulseLocked();
}

unsigned long readCount() {
  noInterrupts();
  unsigned long c = pulseCount;                 // 4-byte read is non-atomic on AVR
  interrupts();
  return c;
}

void addPulses(unsigned long n) {
  noInterrupts();
  while (n--) countPulseLocked();
  interrupts();
}

// ---- Framing ----------------------------------------------------------------------------
uint8_t xor8(const char* s) {
  uint8_t c = 0;
  while (*s) c ^= (uint8_t)*s++;
  return c;
}

// Send "<body>*<CS>\n" for an already-formed body (e.g. "ERR:CSUM").
void sendRaw(const char* body) {
  char line[48];
  snprintf(line, sizeof(line), "%s*%02X", body, xor8(body));
  Serial.println(line);                          // println adds '\n'
}

// Send a numeric-payload frame, e.g. PULSE:42817 / HB:0 / BOOT:0.
void sendFrame(const char* type, unsigned long value) {
  char body[24];
  snprintf(body, sizeof(body), "%s:%lu", type, value);
  sendRaw(body);
}

// Send a two-number frame, e.g. ARM:7:12000 / STOP:7:12500.
void sendFrame2(const char* type, unsigned long a, unsigned long b) {
  char body[32];
  snprintf(body, sizeof(body), "%s:%lu:%lu", type, a, b);
  sendRaw(body);
}

void sendError(const char* code) {
  char body[24];
  snprintf(body, sizeof(body), "ERR:%s", code);
  sendRaw(body);
}

// ---- EEPROM record ----------------------------------------------------------------------
uint16_t crc16(const uint8_t* data, uint8_t len) {   // CRC-16/CCITT-FALSE
  uint16_t crc = 0xFFFF;
  for (uint8_t i = 0; i < len; i++) {
    crc ^= (uint16_t)data[i] << 8;
    for (uint8_t b = 0; b < 8; b++) {
      crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
    }
  }
  return crc;
}

uint16_t slotCrc(const PumpData& d) {
  return crc16((const uint8_t*)&d, sizeof(PumpData) - sizeof(d.crc));
}

// Commit [d] (magic/sequence/crc filled in here) to the next slot in the ring. Safe to be
// interrupted by the power-fail ISR: activeSlotIndex advances only AFTER the put returns, so the
// ISR targets the same slot and simply completes a consistent record over the top of the partial
// one.
void saveRecord(PumpData d) {
  int nextSlot = (activeSlotIndex + 1) % MAX_SLOTS;
  d.magic    = SLOT_MAGIC;
  d.sequence = currentSequence + 1;
  d.crc      = slotCrc(d);
  EEPROM.put(EEPROM_START_ADDR + nextSlot * SLOT_SIZE, d);
  activeSlotIndex = nextSlot;
  currentSequence = d.sequence;
  lastSavedCount  = d.pulseCount;
}

// Count and session, read together so the record is one consistent instant. Interrupts must
// already be off, or be safe to toggle (not from inside an ISR — use it there with them off).
PumpData snapshotLocked() {
  PumpData d;
  d.pulseCount = pulseCount;
  d.tag        = sesTag;
  d.start      = sesStart;
  d.limit      = sesLimit;
  d.state      = sesState;
  return d;
}

PumpData snapshot() {
  noInterrupts();
  PumpData d = snapshotLocked();
  interrupts();
  return d;
}

// Commit if anything worth keeping changed. With the power-fail save off, only the count matters
// (7g's rule: skip no-op saves to spare the EEPROM). With it on, a session change is also worth a
// write — every relay transition must leave a record saying whether the relay was on (spec §6.4).
void commit(bool sessionChanged) {
  PumpData d = snapshot();
  if (d.pulseCount != lastSavedCount || (ENABLE_POWER_FAIL_SAVE && sessionChanged)) saveRecord(d);
}

// Scan every slot for the highest sequence that still passes its CRC, then restore the count
// and — only if it can be trusted — the session.
void recoverLatestState() {
  unsigned long bestSeq = 0;
  int bestSlot = -1;
  PumpData t;

  for (int i = 0; i < MAX_SLOTS; i++) {
    EEPROM.get(EEPROM_START_ADDR + i * SLOT_SIZE, t);
    if (t.magic != SLOT_MAGIC) continue;          // erased, or written by other firmware/layout
    if (t.crc != slotCrc(t)) continue;            // torn write or corruption — ignore
    if (bestSlot < 0 || t.sequence > bestSeq) {
      bestSeq  = t.sequence;
      bestSlot = i;
    }
  }

  sesState = SES_NONE;
  sesTag = 0;
  sesStart = 0;
  sesLimit = 0;

  if (bestSlot < 0) {                             // first boot, or every slot unusable
    currentSequence = 0;
    pulseCount      = 0;
    activeSlotIndex = MAX_SLOTS - 1;              // first save goes to slot 0
  } else {
    EEPROM.get(EEPROM_START_ADDR + bestSlot * SLOT_SIZE, t);
    currentSequence = t.sequence;
    pulseCount      = t.pulseCount;
    activeSlotIndex = bestSlot;

    // Spec §6.4. A HELD or DONE record was written with the relay OFF, so no metered fuel could
    // flow after it and its count is current: the session may come back. An OPEN record was
    // written as the relay came ON, so fuel flowed after it and the power-fail save never landed —
    // its count is stale, and resuming from it would re-grant every pulse the board forgot.
    // With the flag off no power-fail save exists at all, so nothing is ever trusted.
    bool relayWasOff = (t.state == SES_HELD || t.state == SES_DONE);
    if (ENABLE_POWER_FAIL_SAVE && t.tag != 0 && t.limit != 0 && relayWasOff) {
      sesState = t.state;
      sesTag   = t.tag;
      sesStart = t.start;
      sesLimit = t.limit;
    }
  }
  lastSavedCount = pulseCount;
}

// ---- Power-fail ISR ---------------------------------------------------------------------
// Ordering is deliberate: fuel off first (a couple of register writes), then the EEPROM commit,
// then a best-effort notice, then halt. The reservoir capacitor must hold the rail up long
// enough for the commit — worst case ~25 x 3.3 ms, though EEPROM.put() skips bytes that already
// match, so the usual delta (count, state, sequence, crc) is far cheaper than that bound.
void onPowerFail() {
  if (powerFailLatched) return;
  powerFailLatched = true;

  writeRelay(false);                                         // safety before bookkeeping
  if (sesState == SES_OPEN) sesState = SES_HELD;             // relay is off: the record says so

  detachInterrupt(digitalPinToInterrupt(PIN_PULSE_IN));      // protect the write
  detachInterrupt(digitalPinToInterrupt(PIN_POWER_SENSE));

  saveRecord(snapshotLocked());                              // interrupts are already off here

  // Best effort — the link may already be gone. HardwareSerial drains its buffer by polling
  // when interrupts are disabled, so both of these work from inside an ISR; without the flush
  // the bytes would sit in the buffer forever once we spin below.
  sendError("PWR");
  Serial.flush();

  digitalWrite(LED_BUILTIN, HIGH);
  while (true) { }                                           // hold until the rail collapses
}

// ---- Session commands (spec §4) ---------------------------------------------------------
// Reply with what the board holds for the session: ARM while OPEN/HELD, STOP once DONE.
void reportSession(uint8_t state, unsigned long tag, unsigned long start, unsigned long limit) {
  if (state == SES_DONE) sendFrame2("STOP", tag, start + limit);
  else                   sendFrame2("ARM", tag, start);
}

// RES:<tag> — and a same-tag RLY:1, which is treated exactly the same.
void cmdResume(unsigned long tag) {
  bool opened = false, finished = false;
  lastHeartbeatMs = millis();                    // seed BEFORE the relay can come on

  noInterrupts();
  if (sesState == SES_NONE || sesTag != tag) {
    interrupts();
    sendError("NOSESSION");
    return;
  }
  if (sesState == SES_HELD) {
    // Coast pulses while HELD can carry the count past the limit; such a session is used up.
    if ((pulseCount - sesStart) >= sesLimit) { sesState = SES_DONE; finished = true; }
    else                                     { sesState = SES_OPEN; writeRelay(true); opened = true; }
  }
  uint8_t s = sesState; unsigned long st = sesStart, lim = sesLimit;
  interrupts();

  if (opened || finished) commit(true);          // relay on (OPEN) / session over (DONE)
  reportSession(s, tag, st, lim);
}

// RLY:1:<limit>:<tag>
void cmdArm(unsigned long limit, unsigned long tag) {
  noInterrupts();
  bool sameTag = (sesState != SES_NONE && sesTag == tag);
  interrupts();
  if (sameTag) { cmdResume(tag); return; }       // rule 1: a retry never re-latches or re-limits

  lastHeartbeatMs = millis();                    // seed BEFORE the relay comes on
  noInterrupts();
  // A different tag discards whatever session was held (spec §4): the app only arms a new sale
  // when it believes nothing is open.
  sesTag      = tag;
  sesStart    = pulseCount;
  sesLimit    = limit;
  stopPending = false;
  sesState    = SES_OPEN;
  writeRelay(true);
  unsigned long st = sesStart;
  interrupts();

  commit(true);                                  // flag on: the record now says "relay on"
  sendFrame2("ARM", tag, st);
}

// RLY:0 — relay off; an OPEN session is HELD, not ended (the app sends this on every boot).
void cmdRelayOff() {
  noInterrupts();
  writeRelay(false);
  bool changed = (sesState == SES_OPEN);
  if (changed) sesState = SES_HELD;
  interrupts();
  commit(changed);                               // end of dispense — 7g's agreed commit point
}

// SES? — report only.
void cmdQuery() {
  noInterrupts();
  uint8_t s = sesState; unsigned long t = sesTag, st = sesStart, lim = sesLimit;
  interrupts();
  if (s == SES_NONE) sendError("NOSESSION");
  else               reportSession(s, t, st, lim);
}

// ---- Inbound command parsing ------------------------------------------------------------
// Strict unsigned decimal (spec §1): 1-10 digits, no sign, fits 32 bits. Advances *p past it.
bool parseU32(const char** p, unsigned long* out) {
  const char* s = *p;
  unsigned long v = 0;
  uint8_t n = 0;
  while (*s >= '0' && *s <= '9') {
    uint8_t d = (uint8_t)(*s - '0');
    if (++n > 10 || v > (0xFFFFFFFFUL - d) / 10UL) return false;
    v = v * 10UL + d;
    s++;
  }
  if (n == 0) return false;
  *out = v;
  *p = s;
  return true;
}

void processLine(char* line) {
  char* star = strrchr(line, '*');
  if (!star || star == line || *(star + 1) == '\0') { sendError("NOCS"); return; }
  *star = '\0';
  uint8_t want = (uint8_t) strtol(star + 1, NULL, 16);
  if (xor8(line) != want) { sendError("CSUM"); return; }

  if (strcmp(line, "PING") == 0) { lastHeartbeatMs = millis(); return; }
  if (strcmp(line, "RLY:0") == 0) { cmdRelayOff(); return; }
  if (strcmp(line, "SES?") == 0) { cmdQuery(); return; }

  const char* p;
  unsigned long a, b;
  if (strncmp(line, "RLY:1:", 6) == 0) {
    // A bare "RLY:1" does not match this prefix and falls through to ERR:CMD: every open must
    // carry a limit, and a malformed one fails to NO fuel, never to unlimited.
    p = line + 6;
    if (parseU32(&p, &a) && *p == ':' && (++p, parseU32(&p, &b)) && *p == '\0'
        && a >= 1 && a <= MAX_LIMIT && b != 0) {
      cmdArm(a, b);
      return;
    }
  } else if (strncmp(line, "RES:", 4) == 0) {
    p = line + 4;
    if (parseU32(&p, &a) && *p == '\0' && a != 0) { cmdResume(a); return; }
  }
  sendError("CMD");
}

void handleSerial() {
  while (Serial.available()) {
    char c = (char) Serial.read();
    if (c == '\n' || c == '\r') {
      if (rxLen > 0) { rxBuf[rxLen] = '\0'; processLine(rxBuf); rxLen = 0; }
    } else if (rxLen < sizeof(rxBuf) - 1) {
      rxBuf[rxLen++] = c;
    } else {
      rxLen = 0;                                 // overflow — drop the garbled line
    }
  }
}

// ---- Pulse generation (demo / manual) ---------------------------------------------------
bool buttonHeld() {
  return ENABLE_BUTTON && digitalRead(PIN_BUTTON) == LOW;  // INPUT_PULLUP: pressed == LOW
}

void generatePulses() {
  unsigned long now = millis();
  bool injecting = (dispensing && ENABLE_AUTO_PULSE) || buttonHeld();
  if (!injecting) { lastAutoMs = now; return; }  // reset cadence so we don't burst on resume
  unsigned long interval = 1000UL / AUTO_PPS;
  if (now - lastAutoMs >= interval) {
    lastAutoMs += interval;
    addPulses(1);                                // through the cutoff, like a meter pulse
  }
}

// ---- Board-initiated stop report --------------------------------------------------------
// The ISR cut the relay at the limit; report it and commit, from loop() — never from the ISR.
void serviceStop() {
  noInterrupts();
  bool pending = stopPending;
  stopPending = false;
  unsigned long t = sesTag, st = sesStart, lim = sesLimit;
  interrupts();
  if (!pending) return;
  sendFrame2("STOP", t, st + lim);
  commit(true);
}

// ---- Comms-loss heartbeat watchdog ------------------------------------------------------
// Close the relay if the app's PING heartbeat has gone silent while fuel is flowing. The app
// sends PING ~every 1s; missing ~3 in a row trips this. Best-effort ERR:WDOG notice for the
// frozen-but-attached case — on a USB data drop the link is already down so nothing reads it,
// but the relay still closes locally on the adapter's own GPIO, which is the whole point. The
// session is HELD, so a RES from the app resumes it under the same limit.
void serviceRelayWatchdog() {
  if (dispensing && (millis() - lastHeartbeatMs) >= HEARTBEAT_TIMEOUT_MS) {
    noInterrupts();
    writeRelay(false);
    bool changed = (sesState == SES_OPEN);
    if (changed) sesState = SES_HELD;
    interrupts();
    sendError("WDOG");
    commit(changed);                             // the dispense ended here, however abruptly
  }
}

// ---- Frame emission ---------------------------------------------------------------------
void emitFrames() {
  unsigned long now = millis();
  unsigned long c = readCount();
  if (c != lastSentCount && (now - lastPulseTxMs) >= PULSE_TX_MIN_MS) {
    sendFrame("PULSE", c);
    lastSentCount = c;
    lastPulseTxMs = now;
    lastFrameMs = now;
  } else if (now - lastFrameMs >= HB_INTERVAL_MS) {
    sendFrame("HB", c);
    lastFrameMs = now;
  }
}

// ---- Arduino entry points ---------------------------------------------------------------
void setup() {
  pinMode(PIN_RELAY, OUTPUT);
  pinMode(LED_BUILTIN, OUTPUT);
  writeRelay(false);                             // relay OFF on boot — relay-open-on-boot invariant
  if (ENABLE_BUTTON) pinMode(PIN_BUTTON, INPUT_PULLUP);
  pinMode(PIN_PULSE_IN, INPUT_PULLUP);

  recoverLatestState();                          // count (and maybe the session) before any frame

  attachInterrupt(digitalPinToInterrupt(PIN_PULSE_IN), onPulseEdge, FALLING);
  if (ENABLE_POWER_FAIL_SAVE) {
    pinMode(PIN_POWER_SENSE, INPUT_PULLUP);
    attachInterrupt(digitalPinToInterrupt(PIN_POWER_SENSE), onPowerFail, POWER_FAIL_EDGE);
  }

  Serial.begin(BAUD);
  delay(50);

  if (DEBUG_BANNERS) {                           // unframed — never with the app attached
    Serial.println(F("# SmartPump adapter (7a+7g+11)"));
    Serial.print(F("# slot="));     Serial.print(activeSlotIndex);
    Serial.print(F(" seq="));       Serial.print(currentSequence);
    Serial.print(F(" pulses="));    Serial.print(pulseCount);
    Serial.print(F(" session="));   Serial.println(sesState);
  }

  // BOOT carries the restored lifetime count, which is 0 only on a virgin board. A restored
  // session is never announced here and never re-opened here: the app asks with SES? and resumes
  // with RES (rule 2).
  sendFrame("BOOT", readCount());

  unsigned long t = millis();
  lastFrameMs = t;
  lastPulseTxMs = t;
  lastAutoMs = t;
  lastHeartbeatMs = t;  // dispensing == false at boot, so the watchdog stays idle until a dispense
  lastSentCount = readCount();
}

void loop() {
  serviceStop();        // before handleSerial: a new arm must not clear an unreported STOP
  handleSerial();
  serviceRelayWatchdog();
  generatePulses();
  emitFrames();
}
