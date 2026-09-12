#!/usr/bin/env bash
# Re-run the 2026-09-12 dev API probe. Sends NO credentials — every value is filler — and cannot
# redeem an activation code (empty body). Safe to run at any time.
set -u
B="${1:-https://api.dev.balancee.app}"
cd "$(dirname "$0")"

curl -s -m 20 -D headers-config-no-auth.txt -o body-config-no-auth.json \
  "$B/api/pump/config"
curl -s -m 20 -D headers-transactions-no-auth.txt -o body-transactions-no-auth.json \
  "$B/api/pump/transactions/probe-not-a-real-id"
curl -s -m 20 -X POST -H 'Content-Type: application/json' -d '{}' \
  -D headers-activate-empty-body.txt -o body-activate-empty-body.json \
  "$B/api/pump/activate"
curl -s -m 20 \
  -H 'X-Api-Key: probe_not_a_real_key' \
  -H 'X-Device-Id: 00000000-0000-0000-0000-000000000000' \
  -H "X-Timestamp: $(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  -H 'X-Signature: deadbeef' \
  -D headers-config-fake-creds.txt -o body-config-fake-creds.json \
  "$B/api/pump/config"
# Control: a route that does not exist, to prove a JSON envelope means a real handler answered.
curl -s -m 20 -o /dev/null -D headers-unknown-route-control.txt \
  "$B/api/pump/definitely-not-an-endpoint"

for f in body-*.json; do printf '%s\n  ' "$f"; cat "$f"; echo; done
grep -h -i '^HTTP/\|^X-Matched-Path' headers-*.txt
