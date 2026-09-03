/*
 * SmartPump Display — pulse-adapter firmware (Phase 7a + 7g)
 * Target: Arduino Uno R3 or Mega 2560 (any AVR with two external-interrupt pins)
 *
 * Merge of two sketches:
 *   - the Phase 7a adapter (checksummed framing, relay control, comms-loss watchdog) — bench
 *     verified 2026-06-11 and 2026-07-10, merge gate #2;
 *   - the Phase 7g bench sketch (wear-levelled EEPROM totaliser + power-fail save).
 * The 7a half is authoritative on anything the Android side parses; the 7g half adds the
 * non-volatile totaliser required by Prototype Specification v1.0 (Hardware -> pulse-tap adapter
 * board). Previous single-purpose versions are in git history.
 *
 * Speaks the SmartPump serial protocol over USB @ 115200 8N1. This is the other end of the
 * Android UsbSerialConnection / SerialFrameParser, so the framing + checksum here MUST stay
 * byte-for-byte identical to the Kotlin side.
 *
 * Framing (line-delimited, '\n'):
 *   device -> app :  PULSE:<cum>*<cs>     a fuel pulse; <cum> is this adapter's running count
 *                    HB:<cum>*<cs>        keep-alive, ~2s when idle
 *                    BOOT:<cum>*<cs>      sent once at power-up; <cum> is the EEPROM-restored
 *                                         totaliser, so it is NO LONGER always 0 (see below)
 *                    ERR:<code>*<cs>      rejected/garbled inbound command, ERR:WDOG on watchdog
 *                                         trip, ERR:PWR on a power-fail save
 *   app -> device :  PING*<cs>            liveness heartbeat, sent ~every 1s while the link is up
 *                    RLY:1*<cs>           energise relay (fuel on)  — one-shot edge command
 *                    RLY:0*<cs>           de-energise relay (fuel off) — one-shot edge command
 *
 *   <cs> = XOR-8 of every ASCII byte BEFORE the '*', printed as two UPPERCASE hex digits.
 *          e.g. PULSE:1 -> 54, HB:0 -> 00, BOOT:0 -> 1C, RLY:1 -> 4C, RLY:0 -> 4D,
 *               ERR:WDOG -> 64, ERR:PWR -> 2A, PING -> 10
 *
 * NOTHING may be printed outside this framing while the app is attached — SerialFrameParser
 * classifies any unframed line as SerialFrame.Invalid. Human-readable banners are therefore
 * behind DEBUG_BANNERS, which MUST be false in normal operation.
 *
 * The app takes the DELTA between successive counts, so dropped lines self-heal and the exact
 * PULSE cadence does not matter — we throttle PULSE frames and lean on the counter.
 *
 * Comms-loss heartbeat watchdog: the relay is a fail-closed output and this adapter — not the
 * app — is its safety authority. While dispensing, the adapter must keep hearing the app's PING;
 * if none arrives within HEARTBEAT_TIMEOUT_MS the comms are presumed dead (USB data drop, frozen
 * or crashed controller) and the adapter closes the relay on its own GPIO. It does NOT need to
 * know litres_authorised to do this, and it never re-energises on its own: once tripped, only an
 * explicit RLY:1 resumes fuel. In production the adapter is powered from the UPS, not the
 * tablet's USB, so it stays alive to enforce this even when the data link drops.
 *
 * EEPROM totaliser (7g): a single LIFETIME pulse count, wear-levelled over MAX_SLOTS slots. Per
 * the agreed constraint it is written only at end-of-dispense (on RLY:0) and on power failure —
 * never per pulse, which at 50 pps would exhaust the ~100k cycle endurance within the hour. It
 * is a REPORTING figure: the Android app remains system of record for litres sold.
 *
 * KNOWN GAP — do not mistake this for complete. Prototype Specification v1.0 (Software ->
 * power-cut transaction recovery) calls for resuming max(adapter_eeprom, android_persisted).
 * That comparison is not yet implementable: this totaliser is lifetime-scoped while the app's
 * persisted count is per-transaction, so a literal max() always returns the lifetime value. It
 * needs a session-mark command (app signals session-zero at relay-open; adapter records the
 * totaliser at that mark; recovery reads lifetime_now - lifetime_at_mark). That is a protocol
 * change and is deliberately NOT invented here — see OPEN_QUESTIONS #24, pending Olonade.
 * The same applies to the sealed pulses-per-litre constant and its proposed CAL frame (OQ #23).
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
// DEFAULT false: no power-sense circuit exists on any rig yet, and arming this interrupt without
// one is actively harmful. D3 was the button pin before this merge, so on existing bench wiring a
// button press would fire onPowerFail() on release — the board commits to EEPROM and HALTS, which
// looks exactly like a hang mid-demo. Turn this on only once the sense circuit is actually built.
const bool ENABLE_POWER_FAIL_SAVE = false;
const int  POWER_FAIL_EDGE        = RISING;

// Human-readable banners for a bare Serial Monitor. MUST be false whenever the app is attached:
// unframed lines are parsed as SerialFrame.Invalid.
const bool DEBUG_BANNERS = false;

// ---- EEPROM wear-levelled totaliser -----------------------------------------------------
// Field order is load-bearing. EEPROM.put() writes ascending, so "crc" lands LAST and acts as
// the commit marker: a write torn by a power cut leaves a stale/garbage crc, recovery rejects
// that slot, and the previous slot's older-but-valid data wins.
//
// (The bench sketch ordered "sequence" first with no crc. A cut mid-save could therefore commit
// a new highest sequence against a stale pulseCount left from a full lap of the ring — and
// recovery, picking purely on sequence, would elect exactly that corrupt slot. The failure case
// was the one the mechanism exists to survive.)
// A format marker leads the record. Without one, "is this slot ours?" rests entirely on a CRC-16,
// which foreign bytes pass about 1 time in 65,536 — and with 64 slots that is a ~0.1% chance per
// boot of adopting someone else's data as a pulse count. Not hypothetical: this firmware shares a
// board with an earlier sketch that used a different 8-byte layout, so the EEPROM genuinely does
// contain records in another format. The marker makes rejecting them a certainty instead of a bet.
//
// Bump SLOT_MAGIC whenever the struct changes, so an older layout is rejected rather than
// misread.
const uint16_t SLOT_MAGIC = 0x5350;   // "SP" — erased cells read 0xFFFF, so they never collide

struct PumpData {
  uint16_t      magic;        // bytes 0-1   — written first; identifies the record as ours
  unsigned long pulseCount;   // bytes 2-5
  unsigned long sequence;     // bytes 6-9
  uint16_t      crc;          // bytes 10-11 — written last: commit marker
};

const int MAX_SLOTS         = 64;               // 64 * 12 B = 768 B; fits Uno (1 KB) and Mega (4 KB)
const int SLOT_SIZE         = sizeof(PumpData);
const int EEPROM_START_ADDR = 0;

static_assert(MAX_SLOTS * SLOT_SIZE <= E2END + 1, "EEPROM ring does not fit this MCU");

// ---- State ------------------------------------------------------------------------------
volatile unsigned long pulseCount = 0;   // free-running lifetime count, shared with the ISR
volatile bool powerFailLatched = false;
bool dispensing = false;

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

// ---- Pulse counter ----------------------------------------------------------------------
void onPulseEdge() {
  if (PULSE_DEBOUNCE_US > 0) {
    static unsigned long lastEdgeUs = 0;
    // micros() is ISR-safe on AVR (it reads TCNT0 and folds in a pending overflow), unlike
    // millis(), which cannot advance while we are in here.
    unsigned long nowUs = micros();
    if (nowUs - lastEdgeUs < PULSE_DEBOUNCE_US) return;
    lastEdgeUs = nowUs;
  }
  pulseCount++;
}

unsigned long readCount() {
  noInterrupts();
  unsigned long c = pulseCount;                 // 4-byte read is non-atomic on AVR
  interrupts();
  return c;
}

void addPulses(unsigned long n) {
  noInterrupts();
  pulseCount += n;
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

void sendError(const char* code) {
  char body[24];
  snprintf(body, sizeof(body), "ERR:%s", code);
  sendRaw(body);
}

// ---- EEPROM totaliser -------------------------------------------------------------------
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

// Commit [count] to the next slot in the ring. Safe to be interrupted by the power-fail ISR:
// activeSlotIndex advances only AFTER the put returns, so the ISR targets the same slot and
// simply completes a consistent record over the top of the partial one.
void saveTotaliser(unsigned long count) {
  int nextSlot = (activeSlotIndex + 1) % MAX_SLOTS;
  PumpData d;
  d.magic      = SLOT_MAGIC;
  d.pulseCount = count;
  d.sequence   = currentSequence + 1;
  d.crc        = slotCrc(d);
  EEPROM.put(EEPROM_START_ADDR + nextSlot * SLOT_SIZE, d);
  activeSlotIndex = nextSlot;
  currentSequence = d.sequence;
  lastSavedCount  = count;
}

// Scan every slot for the highest sequence that still passes its CRC.
void recoverLatestState() {
  unsigned long bestSeq = 0;
  int bestSlot = -1;
  PumpData t;

  for (int i = 0; i < MAX_SLOTS; i++) {
    EEPROM.get(EEPROM_START_ADDR + i * SLOT_SIZE, t);
    if (t.magic != SLOT_MAGIC) continue;          // erased, or written by other firmware
    if (t.crc != slotCrc(t)) continue;            // torn write or corruption — ignore
    if (bestSlot < 0 || t.sequence > bestSeq) {
      bestSeq  = t.sequence;
      bestSlot = i;
    }
  }

  if (bestSlot < 0) {                             // first boot, or every slot unusable
    currentSequence = 0;
    pulseCount      = 0;
    activeSlotIndex = MAX_SLOTS - 1;              // first save goes to slot 0
  } else {
    EEPROM.get(EEPROM_START_ADDR + bestSlot * SLOT_SIZE, t);
    currentSequence = t.sequence;
    pulseCount      = t.pulseCount;
    activeSlotIndex = bestSlot;
  }
  lastSavedCount = pulseCount;
}

// ---- Relay ------------------------------------------------------------------------------
void setRelay(bool on) {
  dispensing = on;
  bool level = RELAY_ACTIVE_LOW ? !on : on;
  digitalWrite(PIN_RELAY, level ? HIGH : LOW);
  digitalWrite(LED_BUILTIN, on ? HIGH : LOW);    // visual cue on a bare board
}

// ---- Power-fail ISR ---------------------------------------------------------------------
// Ordering is deliberate: fuel off first (a couple of register writes), then the EEPROM commit,
// then a best-effort notice, then halt. The reservoir capacitor must hold the rail up long
// enough for the commit — worst case ~10 x 3.3 ms, though EEPROM.put() skips bytes that already
// match, so a small delta is much cheaper than that bound suggests.
void onPowerFail() {
  if (powerFailLatched) return;
  powerFailLatched = true;

  setRelay(false);                                           // safety before bookkeeping

  detachInterrupt(digitalPinToInterrupt(PIN_PULSE_IN));      // protect the write
  detachInterrupt(digitalPinToInterrupt(PIN_POWER_SENSE));

  saveTotaliser(pulseCount);

  // Best effort — the link may already be gone. HardwareSerial drains its buffer by polling
  // when interrupts are disabled, so both of these work from inside an ISR; without the flush
  // the bytes would sit in the buffer forever once we spin below.
  sendError("PWR");
  Serial.flush();

  digitalWrite(LED_BUILTIN, HIGH);
  while (true) { }                                           // hold until the rail collapses
}

// ---- Inbound command parsing ------------------------------------------------------------
void processLine(char* line) {
  char* star = strrchr(line, '*');
  if (!star || star == line || *(star + 1) == '\0') { sendError("NOCS"); return; }
  *star = '\0';
  uint8_t want = (uint8_t) strtol(star + 1, NULL, 16);
  if (xor8(line) != want) { sendError("CSUM"); return; }

  if      (strcmp(line, "PING")  == 0) lastHeartbeatMs = millis();
  // RLY:1 also seeds the heartbeat clock so the watchdog can't trip in the instant between
  // energising the relay and the first PING of the dispense.
  else if (strcmp(line, "RLY:1") == 0) { lastHeartbeatMs = millis(); setRelay(true); }
  else if (strcmp(line, "RLY:0") == 0) {
    setRelay(false);
    // End of dispense — the agreed commit point for the totaliser.
    unsigned long c = readCount();
    if (c != lastSavedCount) saveTotaliser(c);
  }
  else                                 sendError("CMD");
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
    addPulses(1);
  }
}

// ---- Comms-loss heartbeat watchdog ------------------------------------------------------
// Close the relay if the app's PING heartbeat has gone silent while fuel is flowing. The app
// sends PING ~every 1s; missing ~3 in a row trips this. Best-effort ERR:WDOG notice for the
// frozen-but-attached case — on a USB data drop the link is already down so nothing reads it,
// but the relay still closes locally on the adapter's own GPIO, which is the whole point.
void serviceRelayWatchdog() {
  if (dispensing && (millis() - lastHeartbeatMs) >= HEARTBEAT_TIMEOUT_MS) {
    setRelay(false);
    sendError("WDOG");
    unsigned long c = readCount();
    if (c != lastSavedCount) saveTotaliser(c);   // the dispense ended here, however abruptly
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
  setRelay(false);                               // relay OFF on boot — relay-open-on-boot invariant
  if (ENABLE_BUTTON) pinMode(PIN_BUTTON, INPUT_PULLUP);
  pinMode(PIN_PULSE_IN, INPUT_PULLUP);

  recoverLatestState();                          // restores pulseCount before any frame is sent

  attachInterrupt(digitalPinToInterrupt(PIN_PULSE_IN), onPulseEdge, FALLING);
  if (ENABLE_POWER_FAIL_SAVE) {
    pinMode(PIN_POWER_SENSE, INPUT_PULLUP);
    attachInterrupt(digitalPinToInterrupt(PIN_POWER_SENSE), onPowerFail, POWER_FAIL_EDGE);
  }

  Serial.begin(BAUD);
  delay(50);

  if (DEBUG_BANNERS) {                           // unframed — never with the app attached
    Serial.println(F("# SmartPump adapter (7a+7g)"));
    Serial.print(F("# slot="));     Serial.print(activeSlotIndex);
    Serial.print(F(" seq="));       Serial.print(currentSequence);
    Serial.print(F(" pulses="));    Serial.println(pulseCount);
  }

  // BOOT carries the restored totaliser, which is 0 only on a virgin board. PulseAccumulator
  // adopts it as its baseline and contributes 0 fuel, so a non-zero value is safe.
  sendFrame("BOOT", readCount());

  unsigned long t = millis();
  lastFrameMs = t;
  lastPulseTxMs = t;
  lastAutoMs = t;
  lastHeartbeatMs = t;  // dispensing == false at boot, so the watchdog stays idle until a dispense
  lastSentCount = readCount();
}

void loop() {
  handleSerial();
  serviceRelayWatchdog();
  generatePulses();
  emitFrames();
}
