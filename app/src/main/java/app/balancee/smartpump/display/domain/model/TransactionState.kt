// Single sealed hierarchy covering all five flows in docs/flows.md.
// Persisted to Room (via PulseRepository) on every transition so the app resumes after a power cut.
// See docs/state-machine.md for the transition tables and invariants.
package app.balancee.smartpump.display.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Immutable
sealed class TransactionState {

    // ---- ENTRY ----

    /** Pump locked, waiting for input. Default. */
    @Serializable @SerialName("idle")
    data object Idle : TransactionState()

    /** Customer tapped "Start Transaction" — choosing PRE-PAY vs FILL UP. */
    @Serializable @SerialName("mode_select")
    data object ModeSelect : TransactionState()

    // ---- PRE-PAY (Flow 1, Flow 5 entry) ----

    /** Customer picking an amount tile (₦2k/₦5k/₦10k/₦20k/₦50k/Custom). */
    @Serializable @SerialName("prepay_amount_select")
    data object PrepayAmountSelect : TransactionState()

    /** Customer picking a payment method. */
    @Serializable @SerialName("prepay_method_select")
    data class PrepayMethodSelect(
        val amountNaira: Int,
    ) : TransactionState()

    /** QR / NFC / digital wait. 5-min expiry, then auto-cancel back to Idle. */
    @Serializable @SerialName("prepay_awaiting_payment")
    data class PrepayAwaitingPayment(
        val flow: TransactionFlow,           // FIXED_PREPAY_DIGITAL or USSD_OFFLINE
        val amountNaira: Int,
        val method: PaymentMethod,
        val txnId: String,
        val pricePerLitre: Int,
    ) : TransactionState()

    /** USSD-specific: SMS expected on the pump SIM. */
    @Serializable @SerialName("ussd_awaiting_sms")
    data class UssdAwaitingSms(
        val amountNaira: Int,
        val txnRef: String,                  // e.g. "847"
        val txnId: String,
        val pricePerLitre: Int,
    ) : TransactionState()

    // ---- FILL-UP (Flow 2, Flow 3) ----

    /** Customer-initiated fill-up; waiting for attendant to tap FILL UP AUTHORISE. */
    @Serializable @SerialName("fillup_awaiting_attendant_auth")
    data object FillupAwaitingAttendantAuth : TransactionState()

    /** Open-ended dispense. No litre target. Live count. */
    @Serializable @SerialName("fillup_dispensing")
    data class FillupDispensing(
        val txnId: String,
        val pricePerLitre: Int,
        val litresSoFar: Double,
    ) : TransactionState()

    /** Nozzle shutoff detected. Verified count locked. Customer chooses cash or QR. */
    @Serializable @SerialName("fillup_tank_full")
    data class FillupTankFull(
        val txnId: String,
        val pricePerLitre: Int,
        val verifiedLitres: Double,
        val amountDueNaira: Int,
    ) : TransactionState()

    /** Customer chose digital after fill-up. Dynamic NIP QR shown. */
    @Serializable @SerialName("fillup_digital_awaiting_payment")
    data class FillupDigitalAwaitingPayment(
        val txnId: String,
        val verifiedLitres: Double,
        val amountDueNaira: Int,
        val qrContent: String,               // NIP transfer payload
    ) : TransactionState()

    /** Customer chose cash. Attendant has not yet tapped CASH RECEIVED. */
    @Serializable @SerialName("fillup_awaiting_cash_confirm")
    data class FillupAwaitingCashConfirm(
        val txnId: String,
        val verifiedLitres: Double,
        val amountDueNaira: Int,
    ) : TransactionState()

    // ---- CASH FIXED (Flow 4) ----

    /** Attendant entering ₦ amount for fixed cash dispense. */
    @Serializable @SerialName("cash_fixed_amount_entry")
    data object CashFixedAmountEntry : TransactionState()

    /**
     * Cash-fixed authorised; counting to litre cutoff.
     * Kept distinct from FixedDispensing during Phase 1 to keep state explicit;
     * may consolidate later if the UI simplifies (see docs/state-machine.md).
     */
    @Serializable @SerialName("cash_fixed_dispensing")
    data class CashFixedDispensing(
        val txnId: String,
        val pricePerLitre: Int,
        val cashAmountNaira: Int,
        val litresCutoff: Double,            // pre-computed, floored to 0.01L
        val litresSoFar: Double,
    ) : TransactionState()

    // ---- FIXED DISPENSING (Flow 1 + Flow 5) ----

    /**
     * Generic fixed-target dispensing — counts toward a known litre target.
     * [method] is the digital channel that authorised the dispense (null for cash flows).
     * Carrying it on the state lets a power-cut resume rebuild the right `Complete` audit
     * row without having to re-derive from `flow` (kotlinx default = null keeps older
     * persisted JSON blobs backwards-compatible).
     */
    @Serializable @SerialName("fixed_dispensing")
    data class FixedDispensing(
        val flow: TransactionFlow,
        val txnId: String,
        val pricePerLitre: Int,
        val amountNaira: Int,
        val litresAuthorised: Double,
        val litresSoFar: Double,
        val method: PaymentMethod? = null,
    ) : TransactionState()

    // ---- TERMINAL ----

    @Serializable @SerialName("complete")
    data class Complete(
        val flow: TransactionFlow,
        val txnId: String,
        val litres: Double,
        val amountNaira: Int,
        val method: PaymentMethod? = null,   // null for cash-only flows that have no digital method
        val attendantId: String? = null,     // null in V1 (no roles)
    ) : TransactionState()

    @Serializable @SerialName("error")
    data class Error(
        val message: String,
        val recoverable: Boolean,
    ) : TransactionState()
}
