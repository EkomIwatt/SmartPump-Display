#pragma once
#include <cstring>
#include <cstdint>
extern uint8_t g_eeprom[1024];
extern int g_eeprom_writes;
struct EEPROMClass {
  template<typename T> T& get(int a, T& t) { memcpy(&t, g_eeprom + a, sizeof(T)); return t; }
  template<typename T> const T& put(int a, const T& t) {
    const uint8_t* p = (const uint8_t*)&t;
    for (size_t i = 0; i < sizeof(T); i++) if (g_eeprom[a+i] != p[i]) { g_eeprom[a+i] = p[i]; g_eeprom_writes++; }
    return t;
  }
};
static EEPROMClass EEPROM;
