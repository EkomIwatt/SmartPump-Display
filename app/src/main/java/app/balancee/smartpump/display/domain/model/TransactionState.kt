// Single sealed hierarchy covering all five flows in docs/flows.md.
// Persisted to Room (via PulseRepository) on every transition so the app resumes after a power cut.
// See docs/state-machine.md for the transition tables and invariants.
//
// Money note: all amounts and prices are carried as KOBO (Long), never naira. A sub-naira
// fuel price (e.g. 87_050 = ₦870.50/L) must survive the whole state machine without being
// truncated to whole naira. Render with ui/util/formatNaira(kobo). Persisted blobs from before
// the kobo migration (which used naira `Int` fields like `amountNaira`/`pricePerLitre`) fail to
// deserialise into these renamed fields and fall back to Idle via PulseRepositoryImpl's
// runCatching — acceptable for an in-flight transaction across an app upgrade.
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

    /**
     * Unified pre-pay/fill-up selection screen.
     *
     * Phase 6b collapsed three previous states (ModeSelect / PrepayAmountSelect /
     * PrepayMethodSelect) into this one so the customer sees mode + amount + method
     * on a single progressive-reveal screen matching `docs/Strict design screens/...`.
     * All three selections start null. PRE_PAY needs all three before Confirm enables;
     * FILL_UP only needs `mode`.
     *
     * `amountKobo` is the customer-selected pre-pay amount in kobo (whole-naira tiles ×100).
     *
     * Kotlinx defaults to null on each field, so persisted blobs from the old
     * `data object ModeSelect` still deserialise into a blank ModeSelect on boot.
     */
    @Serializable @SerialName("mode_select")
    data class ModeSelect(
        val mode: TransactionMode? = null,
        val amountKobo: Long? = null,
        val method: PaymentMethod? = null,
    ) : TransactionState()

    // ---- PRE-PAY (Flow 1, Flow 5 entry) ----

    /** QR / NFC / digital wait. 5-min expiry, then auto-cancel back to Idle. */
    @Serializable @SerialName("prepay_awaiting_payment")
    data class PrepayAwaitingPayment(
        val flow: TransactionFlow,           // FIXED_PREPAY_DIGITAL or USSD_OFFLINE
        val amountKobo: Long,
        val method: PaymentMethod,
        val txnId: String,
        val priceKoboPerLitre: Long,
    ) : TransactionState()

    /** USSD-specific: SMS expected on the pump SIM. */
    @Serializable @SerialName("ussd_awaiting_sms")
    data class UssdAwaitingSms(
        val amountKobo: Long,
        val txnRef: String,                  // e.g. "847"
        val txnId: String,
        val priceKoboPerLitre: Long,
    ) : TransactionState()

    // ---- FILL-UP (Flow 2, Flow 3) ----

    /**
     * Customer-initiated fill-up; waiting for attendant to tap FILL UP AUTHORISE.
     *
     * Phase 6d: customer pre-declares how they want to pay *after* the tank is full
     * via [intent]. Null = not picked yet. The choice is advisory — the customer can
     * still change it at FillupTankFull — but capturing it here means the screen has
     * an actual call-to-action ("Tell attendant to start") that the strict-design
     * spec calls for. Default value keeps older persisted `data object` JSON blobs
     * deserialisable into `FillupAwaitingAttendantAuth(intent = null)` on boot.
     */
    @Serializable @SerialName("fillup_awaiting_attendant_auth")
    data class FillupAwaitingAttendantAuth(
        val intent: PostFillIntent? = null,
    ) : TransactionState()

    /** Open-ended dispense. No litre target. Live count. */
    @Serializable @SerialName("fillup_dispensing")
    data class FillupDispensing(
        val txnId: String,
        val priceKoboPerLitre: Long,
        val litresSoFar: Double,
    ) : TransactionState()

    /** Nozzle shutoff detected. Verified count locked. Customer chooses cash or QR. */
    @Serializable @SerialName("fillup_tank_full")
    data class FillupTankFull(
        val txnId: String,
        val priceKoboPerLitre: Long,
        val verifiedLitres: Double,
        val amountDueKobo: Long,
    ) : TransactionState()

    /** Customer chose digital after fill-up. Dynamic NIP QR shown. */
    @Serializable @SerialName("fillup_digital_awaiting_payment")
    data class FillupDigitalAwaitingPayment(
        val txnId: String,
        val verifiedLitres: Double,
        val amountDueKobo: Long,
        val qrContent: String,               // NIP transfer payload
    ) : TransactionState()

    /** Customer chose cash. Attendant has not yet tapped CASH RECEIVED. */
    @Serializable @SerialName("fillup_awaiting_cash_confirm")
    data class FillupAwaitingCashConfirm(
        val txnId: String,
        val verifiedLitres: Double,
        val amountDueKobo: Long,
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
        val priceKoboPerLitre: Long,
        val cashAmountKobo: Long,
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
        val priceKoboPerLitre: Long,
        val amountKobo: Long,
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
        val amountKobo: Long,
        val method: PaymentMethod? = null,   // null for cash-only flows that have no digital method
        val attendantId: String? = null,     // null in V1 (no roles)
    ) : TransactionState()

    @Serializable @SerialName("error")
    data class Error(
        val message: String,
        val recoverable: Boolean,
    ) : TransactionState()
}
