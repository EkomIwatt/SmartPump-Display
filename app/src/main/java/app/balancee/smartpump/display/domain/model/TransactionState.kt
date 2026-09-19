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

    /**
     * QR / digital wait, then auto-cancel back to Idle.
     *
     * **The window is the server's, not ours (TODO #43).** It was documented here as five minutes
     * for a year; production gives **twenty**, measured six times across two sittings. A screen that
     * gave up at five abandoned a sale the server would still have honoured for another fifteen,
     * with the customer standing at the pump. [expiresAtEpochMs] carries the server's own figure and
     * the countdown reads it; the five-minute constant survives only as the fallback for a response
     * that omitted it.
     *
     * @param amountKobo **what will be collected**, not what the customer tendered. From 10d this
     *   carries the authorised figure, because that is what the Paystack page shows: at ₦1,490/L a
     *   ₦5,000 pre-pay is authorised at ₦4,998.95, and the screen used to print the ₦5,000 beside a
     *   QR that would charge the other number.
     * @param checkoutUrl the Paystack page the QR encodes — **the only thing a customer can pay**.
     *   Null on a state persisted before 10c, and on the mock's fabricated sales; the screen falls
     *   back to showing the reference rather than a QR that goes nowhere.
     * @param expiresAtEpochMs server expiry. Epoch millis rather than an Instant because this class
     *   is persisted through kotlinx and a Long needs no serializer.
     */
    @Serializable @SerialName("prepay_awaiting_payment")
    data class PrepayAwaitingPayment(
        val flow: TransactionFlow,           // FIXED_PREPAY_DIGITAL or USSD_OFFLINE
        val amountKobo: Long,
        val method: PaymentMethod,
        val txnId: String,
        val priceKoboPerLitre: Long,
        val checkoutUrl: String? = null,
        val expiresAtEpochMs: Long? = null,
        /**
         * Litres the server authorised, when it authorised any (10d).
         *
         * Persisted because a restart has to resume the sale that exists rather than re-derive it:
         * the quote lands on a payable litre step while `DeviceConfig.litresCutoff` floors to 2 dp,
         * so re-deriving stops the pump a few millilitres short of what was paid for. Null on a
         * state written before 10d and on the mock's sales, where the fallback still applies.
         */
        val litresAuthorised: Double? = null,
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
        /**
         * What the QR encodes. Was a fabricated NIP transfer payload built from the operator's
         * virtual account; from 10c it is the Paystack checkout URL the server returned, which is
         * the only form of it a customer can actually pay.
         */
        val qrContent: String,
        val expiresAtEpochMs: Long? = null,
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
        /**
         * Set only when the attendant ended a fixed sale before it reached its target (OQ #22):
         * the litres the customer paid for, where [litres] is what actually flowed. Null for every
         * sale that finished normally. Defaulted, so rows persisted before it existed still decode.
         */
        val litresTarget: Double? = null,
    ) : TransactionState()

    /**
     * @param message   What the CUSTOMER sees. One plain line they can act on, which for most
     *                  failures means "see attendant" — a customer cannot act on a clock skew or a
     *                  station's stock level (OQ #17, approved 2026-09-12).
     * @param recoverable Whether trying again could work. Read by the error screen, which presents
     *                  a retryable failure differently from a dead end.
     * @param attendantDetail The diagnostic half, shown only in the swipe-up attendant panel —
     *                  behind the PIN, never on the customer-facing card. Null when there is
     *                  nothing an attendant could do that the customer line does not already say.
     *
     * Adding [attendantDetail] needed no Room migration: the whole state is persisted as
     * kotlinx JSON in one column (`pulse_state.transactionStateJson`), and a new field with a
     * default decodes cleanly from rows written before it existed.
     */
    @Serializable @SerialName("error")
    data class Error(
        val message: String,
        val recoverable: Boolean,
        val attendantDetail: String? = null,
    ) : TransactionState()
}
