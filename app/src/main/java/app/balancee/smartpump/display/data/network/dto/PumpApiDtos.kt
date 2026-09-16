// Wire DTOs for the Balancee Pump API (docs/phase7_blocker_resolution.md → endpoints).
//
// These are the transport shape only — kept separate from domain models; mapping happens at the
// repository boundary. Several fields are PROVISIONAL pending the sandbox / backend finalising the
// schema (flagged inline): notably the money UNIT on `amount` and the exact `/config` payload.
//
// @SerialName is set explicitly on every field so a rename on the Kotlin side never silently breaks
// the wire contract. Json is configured with ignoreUnknownKeys, so extra server fields are safe.
package app.balancee.smartpump.display.data.network.dto

// FuelType is a domain type (a pump knows what it sells before it ever talks to the backend) and
// carries the @SerialName wire strings /authorise expects. See domain/model/FuelType.kt.
import app.balancee.smartpump.display.domain.model.FuelType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- Activation: POST /api/pump/activate (public, @Unsigned) --------------------------------

@Serializable
data class ActivateRequest(
    @SerialName("activationCode") val activationCode: String,
    @SerialName("deviceId") val deviceId: String,
)

/** apiKey + signingSecret are emitted exactly ONCE here — persist immediately, never re-fetchable. */
@Serializable
data class ActivateResponse(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("pumpId") val pumpId: String,
    @SerialName("apiKey") val apiKey: String,
    @SerialName("signingSecret") val signingSecret: String,
) {
    /**
     * Redacted for the same reason as PumpCredentials.toString() — this is the one response body
     * carrying the secrets, so it must not be printable by accident (TODO #12). Serialization is
     * unaffected: kotlinx uses the generated serializer, not toString().
     */
    override fun toString(): String =
        "ActivateResponse(deviceId=$deviceId, pumpId=$pumpId, apiKey=***, signingSecret=***)"
}

// ---- Start a sale: POST /api/pump/authorise (signed) ----------------------------------------

@Serializable
data class AuthoriseRequest(
    @SerialName("pumpId") val pumpId: String,
    // Locally generated, doubles as the idempotency key.
    @SerialName("transactionId") val transactionId: String,
    // UNIT: NAIRA (decided 2026-08-04). The Reference never states a unit, but its worked example
    // — amount 7000 / expectedLitres 10 → ₦700/L — only reads sensibly as naira (as kobo it would be
    // ₦7/L). The app carries money as kobo (Long) internally, so the repository mapper owns the ÷100
    // and is the single place to flip this if the backend ever says otherwise.
    //
    // Failure is loud, not silent: the server enforces `amount == expectedLitres ×
    // stationPricePerUnit` exactly and returns 400 "Amount mismatch", so a wrong unit breaks
    // /authorise before money moves or fuel flows — it cannot mischarge a customer 100×.
    //
    // STILL OPEN: does `amount` accept decimals? The example is integer naira. A fill-up of 38.1 L
    // at ₦870.50/L is ₦33,166.05, which integer-naira cannot express — and because the server check
    // is exact, a rounded 33166 is REJECTED rather than merely off-by-a-naira. If they confirm
    // integer-only, station pricing is constrained to whole naira per litre (a business call).
    @SerialName("amount") val amount: Long,
    @SerialName("expectedLitres") val expectedLitres: Double,
    @SerialName("fuelType") val fuelType: FuelType,
)

@Serializable
data class AuthoriseResponse(
    // Expected "PENDING_PAYMENT" on success. Kept as String until the full status set is confirmed.
    @SerialName("status") val status: String,
    @SerialName("transactionId") val transactionId: String,
    @SerialName("paymentReference") val paymentReference: String,
    // Paystack checkout URL — rendered as the on-screen QR.
    @SerialName("authorizationUrl") val authorizationUrl: String,
    // ISO-8601; drives the 5-min QR-expiry / poll window.
    @SerialName("expiresAt") val expiresAt: String,
)

// ---- Payment status: GET /api/pump/transactions/{id} (signed) -------------------------------

/** Polled every ~10s during the PENDING_PAYMENT window as the fallback to the PAID push. */
@Serializable
data class TransactionStatusResponse(
    // e.g. "PENDING_PAYMENT" → "PAID"/"DISPENSED". String until the set is confirmed.
    @SerialName("status") val status: String,
    @SerialName("transactionId") val transactionId: String,
    @SerialName("paymentReference") val paymentReference: String? = null,
)

// ---- Config: GET /api/pump/config (signed) --------------------------------------------------

/**
 * OBSERVED shape. Copied field-for-field from the first authenticated `/config` response this
 * project ever received — `docs/api-probes/2026-09-16-prod-config/`, production, 2026-09-16:
 *
 * ```
 * {"status":true,"message":"Pump config","data":{"pumpId":"3727aebf-…","stationName":"Kachi",
 *  "fuelType":"PETROL","pricePerUnit":1490,"updatedAt":"2026-09-15T09:44:39.187Z"}}
 * ```
 *
 * It replaces a `Map<FuelType, Long>` invented in July from our summary of the Reference. There is
 * no price list: `/config` describes **this pump** — the fuel it dispenses and the one price it
 * charges. The endpoint therefore answers the ask recorded as "nothing in the API tells a pump what
 * it sells or what to charge", which was true of the document and not of the server.
 *
 * **Nothing here is defaulted, and that is the point.** The old field defaulted to `emptyMap()`, so
 * a completely wrong shape parsed cleanly into "this pump sells nothing" and no layer above could
 * tell. A missing or renamed field must fail loudly as [ApiError.Serialization] instead — the same
 * ruling as TODO #13 made for `pumpId`. A pump whose price is genuinely unset has never been
 * observed; if the server answers `null` there, this throws, which is the correct outcome for a
 * value that would otherwise become a wrong-price sale on every litre.
 */
@Serializable
data class PumpConfigResponse(
    @SerialName("pumpId") val pumpId: String,
    @SerialName("stationName") val stationName: String,
    /** Single fuel — one pump, one nozzle, one product. */
    @SerialName("fuelType") val fuelType: FuelType,
    /**
     * Price per litre, as an integer. **Naira**, inferred rather than stated: the observed 1490 is a
     * plausible pump price and 14.90 is not. Consistent with the `amount = naira` call in TODO #17,
     * which was also ours to make. The app stores kobo, so the mapper owns the ×100.
     *
     * Typed `Long` deliberately. If the server ever sends a decimal this fails loudly instead of
     * rounding money silently — which is the observation TODO #18c is still waiting for.
     */
    @SerialName("pricePerUnit") val pricePerUnit: Long,
    /** ISO-8601 with millis. Unconsumed so far; this is what a freshness check would read. */
    @SerialName("updatedAt") val updatedAt: String,
)

// ---- Dispense upload: POST /api/pump/transactions/upload (signed, idempotent) ---------------

@Serializable
data class UploadTransactionRequest(
    @SerialName("pumpId") val pumpId: String,
    @SerialName("transactionId") val transactionId: String,
    @SerialName("paymentReference") val paymentReference: String,
    @SerialName("actualLitresDispensed") val actualLitresDispensed: Double,
    @SerialName("startedAt") val startedAt: String,   // ISO-8601
    @SerialName("completedAt") val completedAt: String, // ISO-8601
)

@Serializable
data class UploadTransactionResponse(
    // Expected "DISPENSED".
    @SerialName("status") val status: String,
    @SerialName("transactionId") val transactionId: String,
    @SerialName("paymentReference") val paymentReference: String,
)
