/*
 * SmartPump Display — pulse-adapter firmware (Phase 7a)
 * Target: Arduino Uno R3 (or any AVR/clone with a hardware-interrupt pin)
 *
 * Speaks the SmartPump serial protocol over USB @ 115200 8N1. This is the other end of the
 * Android UsbSerialConnection / SerialFrameParser, so the framing + checksum here MUST stay
 * byte-for-byte identical to the Kotlin side.
 *
 * Framing (line-delimited, '\n'):
 *   device -> app :  PULSE:<cum>*<cs>     a fuel pulse; <cum> is this adapter's free-running count
 *                    HB:<cum>*<cs>        keep-alive, ~2s when idle
 *                    BOOT:<cum>*<cs>      sent once at power-up (count starts at 0)
 *                    ERR:<code>*<cs>      rejected/garbled inbound command, or ERR:WDOG when the
 *                                         relay dead-man watchdog auto-closes the relay (see below)
 *   app -> device :  RLY:1*<cs>           energise relay (fuel on); MUST be re-asserted ~every 700ms
 *                                         while dispensing or the watchdog closes the relay
 *                    RLY:0*<cs>           de-energise relay (fuel off)
 *
 * Relay dead-man watchdog: the relay is a fail-closed output. Once energised it stays on only while
 * the app keeps re-asserting RLY:1; if no valid RLY command arrives within RELAY_DEADMAN_MS the
 * adapter closes the relay itself. This is the safety backstop for a mid-dispense cable yank or a
 * frozen controller — fuel can never keep flowing once the app goes silent.
 *   <cs> = XOR-8 of every ASCII byte BEFORE the '*', printed as two UPPERCASE hex digits.
 *          e.g. PULSE:1 -> 54, HB:0 -> 00, BOOT:0 -> 1C, RLY:1 -> 4C, RLY:0 -> 4D
 *          (the boss's illustrative "PULSE:0042817*7C" is wrong; the real XOR is 5D.)
 *
 * The app takes the DELTA between successive cumulative counts, so dropped lines self-heal and
 * the exact PULSE cadence does not matter — we throttle PULSE frames and lean on the counter.
 *
 * Demo without a real flow meter: with ENABLE_AUTO_PULSE, the adapter synthesises pulses while
 * the relay is energised (~30 L/min at 100 pulses/L). A button can also inject pulses by hand.
 * A real meter wired to PIN_PULSE_IN works too (counted on the interrupt) — set ENABLE_AUTO_PULSE
 * to false if you don't want the synthetic pulses on top.
 */

// ---- Configuration ----------------------------------------------------------------------
#define BAUD 115200

const uint8_t  PIN_RELAY    = 7;   // relay module / LED driving the pump solenoid
const uint8_t  PIN_PULSE_IN = 2;   // INT0 — real flow-meter signal (optional)
const uint8_t  PIN_BUTTON   = 3;   // manual pulse inject, to GND (optional)

const bool     RELAY_ACTIVE_LOW   = false; // set true for active-LOW relay boards (LOW = energised)
const bool     ENABLE_AUTO_PULSE  = true;  // synthesise pulses while dispensing (meter-free demo)
const bool     ENABLE_BUTTON      = true;  // inject pulses while the button is held
const unsigned int  AUTO_PPS      = 50;    // synthetic pulse rate (~30 L/min @ 100 pulses/L)

const unsigned long HB_INTERVAL_MS   = 2000; // keep-alive cadence when idle
const unsigned long PULSE_TX_MIN_MS  = 30;   // min gap between PULSE frames (throttle the stream)

// Dead-man watchdog: while the relay is energised the app must keep re-asserting RLY:1 (it sends
// one ~every 700ms). If no valid RLY command arrives for this long, the link is presumed dead (cable
// yanked / app frozen) and we close the relay ourselves — fuel must never keep flowing once the
// controller has gone silent. Generous enough not to false-trip on USB latency, tight enough to bound
// uncontrolled flow well under the 3s fill-up shutoff window.
const unsigned long RELAY_DEADMAN_MS = 2000;

// ---- State ------------------------------------------------------------------------------
volatile unsigned long pulseCount = 0;   // free-running, shared with the ISR
bool dispensing = false;

unsigned long lastFrameMs   = 0;  // last frame of ANY type sent (gates HB)
unsigned long lastPulseTxMs = 0;  // last PULSE frame sent (throttle)
unsigned long lastAutoMs    = 0;  // last synthetic-pulse tick
unsigned long lastSentCount = 0;  // cumulative at the last PULSE frame
unsigned long lastRelayCmdMs = 0; // last valid RLY command received (feeds the dead-man watchdog)

char rxBuf[40];
uint8_t rxLen = 0;

// ---- Pulse counter ----------------------------------------------------------------------
void onPulseEdge() { pulseCount++; }            // ISR — keep it tiny

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

// ---- Relay ------------------------------------------------------------------------------
void setRelay(bool on) {
  dispensing = on;
  bool level = RELAY_ACTIVE_LOW ? !on : on;
  digitalWrite(PIN_RELAY, level ? HIGH : LOW);
  digitalWrite(LED_BUILTIN, on ? HIGH : LOW);    // visual cue on a bare Uno
}

// ---- Inbound command parsing ------------------------------------------------------------
void processLine(char* line) {
  char* star = strrchr(line, '*');
  if (!star || star == line || *(star + 1) == '\0') { sendError("NOCS"); return; }
  *star = '\0';
  uint8_t want = (uint8_t) strtol(star + 1, NULL, 16);
  if (xor8(line) != want) { sendError("CSUM"); return; }

  if      (strcmp(line, "RLY:1") == 0) { lastRelayCmdMs = millis(); setRelay(true); }
  else if (strcmp(line, "RLY:0") == 0) { lastRelayCmdMs = millis(); setRelay(false); }
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

// ---- Relay dead-man watchdog ------------------------------------------------------------
// Close the relay if the app has gone silent while fuel is flowing. The app re-asserts RLY:1
// roughly every 700ms during a dispense; missing ~3 in a row trips this. Best-effort ERR:WDOG
// notice for the app-frozen-but-cable-attached case — on a real cable yank the link is already
// down so nothing reads it, but the relay still closes locally, which is the point.
void serviceRelayWatchdog() {
  if (dispensing && (millis() - lastRelayCmdMs) >= RELAY_DEADMAN_MS) {
    setRelay(false);
    sendError("WDOG");
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
  attachInterrupt(digitalPinToInterrupt(PIN_PULSE_IN), onPulseEdge, FALLING);

  Serial.begin(BAUD);
  delay(50);
  sendFrame("BOOT", readCount());                // count == 0

  unsigned long t = millis();
  lastFrameMs = t;
  lastPulseTxMs = t;
  lastAutoMs = t;
}

void loop() {
  handleSerial();
  serviceRelayWatchdog();
  generatePulses();
  emitFrames();
}
