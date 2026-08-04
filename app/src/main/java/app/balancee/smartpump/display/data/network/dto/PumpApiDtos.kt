// Wire DTOs for the Balancee Pump API (docs/phase7_blocker_resolution.md → endpoints).
//
// These are the transport shape only — kept separate from domain models; mapping happens at the
// repository boundary. Several fields are PROVISIONAL pending the sandbox / backend finalising the
// schema (flagged inline): notably the money UNIT on `amount` and the exact `/config` payload.
//
// @SerialName is set explicitly on every field so a rename on the Kotlin side never silently breaks
// the wire contract. Json is configured with ignoreUnknownKeys, so extra server fields are safe.
package app.balancee.smartpump.display.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Fuel types accepted by /authorise. Serial names must match the backend's enum strings. */
@Serializable
enum class FuelType {
    @SerialName("PETROL") PETROL,
    @SerialName("KEROSENE") KEROSENE,
    @SerialName("DIESEL") DIESEL,
    @SerialName("COOKING_GAS") COOKING_GAS,
}

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
)

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
 * PROVISIONAL shape (backend hasn't finalised — blocker item 4). Modelled as a price-per-fuel-type
 * map; adjust once the real payload lands. Same UNIT caveat as [AuthoriseRequest.amount].
 */
@Serializable
data class PumpConfigResponse(
    @SerialName("prices") val prices: Map<FuelType, Long> = emptyMap(),
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
