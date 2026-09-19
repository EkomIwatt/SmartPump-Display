# 2026-09-19 — the 10g gate, first sitting

Production (`https://api.balancee.app/`), pump `SN-TEST-001`
(`3727aebf-3c77-4180-a818-4254cbeeae72`), device `ae2b7a83-3e63-4a6c-b3b1-ce0ba38b52ce`,
`debugProd` installed over the #32 install. **No money changed hands.** The sitting was stopped
before payment because the sale in flight was mispriced.

Bytes: [`api-capture-20260919-184249.txt`](api-capture-20260919-184249.txt) — 20 responses, newest
first. Build fixtures from the file, never from this page.

---

## The headline: the server does not expire a transaction

The pre-pay QR was left deliberately unpaid so the expiry could be observed. It was not observable
from the app, because the app stops watching first (finding 4 below) — so the **probe panel** was
pointed at the same transaction afterwards.

| Time (UTC) | Source | `status` |
|---|---|---|
| 18:35:53 → 18:39:02 | app poll, 18 times | `PENDING_PAYMENT` |
| **18:39:12.046** | — | **the transaction's own `expiresAt`** |
| **18:42:28** | probe panel, **3m16s after expiry** | **`PENDING_PAYMENT`** |

```json
{"status":true,"message":"Pump transaction","data":{
  "status":"PENDING_PAYMENT",
  "transactionId":"64bada69-93d7-461e-aaee-f2e517206d3d",
  "paymentReference":"BPM-ae742d8e63b644e6bb53e9518e9d0152",
  "authorizationUrl":"https://checkout.paystack.com/2z1b3tfguhs9h0q",
  "expiresAt":"2026-09-19T18:39:12.046Z"}}
```

**HTTP 200, `status: true`, same live `authorizationUrl`, and an `expiresAt` in the past.** The
server does not transition the record on its own clock; `expiresAt` is a value it reports, not a
deadline it enforces — at least not within three minutes of it passing.

### What that costs

The app ends a pre-pay sale on **its own** countdown (`CustomerViewModel.kt:1363`:
`cancelInFlightJobs(); setState(Idle)`). Put the two together:

1. Customer scans the QR and walks off without paying.
2. At `expiresAt` the tablet goes Idle and **stops polling**. It has forgotten the sale.
3. The Paystack checkout page is still live, and the backend still says `PENDING_PAYMENT`.
4. Customer pays five minutes later. Money leaves their account.
5. Nothing is watching. No dispense, no local record, nothing to upload, nothing to reconcile.

This was originally written up as a few seconds of clock skew. It is not bounded by skew — it is
bounded by whatever the server eventually does with the record, which **is not known** and is not
three minutes.

### It also means 10e has no expiry row to un-park

The sitting intended to catch one real failure to fill a parked Catalogue A row. There was no
failure to catch: an expired-unpaid transaction is a **200 success** carrying `PENDING_PAYMENT`.
There is no expiry code, no error envelope, nothing to key copy on. That is the finding.

**Backend ask (new):** does a transaction ever leave `PENDING_PAYMENT` on expiry, and is the
`authorizationUrl` still payable after `expiresAt`? If it stays payable indefinitely, the app
cannot safely stop polling at all, and the client-side countdown is the wrong mechanism.

---

## `GET /config` — correct, and a third station name

```json
{"pumpId":"3727aebf-3c77-4180-a818-4254cbeeae72","stationName":"Kachi",
 "fuelType":"PETROL","pricePerUnit":1490,"updatedAt":"2026-09-15T09:44:39.187Z"}
```

`pricePerUnit: 1490` is naira per litre, stored correctly as `koboPerLitre = 149000`. The sync
worked: `device_config` moved ₦870 → ₦1,490 and a `PRICE_SYNCED` event was written.

`stationName: "Kachi"` is a **third** name, alongside `DeviceConfig.stationName`
("Total Lekki Ph2", the debug seed) and `StationIdentity.displayName` ("Demo Station", what the
customer screen shows). The two-names item on the board is a three-names item.

## `POST /authorise` — what was sent

```json
{"pumpId":"3727aebf-3c77-4180-a818-4254cbeeae72",
 "transactionId":"64bada69-93d7-461e-aaee-f2e517206d3d",
 "amount":2007.03,"expectedLitres":1.347,"fuelType":"PETROL"}
```

**₦2,007.03 for a customer who typed ₦200.** Not a server defect — ours, three times over. See the
findings below.

---

## Findings, in severity order

1. **`ModeSelectScreen.kt:257` — charged more than displayed.** Every keypad digit commits live;
   `onCustomBackspace` deliberately does not re-commit. Typing `2008`, backspacing to `200` and
   confirming leaves ₦2,008 committed while the screen reads ₦200, and `CUSTOM_MIN_NAIRA = 200`
   means the value is *valid*, so the `customEntryOk` gate the comment relies on never fires.
   ₦2,008 tendered → quote 1.347 L / ₦2,007.03, which is exactly what went on the wire. Dates to
   `33e9564` (2026-05-26).
2. **`CustomerViewModel.kt:236` — stale price in memory.** `syncPriceOnBoot` writes the server's
   price to the database, then returns early unless the state is `Idle`. The tablet restored to
   `ModeSelect`, so the in-memory figure stayed on the ₦870 seed while the database held ₦1,490.
   It reached `PrepayAwaitingPayment.priceKoboPerLitre` and would have reached the receipt and the
   completion screen — #37's failure through a different door. It also drives the amount screen's
   litre previews.
3. **`CustomerViewModel.kt:1363` — silent expiry.** See the headline above.
4. **`PumpConfigSync.kt:107` — wrong attribution.** `PRICE_SYNCED` reads "Price updated from the
   operator", on the one path that can only mean the server. The local operator edit records no
   event at all, so this is the only price event there is.

## What passed

- **`MIGRATION_4_5` on a real device**, first time outside a test harness. v4 → v5, three columns
  added, pre-existing rows intact.
- **Activation survived the reinstall.** `/config` is authenticated and returned 200, so the #32
  KeyStore credentials are alive.
- **10d's poll loop.** 114 polls, clean ~10.5 s cadence, no drift, no leak.
- **Price sync end to end**, database and event both.
