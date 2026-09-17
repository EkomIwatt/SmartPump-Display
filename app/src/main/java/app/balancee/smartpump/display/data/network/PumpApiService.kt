// Retrofit surface for the Balancee Pump API. All calls are signed by PumpSigningInterceptor
// except activate(), tagged @Unsigned (it runs before credentials exist).
//
// Every method returns ApiEnvelope<T>, never T — Reference §1 wraps every response in
// { status, message, data }. PumpApiClient unwraps. See ApiEnvelope.kt.
//
// Paths are relative to BuildConfig.PUMP_API_BASE_URL (which ends in '/'), so no leading slash.
package app.balancee.smartpump.display.data.network

import app.balancee.smartpump.display.data.network.dto.ActivateRequest
import app.balancee.smartpump.display.data.network.dto.ActivateResponse
import app.balancee.smartpump.display.data.network.dto.ApiEnvelope
import app.balancee.smartpump.display.data.network.dto.AuthoriseRequest
import app.balancee.smartpump.display.data.network.dto.AuthoriseResponse
import app.balancee.smartpump.display.data.network.dto.PumpConfigResponse
import app.balancee.smartpump.display.data.network.dto.TransactionStatusResponse
import app.balancee.smartpump.display.data.network.dto.UploadTransactionRequest
import app.balancee.smartpump.display.data.network.dto.UploadTransactionResponse
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface PumpApiService {

    /** First-boot activation. Public — no signing (no credentials yet). */
    @Unsigned
    @POST("api/pump/activate")
    suspend fun activate(@Body body: ActivateRequest): ApiEnvelope<ActivateResponse>

    /** Start a sale → returns the Paystack authorizationUrl to render as a QR. */
    @POST("api/pump/authorise")
    suspend fun authorise(@Body body: AuthoriseRequest): ApiEnvelope<AuthoriseResponse>

    /**
     * The same endpoint with a hand-built body. Used only by the debug probe panel, for the one
     * question our own types make unaskable: [AuthoriseRequest.amount] is a `Long`, so the client
     * physically cannot send the decimal amount that TODO #18c is about — and the answer decides
     * whether a fill-up can be authorised at all, since `price × litres` is fractional for most
     * litre values (₦1490 × 2.35 L = ₦3,501.50).
     *
     * It still goes through the signing interceptor, the envelope and our error mapping. The only
     * thing it bypasses is the request DTO, which is the thing under test.
     */
    @POST("api/pump/authorise")
    suspend fun authoriseRaw(@Body body: JsonObject): ApiEnvelope<AuthoriseResponse>

    /** Poll payment status during the PENDING_PAYMENT window (fallback to the PAID push). */
    @GET("api/pump/transactions/{id}")
    suspend fun transactionStatus(
        @Path("id") transactionId: String,
    ): ApiEnvelope<TransactionStatusResponse>

    /** Current price per fuel type. Fetched on boot and before every authorise. */
    @GET("api/pump/config")
    suspend fun config(): ApiEnvelope<PumpConfigResponse>

    /** Upload a completed dispense. Idempotent on transactionId — safe to retry from the queue. */
    @POST("api/pump/transactions/upload")
    suspend fun uploadTransaction(
        @Body body: UploadTransactionRequest,
    ): ApiEnvelope<UploadTransactionResponse>
}
