# The gate sitting — nine authenticated responses from production

**2026-09-16, 22:36–23:13 UTC**, `https://api.balancee.app`, pump `SN-TEST-001`. Captured by the
in-app probe panel (stage 9d-2), driven through `PumpApiClient`, pulled with `adb`. The file beside
this one is verbatim; everything below cites it.

Six questions that have been open since August are answered here, and one assumption is wrong.

---

## 1. The freshness window exists, and our guessed string was right · **#15**

```
GET /api/pump/config  (signed 10 minutes in the past)  → 401
{"status":false,"message":"Request timestamp is not fresh"}
```

`API_CONFORMANCE_AUDIT.md` predicted exactly this string from the Reference's prose, and
`ERROR_COPY_DRAFT.md` already maps it to *"This tablet's clock is wrong. Turn on automatic date and
time."* Both stand. The window is real and a ten-minute-old signature does not pass it.

**Note what is missing: no `code`.** See §3.

## 2. Stable error codes DO exist on business failures · **#18f**

Two of them, both new:

```
GET /api/pump/transactions/probe-not-a-real-id → 404
{"status":false,"message":"No transaction was found for this id.","code":"TRANSACTION_NOT_FOUND"}

POST /api/pump/authorise  (amount one naira out) → 400
{"status":false,"message":"The sale amount does not match the current station price for this fuel
 type. Refresh the pump price and try again.","code":"AMOUNT_MISMATCH"}
```

This is the ask in `BOSS_CONFIRMATIONS_DRAFT.md` — "request stable error codes alongside `message`" —
already built. `AMOUNT_MISMATCH` is exactly the case the ask cited, and the message is interpolated
prose that would break any matcher the day someone rewords it. **Match on `code`.**

## 3. The pattern behind which responses carry a `code`

Everything captured to date, across all three probe sessions:

| response | `code` |
|---|---|
| 400 `/activate` empty body | `INVALID_REQUEST` |
| 400 `/authorise` amount mismatch | `AMOUNT_MISMATCH` |
| 404 `/transactions/{id}` not found | `TRANSACTION_NOT_FOUND` |
| 401 missing auth headers | **absent** |
| 401 invalid API key | **absent** |
| 401 stale timestamp | **absent** |

So: **business failures carry a code; authentication failures do not.** That is a coherent rule
rather than an inconsistency, and it is enough to build on — `ApiError.Business.code` stays nullable,
and the auth family is identified by `httpCode == 401` plus the message. Worth confirming with the
backend as a rule rather than an observation, but it no longer blocks anything.

## 4. `/authorise` works, and `PENDING_PAYMENT` is real

```
POST /api/pump/authorise → 200
{"status":true,"message":"Transaction authorised","data":{"status":"PENDING_PAYMENT",
 "transactionId":"probe-17df2303-…","paymentReference":"BPM-0006009bfa1f4981ac3a3b52d36443e1",
 "authorizationUrl":"https://checkout.paystack.com/0lugu1pv5t8et80",
 "expiresAt":"2026-09-16T23:03:32.245Z"}}
```

`AuthoriseResponse` parsed unchanged — the first DTO to survive contact. `paymentReference` is
`BPM-` + 32 hex, which nothing had specified. Two real Paystack checkout links were created and left
unpaid.

## 5. ⚠️ The QR expiry is **20 minutes**, not 5

Measured four times, from the captures' own timestamps:

| authorised at | expiresAt | window |
|---|---|---|
| 22:42:14.615 | 23:02:15.616 | 20 min 1 s |
| 22:43:31.143 | 23:03:32.245 | 20 min 1 s |
| 22:51:47.546 | 23:11:48.682 | 20 min 1 s |
| 23:13:06.232 | 23:33:07.279 | 20 min 1 s |

Three places in the app say five minutes:

- `PumpApiDtos.kt:75` — *"drives the 5-min QR-expiry / poll window"*
- `TransactionState.kt:50` — *"QR / NFC / digital wait. 5-min expiry, then auto-cancel back to Idle."*
- `PumpRequestSigner.kt:6` — *"within 5 min of server clock"* (a different 5 minutes — the signing
  window, still unmeasured; §1 only proves ten minutes is too old)

The customer-facing one matters: a screen that gives up after five minutes abandons a sale the server
would still have honoured for another fifteen. **Use the server's `expiresAt`, not a constant.**

## 6. `amount` accepts decimals · **#18c — ANSWERED**

Re-run on 2026-09-16 at 23:13 with the capture format that records the request too, so this rests on
bytes rather than on anyone's memory of a text box:

```
sent:     {"pumpId":"3727aebf-…","transactionId":"probe-1d6dc982-…","amount":3501.5,
           "expectedLitres":2.35,"fuelType":"PETROL"}
received: 200 {"status":true,"message":"Transaction authorised","data":{"status":"PENDING_PAYMENT",
           "transactionId":"probe-1d6dc982-…","paymentReference":"BPM-791e5766f1aa419695506e9949dc3a8d",
           "authorizationUrl":"https://checkout.paystack.com/r15ru028lccwuts",
           "expiresAt":"2026-09-16T23:33:07.279Z"}}
```

2.35 L × ₦1490 = ₦3,501.50, sent as `3501.5` and **accepted**. So the server takes a decimal `amount`,
and its exact `amount == expectedLitres × pricePerUnit` check passes on a fractional product.

**Consequence: `AuthoriseRequest.amount` must stop being a `Long`** before the payment flows (#8) are
built — see TODO **#44**. Station pricing does **not** have to be constrained to whole naira, which was
the alternative and the worse one.

**The first attempt proved less than it looked.** With litres at 2.0 the correct amount is a whole
2980, so the probe sent 2980.5 — decimal *and* wrong at once — and got `AMOUNT_MISMATCH`. That showed
only that a decimal parses. It is recorded here because the trap is easy to fall into twice: a probe
whose input is wrong in two ways at once cannot tell you which one the server objected to.

**Still unmeasured: how many decimal places.** 3501.5 is one. The app carries money as kobo, so it
cannot express more than two — yet `price × litres` can exceed two whenever the price is not a
multiple of ten (₦1491 × 2.357 L = ₦3,514.287). With today's ₦1490 that cannot arise, so the question
is dormant rather than answered. The probe for it is litres **2.3571** → 3512.079, three places.

## 7. Not run

`POST /transactions/upload` (step 7). It needs the ids from a happy authorise, which now exist.
