#!/usr/bin/env bash
# Build the harness against the sketch (power-fail flag as shipped, and forced on) and run the
# scenarios, diffing against expected.txt. Needs g++, or zig: `pip install ziglang` and set
# CXX="python -m ziglang c++". Regenerate expected.txt with `./run.sh --update` only after
# checking the new output by hand — it is a regression guard, not an oracle.
set -euo pipefail
cd "$(dirname "$0")"
CXX=${CXX:-g++}
SK=../smartpump_pulse_adapter/smartpump_pulse_adapter.ino
W=$(mktemp -d)
cp "$SK" "$W/off.ino"
sed 's/^const bool ENABLE_POWER_FAIL_SAVE = false;/const bool ENABLE_POWER_FAIL_SAVE = true;/' "$SK" > "$W/on.ino"
grep -q 'ENABLE_POWER_FAIL_SAVE = true;' "$W/on.ino" || { echo "flag line not found in sketch"; exit 1; }
for v in off on; do
  $CXX -std=c++17 -w -Iinc -DSKETCH="\"$W/$v.ino\"" -x c++ harness.cpp -o "$W/$v.exe"
done
OFF="$W/off.exe"; ON="$W/on.exe"; E="$W/eeprom"

run() {
  echo "=== 1. frames, cutoff, retries (flag off)"
  rm -f "$E"; "$OFF" "$E" raw:RLY:1*4C tx:RLY:1:0:7 tx:RLY:1:5:0 tx:RLY:1:1000001:7 tx:RLY:1:5:7x \
    tx:RLY:1:-5:7 tx:RLY:1:99999999999:7 tx:RLY:1:5 tx:RES:0 raw:RLY:1:500:7*4F raw:SES? tx:SES? \
    tx:RES:7 raw:RLY:1:500:7*4E p:3 tx:RLY:1:9999:7 show p:497 p:5 show tx:RES:7 tx:SES? \
    tx:RLY:1:500:7 tx:RLY:1:10:8 p:3 tx:RLY:0 show p:2 tx:SES? tx:RES:8 p:5 show p:1 tx:RES:9
  echo "=== 2. watchdog, then reboot with the flag off"
  rm -f "$E"; "$OFF" "$E" tx:RLY:1:100:5 p:10 ms:2000 ping ms:2500 ms:600 show p:3 tx:RES:5 ping p:20 show tx:RLY:0
  "$OFF" "$E" show tx:SES? tx:RES:5
  echo "=== 3c. flag on: power fails mid-sale, save lands -> exact resume"
  rm -f "$E"; "$ON" "$E" tx:RLY:1:100:11 p:40 pf
  "$ON" "$E" show tx:SES? tx:RLY:0 tx:RES:11 p:60 show
  echo "=== 3b. flag on: power dies mid-sale with NO save -> session discarded"
  "$ON" "$E" tx:RLY:1:100:12 p:30
  "$ON" "$E" show tx:SES? tx:RES:12
  echo "=== 3a. flag on: RLY:0, then power cut -> session restored"
  "$ON" "$E" tx:RLY:1:50:13 p:20 tx:RLY:0
  "$ON" "$E" show tx:SES? tx:RES:13 p:30
  echo "=== 3d. flag on: a DONE session survives a reboot and is never re-opened"
  "$ON" "$E" show tx:SES? tx:RES:13
  echo "=== 4. a 7g-layout record (magic 0x5350) is rejected"
  python -c "
import struct,sys
def crc(b):
  c=0xFFFF
  for x in b:
    c^=x<<8
    for _ in range(8): c=((c<<1)^0x1021)&0xFFFF if c&0x8000 else (c<<1)&0xFFFF
  return c
r=struct.pack('<HII',0x5350,999,5); r+=struct.pack('<H',crc(r))
open(sys.argv[1],'wb').write(r+b'\xff'*(1024-len(r)))" "$E"
  "$OFF" "$E"
  echo "=== 5. flag on: 60 sales wrap the 40-slot ring; the newest record wins"
  rm -f "$E"; args=(); for i in $(seq 1 60); do args+=(tx:RLY:1:5:$i p:5); done
  "$ON" "$E" "${args[@]}" > /dev/null
  "$ON" "$E" show tx:SES?
}

if [[ "${1:-}" == "--update" ]]; then run > expected.txt; echo "expected.txt updated"; exit 0; fi
run > "$W/actual.txt"
if diff -u --strip-trailing-cr expected.txt "$W/actual.txt"; then echo "host_test: PASS"; else echo "host_test: FAIL"; exit 1; fi
