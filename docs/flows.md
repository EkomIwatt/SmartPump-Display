# SmartPump Display — Transaction Flows

Five flows, all share the same hardware. The Android app drives the difference.

Authoritative visuals: `docs/Strict design screens/*.png`.

---

## Idle + Mode Select (entry point for customer-initiated flows)

The pump screen starts **IDLE** and waits for either a customer tap or an attendant swipe-up.

Customer taps "Start Transaction" → **MODE_SELECT**. Two mode buttons:
- **PRE-PAY** — fixed amount, customer pays before fuel flows
- **FILL UP** — open-ended, attendant authorises, customer pays after

From MODE_SELECT:
- PRE-PAY → AMOUNT_SELECT (preset tiles ₦2k, ₦5k, ₦10k, ₦20k, ₦50k, Custom) → PAYMENT_METHOD_SELECT
- FILL UP → FILLUP_CONFIRM → waits for attendant authorisation

**Payment methods (PRE-PAY only):** Balanceè App, Bank QR / Transfer, NFC / Tap card, USSD *737#, Cash — see attendant.

**Price guard:** if no price-per-litre has been pushed from the operator app, show "Price not set — contact operator" and block all transactions.

---

## Flow 1 — Fixed Pre-pay (Digital)

> Fixed amount, pay before fuel flows.

Customer selects amount and payment method. System waits for payment confirmation. Relay closes on webhook. Counts to target. Opens. Done.

### Screen sequence (customer-side)
1. **QR WAITING** (gold border, gold chip) — QR shown. 5-min expiry then auto-cancel.
2. **CONFIRMED → DISPENSING** (green border, green chip) — relay closed, live count updating in green monospace.
3. **COMPLETE** (gold border) — ✓ Done. Transaction complete. Ledger (LITRES / PAID / PRICE/L / TXN). Share-receipt button.

### Webhook payload (received by Android from Balanceè backend)
```json
POST /pump/authorise
{
  "mode": "fixed",
  "amount_naira": 5000,
  "litres_authorised": 5.75,
  "transaction_id": "BLC-00847",
  "nozzle_id": "1",
  "price_per_litre": 870
}
```

### Logic
- On webhook receipt: set state = DISPENSING, close relay, start counting pulses.
- Open relay when `pulseCount * litresPerPulse >= litres_authorised`.
- QR expiry: if no webhook received within 5 minutes, cancel transaction and return to IDLE.
- Invariant: do **not** leave relay in any state other than OPEN when idle.

This is the **primary flow** for V1.

---

## Flow 2 — Fill-up Cash

> Attendant authorises. Nozzle shuts. Customer pays cash.

The most common Nigerian scenario. Customer says "fill am." Attendant swipes up and taps FILL UP AUTHORISE. System runs open-ended. When the nozzle auto-shuts, screen shows the verified total. Attendant collects cash and taps CASH RECEIVED.

### Screen sequence
1. **FILLING — OPEN-ENDED** (cyan border, cyan chip) — live litre count in cyan monospace. "filling… nozzle shuts automatically." Running total in ₦ below. No litre target.
2. **TANK FULL — AMOUNT DUE** (gold border) — final verified count (cannot be changed). AMOUNT DUE in big gold ₦. PAY NOW: "Scan QR to pay digitally" (routes to Flow 3) **or** "↓ Cash collected below" (stays in Flow 2 until attendant taps CASH RECEIVED).
3. **ATTENDANT CONFIRMS** (green border) — CASH RECEIVED chip. Ledger with LITRES, AMOUNT, ATTENDANT, TXN. "This creates the audit record for litres vs cash."

### Nozzle shutoff detection — critical logic
There is no hardware signal from the nozzle. Shutoff is detected by **pulse timeout**:

```kotlin
// On every pulse:
lastPulseTime = System.currentTimeMillis()

// In a background timer (check every 500ms):
if (flowActive && (now - lastPulseTime) > 3000) {
    onNozzleShutoff()  // open relay, lock final count, display total
}
```

The 3-second timeout is configurable. Too short = false positives on slow flow. Too long = customer waits. 3s is the recommended starting value.

---

## Flow 3 — Fill-up Digital

> Tank full. QR for exact verified amount.

Same fill-up start as Flow 2. But after the nozzle shuts, instead of cash, a QR is generated encoding a bank transfer for the exact verified litre amount.

### Screen sequence
1. **FILLING** (cyan border) — identical to Flow 2 step 1.
2. **TANK FULL — QR GENERATED** (gold border) — `SCAN TO PAY`, `EXACT AMOUNT DUE ₦33,147`, "38.1L · verified". QR code below. "Open any bank app · scan · confirm — GTBank · Opay · PalmPay · any bank". "QR encodes exact fill-up amount. Dynamic — changes per transaction."
3. **PAYMENT CONFIRMED** (green border) — ✓ Paid. Ledger (LITRES, PAID, METHOD). "Webhook confirmed. Transaction…"

### Dynamic QR generation — post-fill-up
The QR is **not** static. It is generated after the fill-up completes using the final verified litre count. The QR encodes an NIP bank transfer to the station's virtual account number for the exact calculated amount. This is different from the pre-pay QR (Flow 1) which is generated *before* fuelling from a fixed amount. The Balanceè backend provides the virtual account number per station — it is stored on the Android unit during setup.

```kotlin
// After nozzle shutoff in fill-up mode:
finalLitres = pulseCount * litresPerPulse
amountDue = finalLitres * currentPricePerLitre
qrContent = generateNIPTransferQR(stationAccount, amountDue, txnId)
showQRScreen(qrContent, amountDue, finalLitres)
```

---

## Flow 4 — Cash Fixed Amount

> Attendant enters naira. System calculates litres.

Customer states a cash amount. Attendant enters it on the pump screen. Android calculates the litre cutoff from the pushed price. Relay cuts at exactly that litre count.

### Screen sequence
1. **ATTENDANT ENTERS ₦** (gold border) — ENTER CASH AMOUNT input (₦5,000 entered), CURRENT PRICE ₦870/L, LITRES CUTOFF 5.75 L. Big amber AUTHORISE CASH ₦5,000 button. "Relay opens. Cuts at 5.75L exactly."
2. **DISPENSING TO CUTOFF** (gold border) — live litre count in gold monospace, "of 5.75L target." ₦2,767 used · ₦5,000 cash. PRICE/L ₦870, CUTOFF AT 5.75. "Gold = cash mode. Counting to 5.75L. Will cut automatically."
3. **COMPLETE — CASH LOGGED** (green border) — ✓ Done. CASH · FIXED chip. Ledger (LITRES, CASH, ATTENDANT, TXN). "Record includes attendant ID + litres + cash amount. Fraud audit trail."

### Litre cutoff calculation
```kotlin
// On AUTHORISE CASH button tap:
cashAmount = parseFloat(inputField.value)         // e.g. 5000
currentPrice = getStoredPrice()                   // pushed from operator, e.g. 870
litresCutoff = cashAmount / currentPrice          // 5.7471...
// Round down to avoid dispensing more than paid:
litresCutoff = Math.floor(litresCutoff * 100) / 100  // 5.74
startFixedDispense(litresCutoff, "cash", cashAmount)
```

Round **down** — never dispense more than the customer paid for.

---

## Flow 5 — USSD Offline

> No data. No bank app. Just a 2G phone.

Customer dials a USSD code. Bank processes it and sends an SMS to the pump unit's SIM. Android parses the SMS and unlocks the relay. Works on any 2G phone. No internet on either device required.

### Screen sequence
1. **USSD CODE DISPLAYED** (blue/gold border) — "DIAL THIS CODE" — primary GTBank `*737*5000*847#`, plus other banks (Access `*901*5000*847#`, Zenith `*966*5000*847#`, UBA `*919*5000*847#`). "Pump shows USSD codes for 3 major banks. Customer dials on their 2G phone."
2. **WAITING FOR SMS** (gold border) — "AWAITING SMS" chip, "WAITING FOR SMS CONFIRMATION", "Bank sends SMS after USSD completes. Usually 10–30 seconds." Details: AMOUNT ₦5,000, REF 847, SIM STATUS MTN · Signal. "Android monitors pump unit SIM for incoming SMS containing the reference."
3. **SMS RECEIVED → DISPENSING** (green border) — "SMS CONFIRMED" chip, live litre count, METHOD: OFFLINE. "SMS parsed. Counting now."

### SMS parsing — what to look for
The Android unit must listen for incoming SMS on the pump SIM. Each bank sends a different SMS format. The parser must extract: (a) confirmation that payment succeeded, (b) the amount, (c) the transaction reference that matches the pending transaction.

```
// GTBank example SMS:
// "Your GTBank acct was debited ₦5,000.00 for BALANCEE-847 on 30/04/26..."
// Extract: amount=5000, ref=847

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sms = intent.getStringExtra("pdu_parsed")
        val p = SMSParser.parse(sms)
        if (p.matches(pendingTxn)) authorisePump(p.amount)
    }
}
```

V1 scope: GTBank (`*737#`) only. Add Access and Zenith before field test. Full Nigerian bank coverage in V2.

---

## Attendant interface

The attendant **never** sees the customer payment screen. Their interface is hidden behind a swipe-up gesture. **Three actions only**, always:

| Action               | Enabled when             | Behaviour                                                                 |
|----------------------|--------------------------|---------------------------------------------------------------------------|
| FILL UP AUTHORISE    | state = IDLE             | Starts Flow 2/3 — opens relay open-ended, monitors for 3s pulse timeout.  |
| AUTHORISE CASH ₦___  | state = IDLE             | Inline amount input → Flow 4 — computes litre cutoff, starts fixed dispense. |
| CASH RECEIVED        | state = FILLUP_AWAITING_CASH | Confirms cash collected in Flow 2 → COMPLETE. Greyed out otherwise. |

Swipe-up from bottom 20% of screen. `translateY` animation. Swipe down or tap outside dismisses.

V1 has no roles. Anyone with access to the pump screen can perform any attendant action. Role-based access (attendant ID, manager approval) is V2.

---

## Webhook contract summary

```
POST /pump/authorise
{
  "mode": "fixed" | "fillup",       // NEW in V1: fillup mode
  "amount_naira": <int, fixed only>,
  "litres_authorised": <float, fixed only>,
  "transaction_id": "BLC-NNNNN",
  "nozzle_id": "<string>",
  "price_per_litre": <int>
}
```

Backend pushes price-per-litre updates separately (not in webhook). Stored on the Android unit. Used for Flow 3 amount calc and Flow 4 litre cutoff.

---

## V1 vs V2 scope

- **V1 required:** all 5 flows. GTBank USSD only. No attendant roles. SMS parser for GTBank.
- **V2:** Access + Zenith + UBA SMS parsers, attendant roles, manager approval for cash variances, role-based audit.
