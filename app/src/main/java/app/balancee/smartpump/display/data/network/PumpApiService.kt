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
