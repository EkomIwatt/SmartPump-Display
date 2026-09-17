# Pre-pay precision — the server takes 4dp litres, and that is not the whole answer

**2026-09-17, 21:25–21:26 UTC**, `https://api.balancee.app`, pump `SN-TEST-001`. Step 8 of
`GATE_32_RUNBOOK.md`, driven through `PumpApiClient` by the probe panel's **…4dp litres** button.
The file beside this one is verbatim; everything below cites it.

The question came out of phase 10b: a pre-pay customer hands over a round sum, litres quoted at 2dp
cannot spend all of it, and the server's `amount == expectedLitres × pricePerUnit` check is exact —
so the shortfall is the customer's loss and its size depends on how finely litres can be quoted.

---

## 1. Answered: four decimal places are accepted

```
POST /api/pump/authorise → 200
{"pumpId":"3727aebf-…","transactionId":"probe-6b7ac0e8-…",
 "amount":3506.864,"expectedLitres":2.3536,"fuelType":"PETROL"}

{"status":true,"message":"Transaction authorised","data":{"status":"PENDING_PAYMENT", …,
 "authorizationUrl":"https://checkout.paystack.com/jn0ej3u6def5150",
 "expiresAt":"2026-09-17T21:46:11.090Z"}}
```

`expectedLitres` at **4dp** and an `amount` at **3dp** were both accepted, and the exact product
check passed on them. Before this, one decimal place was the most we had ever seen accepted
(`3501.5`, 2026-09-16). The server's parser is not the constraint.

**Confirms again:** `expiresAt` is 21:46:11 against a 21:26:11 authorise — **20 minutes**, a sixth
measurement agreeing with the other five (**#43**).

## 2. The hazard nobody asked about: that amount cannot be paid

`amount: 3506.864` is **350,686.4 kobo**. Paystack charges in whole kobo. The server accepted an
amount that the payment rail underneath it cannot collect exactly, and said nothing about it.

Nothing was scanned, so what Paystack actually does with the fractional kobo — round, truncate or
refuse at checkout — **remains unobserved, and deliberately so.** The fix is not to find out. It is
to never send one.

This is the same shape of mistake as **#48**: a `200` means *accepted by this endpoint*, not
*correct end to end*. A status code is not an observation of state.

## 3. So the rule is not "quote at 4dp" — it is "quote at the finest scale that lands on a whole kobo"

Whether a scale is usable depends on the **price**, which is exactly the fragility the tendered-amount
option was rejected for. Computed against a ₦3,507 tender:

| scale | litres | amount | whole kobo? | customer's shortfall |
|---|---|---|---|---|
| 2dp | 2.35 | ₦3,501.50 | ✅ | ₦5.50 |
| **3dp** | **2.353** | **₦3,505.97** | **✅** | **₦1.03** |
| 4dp | 2.3536 | ₦3,506.864 | ❌ 350,686.4 kobo | ₦0.136 |

And the usable scale moves with the price:

| price | 1dp | 2dp | 3dp | 4dp | 5dp |
|---|---|---|---|---|---|
| ₦1,490 (today) | ✅ | ✅ | ✅ | ❌ | ❌ |
| ₦1,491 | ✅ | ✅ | ❌ | ❌ | ❌ |
| ₦870.50 (the Reference's example) | ✅ | ✅ | ❌ | ❌ | ❌ |
| ₦1,250 | ✅ | ✅ | ✅ | ✅ | ✅ |

A fixed scale is wrong at some price. **10c computes it**: pick the finest scale whose product is an
exact number of kobo, floor litres to it, and send that product as the amount. At today's ₦1,490 that
is 3dp and the shortfall falls from ₦5.50 to ₦1.03; at ₦1,250 it disappears entirely.

## 4. What did not need re-probing

3dp is strictly coarser than the 4dp that was just accepted, and 1dp and 2dp have been accepted
before, so no further sitting is needed to adopt 3dp. The only thing this capture leaves unobserved
is the sub-kobo checkout behaviour in §2, which the rule in §3 makes unreachable.

---

**Housekeeping:** one real Paystack initialisation was created (`BPM-990f0b73…`) and left unpaid, as
every write probe in this project has been. It expires on its own after 20 minutes.
