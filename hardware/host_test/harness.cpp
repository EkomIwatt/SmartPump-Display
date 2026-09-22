// Host-side harness for the pulse-adapter sketch (Phase 11b). Compiles the REAL .ino against
// fake Arduino / Serial / EEPROM stubs, so the protocol can be driven and checked without a board.
// It proves the logic, not the hardware: timing, the relay, the meter and the power-fail circuit
// still need the bench (PHASE_11_PLAN.md, 11f).
//
// Usage: harness.exe <eeprom-file> <step>...   (the EEPROM file persists across runs = reboots)
//   tx:<body>  send <body>*<checksum>      raw:<line>  send a line as-is
//   p:<n>      n meter pulses via the ISR  ms:<n>      advance n ms, running loop() every ms
//   ping       send PING                   pf          power fail (runs onPowerFail, then halts)
//   show       print session + relay state
// PULSE/HB frames are hidden to keep the output readable.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <string>
#include <deque>
#define E2END 1023
#define LED_BUILTIN 13
#define HIGH 1
#define LOW 0
#define OUTPUT 1
#define INPUT_PULLUP 2
#define RISING 3
#define FALLING 2
#define F(x) x
uint8_t g_eeprom[1024]; int g_eeprom_writes = 0;
unsigned long g_ms = 0, g_us = 0;
int g_pins[64];
bool g_pf_halt = false;
struct Halt {};
unsigned long millis() { return g_ms; }
unsigned long micros() { return g_us; }
void delay(unsigned long) {}
void noInterrupts() {} void interrupts() {}
void pinMode(int, int) {}
int digitalRead(int p) { return g_pins[p]; }
void digitalWrite(int p, int v) { g_pins[p] = v; }
int digitalPinToInterrupt(int p) { return p; }
void attachInterrupt(int, void(*)(), int) {}
void detachInterrupt(int) {}
std::deque<char> g_rx; bool g_show_pulse = false;
struct SerialC {
  void begin(long) {}
  int available() { return (int)g_rx.size(); }
  int read() { char c = g_rx.front(); g_rx.pop_front(); return c; }
  void println(const char* s) {
    if (!g_show_pulse && (!strncmp(s,"PULSE:",6) || !strncmp(s,"HB:",3))) return;
    printf("  < %s\n", s);
  }
  template<typename T> void print(T) {}
  void println(char* s) { println((const char*)s); }
  template<typename T> void println(T) {}
  void flush() { if (g_pf_halt) throw Halt(); }
} Serial;
#pragma pack(push, 1)
#include SKETCH  // -DSKETCH=\"path/to/sketch.ino\" — see run.sh
#pragma pack(pop)

unsigned char x8(const std::string& b){ unsigned char c=0; for(char ch:b) c^=(unsigned char)ch; return c; }
void send(const std::string& line){ printf("> %s\n", line.c_str()); for(char c:line) g_rx.push_back(c); g_rx.push_back('\n'); loop(); }
int relay(){ return g_pins[7]; }
int main(int argc, char** argv) {
  const char* ef = argv[1];
  FILE* f = fopen(ef, "rb");
  if (f) { fread(g_eeprom, 1, 1024, f); fclose(f); } else memset(g_eeprom, 0xFF, 1024);
  printf("-- boot\n");
  setup();
  int lastRelay = relay();
  auto relayNote = [&]{ if (relay()!=lastRelay){ printf("  [relay %s at count %lu]\n", relay()?"ON":"OFF", (unsigned long)pulseCount); lastRelay=relay(); } };
  try {
  for (int i = 2; i < argc; i++) {
    std::string a = argv[i];
    if (!a.compare(0,3,"tx:")) { std::string b=a.substr(3); char cs[4]; snprintf(cs,4,"%02X",x8(b)); send(b+"*"+cs); }
    else if (!a.compare(0,4,"raw:")) send(a.substr(4));
    else if (!a.compare(0,2,"p:")) { int n=atoi(a.c_str()+2); for(int k=0;k<n;k++){ g_us+=1000; onPulseEdge(); relayNote(); loop(); } printf("  (+%d pulses, count %lu)\n", n, (unsigned long)pulseCount); }
    else if (!a.compare(0,3,"ms:")) { int n=atoi(a.c_str()+3); for(int k=0;k<n;k++){ g_ms++; g_us+=1000; loop(); relayNote(); } printf("  (+%d ms)\n", n); }
    else if (a=="ping") send(std::string("PING*10"));
    else if (a=="pf") { printf("-- power fail\n"); g_pf_halt = true; onPowerFail(); }
    else if (a=="show") printf("  state=%d tag=%lu start=%lu limit=%lu count=%lu relay=%d\n", sesState, (unsigned long)sesTag, (unsigned long)sesStart, (unsigned long)sesLimit, (unsigned long)pulseCount, relay());
    relayNote();
  }
  } catch (Halt&) { printf("  [halted, relay %s]\n", relay()?"ON":"OFF"); }
  f = fopen(ef, "wb"); fwrite(g_eeprom, 1, 1024, f); fclose(f);
  printf("-- eeprom byte writes this boot: %d\n", g_eeprom_writes);
}
