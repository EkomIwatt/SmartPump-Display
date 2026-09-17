# `GET /api/pump/config` — the first authenticated response this project has ever received

**2026-09-16**, production (`https://api.balancee.app`), pump `SN-TEST-001`, captured by the in-app
probe panel (phase 9d-1) and pulled off the tablet with `adb`. The file beside this one is the
verbatim capture; the body below is copied from it, not restated.

```json
{"status":true,"message":"Pump config","data":{"pumpId":"3727aebf-3c77-4180-a818-4254cbeeae72","stationName":"Kachi","fuelType":"PETROL","pricePerUnit":1490,"updatedAt":"2026-09-15T09:44:39.187Z"}}
```

**This is fixture material.** Anything built from it copies these bytes. Restating the shape in our
own words is how TODO #11 survived a green suite for two months, and #32 step 2 exists precisely to
prevent a second instance.

---

## 1. Our DTO was wrong about the whole shape, not one field name

```kotlin
// what we had, since July — invented from our summary of the Reference
data class PumpConfigResponse(
    @SerialName("prices") val prices: Map<FuelType, Long> = emptyMap(),
)
```

There is no `prices` map. `/config` describes **this pump**, not a price list: its `pumpId`, the
station it belongs to, the single `fuelType` it dispenses, one `pricePerUnit`, and when that was last
changed.

Because `prices` was **defaulted to `emptyMap()`**, the wrong shape did not fail. It parsed cleanly
into a config saying this pump sells nothing, and every layer above would have believed it. The probe
panel reported it as a caution — *"200 OK, but zero prices parsed"* — which is the one reason the
mistake was visible at all rather than being discovered weeks later against a real forecourt.

## 2. It answers the highest-priority backend ask — which was already built

`BOSS_CONFIRMATIONS_DRAFT.md` item 1, the one marked *"highest — this sets the date"*, says **nothing
in the API tells a pump what it sells or what to charge**, and asks for an endpoint that does. That
endpoint exists and is deployed. It returns `fuelType` **and** `pricePerUnit` **and** `stationName`.

The ask should be struck before the message is sent. It was true of the Reference PDF; it is not true
of the server.

## 3. `pricePerUnit: 1490` is naira, near-certainly

₦1,490/L is a plausible pump price in Nigeria in 2026. ₦14.90 is not. This is consistent with the
`amount = naira` decision taken on 2026-08-05 (TODO #17), which was our own call made without
confirmation. Still worth one line of confirmation — an integer field gives no unit — but the
observation is strong, and the app stores kobo internally, so the mapper owns a ×100.

## 4. GET signing is confirmed correct

A 200 means our signing of a body-less request — `timestamp + "." + ""` — is what the server
verifies. That was an open question in `BOSS_CONFIRMATIONS_DRAFT.md` item 3 and in **#32 step 6**,
and it is now answered by observation. Strike it from the ask too.

## 5. Still open after this capture

- **The unit on `pricePerUnit`** — inferred, not stated (see 3 above).
- **`updatedAt`** — present, format ISO-8601 with millis. Nothing consumes it yet; it is what a
  freshness check would use, which is the role the FCM push was reduced to.
- **What a pump with no price configured returns** — this one had a price. An absent or null
  `pricePerUnit` is the case the operator-config guard would have to handle.
- **Whether `stationName` is authoritative over the operator-typed one** (7b stores its own).
