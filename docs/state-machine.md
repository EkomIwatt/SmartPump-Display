# SmartPump Display — State Machine

The state machine is one sealed hierarchy that covers all five flows. Persisted to Room on every transition so the app can resume after a power cut.

For the screen-by-screen description see `docs/flows.md`. For visuals see `docs/Strict design screens/`.

---

## Sealed hierarchy (Kotlin)

```kotlin
enum class TransactionFlow {
    FIXED_PREPAY_DIGITAL,   // Flow 1
    FILLUP_CASH,            // Flow 2
    FILLUP_DIGITAL,         // Flow 3 (can branch from Flow 2 mid-transaction)
    CASH_FIXED,             // Flow 4
    USSD_OFFLINE,           // Flow 5
}

enum class PaymentMethod {
    BALANCEE_APP,
    BANK_QR_TRANSFER,
    NFC_CARD,
    USSD,
    CASH_SEE_ATTENDANT,
}

sealed class TransactionState {

    /** Pump locked, waiting for input. Default. */
    data object Idle : TransactionState()

    /** Customer tapped "Start Transaction" — choosing PRE-PAY vs FILL UP. */
    data object ModeSelect : TransactionState()

    // ---- PRE-PAY (Flow 1, Flow 5 entry) ----

    /** Customer picking amount tile (₦2k/₦5k/₦10k/₦20k/₦50k/Custom). */
    data object PrepayAmountSelect : TransactionState()

    /** Customer picking payment method. */
    data class PrepayMethodSelect(
        val amountNaira: Int,
    ) : TransactionState()

    /** QR / NFC / digital wait. 5-min expiry. */
    data class PrepayAwaitingPayment(
        val flow: TransactionFlow,           // FIXED_PREPAY_DIGITAL or USSD_OFFLINE
        val amountNaira: Int,
        val method: PaymentMethod,
        val txnId: String,
        val pricePerLitre: Int,
    ) : TransactionState()

    /** USSD-specific: SMS expected on the pump SIM. */
    data class UssdAwaitingSms(
        val amountNaira: Int,
        val txnRef: String,                  // e.g. "847"
        val txnId: String,
        val pricePerLitre: Int,
    ) : TransactionState()

    // ---- FILL-UP (Flow 2, Flow 3) ----

    /** FILL UP picked; waiting for attendant to tap FILL UP AUTHORISE. */
    data object FillupAwaitingAttendantAuth : TransactionState()

    /** Open-ended dispense. No litre target. Live count. */
    data class FillupDispensing(
        val txnId: String,
        val pricePerLitre: Int,
        val litresSoFar: Double,             // updates live
    ) : TransactionState()

    /** Nozzle shutoff detected. Verified count locked. Customer chooses cash or QR. */
    data class FillupTankFull(
        val txnId: String,
        val pricePerLitre: Int,
        val verifiedLitres: Double,
        val amountDueNaira: Int,
    ) : TransactionState()

    /** Customer chose to pay via QR after fill-up. Dynamic NIP QR shown. */
    data class FillupDigitalAwaitingPayment(
        val txnId: String,
        val verifiedLitres: Double,
        val amountDueNaira: Int,
        val qrContent: String,               // NIP transfer payload
    ) : TransactionState()

    /** Customer chose cash. Attendant has not yet tapped CASH RECEIVED. */
    data class FillupAwaitingCashConfirm(
        val txnId: String,
        val verifiedLitres: Double,
        val amountDueNaira: Int,
    ) : TransactionState()

    // ---- CASH FIXED (Flow 4) ----

    /** Attendant entering ₦ amount for fixed cash dispense. */
    data object CashFixedAmountEntry : TransactionState()

    /** Cash-fixed authorised; counting to litre cutoff. */
    data class CashFixedDispensing(
        val txnId: String,
        val pricePerLitre: Int,
        val cashAmountNaira: Int,
        val litresCutoff: Double,            // pre-computed
        val litresSoFar: Double,
    ) : TransactionState()

    // ---- FIXED DISPENSING (Flow 1 + Flow 5 + Flow 4 share this) ----

    /** Generic fixed-target dispensing — counts toward a known litre target. */
    data class FixedDispensing(
        val flow: TransactionFlow,
        val txnId: String,
        val pricePerLitre: Int,
        val amountNaira: Int,
        val litresAuthorised: Double,
        val litresSoFar: Double,
    ) : TransactionState()

    // ---- TERMINAL ----

    data class Complete(
        val flow: TransactionFlow,
        val txnId: String,
        val litres: Double,
        val amountNaira: Int,
        val method: PaymentMethod?,
        val attendantId: String?,            // null for customer-initiated digital flows
    ) : TransactionState()

    data class Error(
        val message: String,
        val recoverable: Boolean,
    ) : TransactionState()
}
```

Note: `CashFixedDispensing` could be folded into `FixedDispensing` with a `flow = CASH_FIXED` discriminator. Keeping it separate during Phase 1 makes the state explicit; Phase 2 may consolidate if it simplifies the UI.

---

## Transition tables

### Flow 1 — Fixed Pre-pay (Digital)

| From                              | Event                                | To                                  |
|-----------------------------------|--------------------------------------|-------------------------------------|
| `Idle`                            | customer tap Start                   | `ModeSelect`                        |
| `ModeSelect`                      | tap PRE-PAY                          | `PrepayAmountSelect`                |
| `PrepayAmountSelect`              | amount chosen                        | `PrepayMethodSelect`                |
| `PrepayMethodSelect`              | method chosen (NOT USSD, NOT CASH)   | `PrepayAwaitingPayment`             |
| `PrepayAwaitingPayment`           | webhook received                     | `FixedDispensing`                   |
| `PrepayAwaitingPayment`           | 5-min expiry, no webhook             | `Idle` (+ cancel txn)               |
| `FixedDispensing`                 | pulses ≥ litresAuthorised            | `Complete`                          |

### Flow 2 — Fill-up Cash

| From                              | Event                                | To                                  |
|-----------------------------------|--------------------------------------|-------------------------------------|
| `Idle`                            | attendant tap FILL UP AUTHORISE      | `FillupDispensing`                  |
| `Idle`                            | customer tap → ModeSelect → FILL UP  | `FillupAwaitingAttendantAuth`       |
| `FillupAwaitingAttendantAuth`     | attendant tap FILL UP AUTHORISE      | `FillupDispensing`                  |
| `FillupDispensing`                | 3s pulse timeout                     | `FillupTankFull`                    |
| `FillupTankFull`                  | "↓ Cash collected below"             | `FillupAwaitingCashConfirm`         |
| `FillupAwaitingCashConfirm`       | attendant tap CASH RECEIVED          | `Complete`                          |

### Flow 3 — Fill-up Digital (branches from Flow 2 mid-transaction)

| From                              | Event                                | To                                  |
|-----------------------------------|--------------------------------------|-------------------------------------|
| `FillupTankFull`                  | "Scan QR to pay digitally"           | `FillupDigitalAwaitingPayment`      |
| `FillupDigitalAwaitingPayment`    | webhook received                     | `Complete`                          |
| `FillupDigitalAwaitingPayment`    | 5-min expiry                         | `FillupAwaitingCashConfirm` (fallback) |

### Flow 4 — Cash Fixed

| From                              | Event                                | To                                  |
|-----------------------------------|--------------------------------------|-------------------------------------|
| `Idle`                            | attendant tap AUTHORISE CASH ₦…      | `CashFixedAmountEntry`              |
| `CashFixedAmountEntry`            | attendant confirms ₦ amount          | `CashFixedDispensing`               |
| `CashFixedDispensing`             | pulses ≥ litresCutoff                | `Complete`                          |

### Flow 5 — USSD Offline

| From                              | Event                                | To                                  |
|-----------------------------------|--------------------------------------|-------------------------------------|
| `PrepayMethodSelect`              | method = USSD                        | `UssdAwaitingSms`                   |
| `UssdAwaitingSms`                 | SMS parsed, ref matches              | `FixedDispensing` (flow = USSD)     |
| `UssdAwaitingSms`                 | timeout (configurable, default 5min) | `Idle`                              |

### Universal

| From                              | Event                                | To                                  |
|-----------------------------------|--------------------------------------|-------------------------------------|
| _any_                             | hardware disconnect / parse error    | `Error(recoverable=true)`           |
| _any_                             | power cut                            | (state persisted; resume on boot)   |
| `Error(recoverable=true)`         | retry / dismiss                      | previous state or `Idle`            |

---

## Persistence rules

- Every state transition writes the new state to Room (`TransactionStateEntity`, one row, upsert).
- On app boot: read the last persisted state. If terminal (`Complete` / `Error(recoverable=false)`), reset to `Idle`. Otherwise resume.
- The relay state is **derived** from the transaction state, not stored separately:
  - Relay CLOSED only when state ∈ {`FixedDispensing`, `FillupDispensing`, `CashFixedDispensing`}.
  - Relay OPEN everywhere else.
  - On boot, before resuming state, force relay OPEN. Then re-derive.

---

## Invariants

1. Relay is never left in a state other than OPEN when the app is in `Idle`.
2. `FillupTankFull.verifiedLitres` is locked the moment the 3s pulse timeout fires. It cannot be edited.
3. `CashFixedDispensing.litresCutoff` is rounded **down** to 0.01L. Never round up.
4. No transaction can start unless `pricePerLitre` is known (stored from operator push).
5. `Complete` is the only terminal state that produces an audit record. `Error(recoverable=false)` produces a separate failure record.
