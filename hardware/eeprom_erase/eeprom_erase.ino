/*
 * SmartPump — EEPROM erase utility
 *
 * One-off tool. Flash it, wait for the built-in LED to come on, then flash the real sketch
 * (smartpump_pulse_adapter) back. It writes 0xFF to every EEPROM cell, which is the erased state
 * the adapter treats as "no records here", so the totaliser restarts from zero.
 *
 * WHEN TO USE IT
 *   - A board that previously ran different firmware and still holds records in another layout.
 *   - Before a calibration run, when a clean BOOT:0 is wanted so the numbers in the run sheet are
 *     unambiguous.
 *   - Commissioning a board for a specific pump, so its lifetime total starts at that pump.
 *
 * WHEN NOT TO USE IT
 *   - On a deployed adapter. The totaliser is the pump's lifetime count and is meant to be
 *     reconciled against station stock records. Erasing it destroys that history, and the app's
 *     own transaction log is a separate record that will NOT match afterwards.
 *
 * The adapter tolerates foreign records anyway (it checks a format marker, then a CRC), so this
 * is a convenience, not a repair. It uses EEPROM.update(), which skips cells already at 0xFF, so
 * a second run costs almost nothing and does not burn write cycles needlessly.
 */

#include <EEPROM.h>

static bool eraseOk = false;

void setup() {
  pinMode(LED_BUILTIN, OUTPUT);
  digitalWrite(LED_BUILTIN, LOW);

  Serial.begin(115200);
  delay(50);

  const uint16_t len = EEPROM.length();         // uint16_t, so the loop counters match it
  Serial.print(F("Erasing "));
  Serial.print(len);
  Serial.println(F(" bytes..."));

  for (uint16_t i = 0; i < len; i++) {
    EEPROM.update(i, 0xFF);                     // update() skips cells already erased
  }

  // Read back, so "done" means verified rather than merely attempted.
  uint16_t bad = 0;
  for (uint16_t i = 0; i < len; i++) {
    if (EEPROM.read(i) != 0xFF) bad++;
  }

  eraseOk = (bad == 0);
  if (eraseOk) {
    Serial.println(F("OK - erased and verified. Reflash smartpump_pulse_adapter now."));
    digitalWrite(LED_BUILTIN, HIGH);            // solid = success
  } else {
    Serial.print(F("FAILED - "));
    Serial.print(bad);
    Serial.println(F(" cells did not erase."));
  }
}

void loop() {
  if (eraseOk) return;                          // solid LED, nothing more to do
  digitalWrite(LED_BUILTIN, HIGH); delay(150);  // blink = failure, never mistakable for success
  digitalWrite(LED_BUILTIN, LOW);  delay(150);
}
