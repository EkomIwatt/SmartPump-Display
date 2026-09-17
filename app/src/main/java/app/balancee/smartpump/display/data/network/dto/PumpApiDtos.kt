// Wire DTOs for the Balancee Pump API (docs/phase7_blocker_resolution.md → endpoints).
//
// These are the transport shape only — kept separate from domain models; mapping happens at the
// repository boundary. These were PROVISIONAL for a year; they are not any more. The #32 gate
// (2026-09-16/17) drove the whole lifecycle against production, and the money unit, the decimal
// question, the `/config` payload and the status set are all settled **by observation** — captures
// in `docs/api-probes/2026-09-16-prod-config/` and `…-prod-gate/`. Where the wire and the Reference
// PDF disagreed, the wire won. Anything still marked open below is genuinely open.
//
// @SerialName is set explicitly on every field so a rename on the Kotlin side never silently breaks
// the wire contract. Json is configured with ignoreUnknownKeys, so extra server fields are safe.
package app.balancee.smartpump.display.data.network.dto

// FuelType is a domain type (a pump knows what it sells before it ever talks to the backend) and
// carries the @SerialName wire strings /authorise expects. See domain/model/FuelType.kt.
import app.balancee.smartpump.display.domain.model.FuelType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal

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
    // UNIT: NAIRA (decided 2026-08-04, and corroborated by every gate capture). The app carries
    // money as kobo internally; [nairaFromKobo] / [nairaForSale] own the conversion.
    //
    // Failure is loud, not silent: the server enforces `amount == expectedLitres ×
    // stationPricePerUnit` exactly and returns 400 AMOUNT_MISMATCH, so a wrong unit breaks
    // /authorise before money moves or fuel flows — it cannot mischarge a customer 100×.
    //
    // DECIMALS: ANSWERED 2026-09-16 at the #32 gate — accepted, and the exact check passes on one
    // (`amount 3501.5` for 2.35 L at ₦1490). TODO #44 is why this is no longer a `Long`: at any
    // price, most metered litre figures produce a fractional naira amount, so an integer type could
    // not express a fill-up at all. See [NairaAmountSerializer] for the wire form, and note that
    // BigDecimal rather than Double is deliberate — the server's check is an equality.
    @SerialName("amount")
    @Serializable(with = NairaAmountSerializer::class)
    val amount: BigDecimal,
    @SerialName("expectedLitres") val expectedLitres: Double,
    @SerialName("fuelType") val fuelType: FuelType,
)

/**
 * **One transaction, one shape.** `/authorise`, `GET /transactions/{id}` and
 * `/transactions/upload` all return the **same object** — verified byte-for-byte across the #32
 * gate captures, where they differ only in `status` and the envelope's `message`:
 *
 * ```
 * authorise → {"status":"PENDING_PAYMENT","transactionId":…,"paymentReference":"BPM-…",
 *              "authorizationUrl":"https://checkout.paystack.com/…","expiresAt":…}
 * poll      → {"status":"PAID",           … same five fields, same values …}
 * upload    → {"status":"DISPENSED",      … same five fields, same values …}
 * ```
 *
 * TODO **#46**. Three separate types had been modelled, two of them carrying only three fields — so
 * `authorizationUrl` and `expiresAt` **parsed away silently** under `ignoreUnknownKeys` on every
 * poll and every upload. The one that mattered is `expiresAt` on a poll: **#43** requires the expiry
 * countdown to read the server's value rather than a constant, and the poll is exactly where a
 * running screen would refresh it.
 *
 * Kept as one class with three aliases rather than three classes: an alias cannot drift, and a
 * fourth endpoint returning this shape needs no fourth type.
 *
 * **Why these are nullable when all five were observed on all three responses.** Only `status` and
 * `transactionId` identify the transaction; the rest describe a payment that a future endpoint (a
 * cash sale, a refund) may legitimately not have. Failing a poll to deserialize would strand a
 * customer who has already paid, which is a worse outcome than a null the caller must handle — the
 * opposite trade from `PumpConfigResponse`, where a silent default became a wrong price on every
 * litre. The two consumers that must not accept null (`authorizationUrl` on an authorise, and
 * `paymentReference` before an upload) check for it where it matters.
 */
@Serializable
data class PumpTransactionResponse(
    /** Observed set: `PENDING_PAYMENT` → `PAID` → `DISPENSED` (**#18d**, closed at the gate). */
    @SerialName("status") val status: String,
    /** The id **we** generated and sent, echoed back unchanged. */
    @SerialName("transactionId") val transactionId: String,
    /** The server's own reference (`BPM-…`). `/transactions/upload` requires it. */
    @SerialName("paymentReference") val paymentReference: String? = null,
    /** Paystack checkout URL — rendered as the on-screen QR, and the only thing a customer can pay. */
    @SerialName("authorizationUrl") val authorizationUrl: String? = null,
    /**
     * ISO-8601. **Twenty minutes** from the authorise, measured six times across two sittings
     * (**#43**) — against three places in the app that said five. Read it; never assume it.
     */
    @SerialName("expiresAt") val expiresAt: String? = null,
)

/** The three names the endpoints are described by. All one shape — see [PumpTransactionResponse]. */
typealias AuthoriseResponse = PumpTransactionResponse

// ---- Payment status: GET /api/pump/transactions/{id} (signed) -------------------------------

/** Polled during the PENDING_PAYMENT window. The correctness guarantee; push is freshness only. */
typealias TransactionStatusResponse = PumpTransactionResponse

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

/**
 * A `200` here means **accepted**, not **stored** — **#48**. A second upload carrying corrected
 * litres returns `200 Transaction recorded` and changes nothing; first write wins. Nothing in this
 * response echoes `actualLitresDispensed`, so the app cannot read its own record back at all.
 */
typealias UploadTransactionResponse = PumpTransactionResponse
