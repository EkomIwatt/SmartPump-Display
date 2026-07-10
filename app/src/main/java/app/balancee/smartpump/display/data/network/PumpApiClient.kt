// Transport-level client over PumpApiService: every call returns a typed ApiResult instead of
// throwing, and the idempotent upload gets bounded retry. This is intentionally a THIN wrapper —
// it does not map to domain models. DTO↔domain mapping lives one layer up in the feature
// repositories (built in the payment-flows phase), because the mapping is where the still-
// provisional bits land (money unit on `amount`, the final `/config` shape). Keeping the client
// transport-only means those open questions don't ripple down into it.
package app.balancee.smartpump.display.data.network

import app.balancee.smartpump.display.data.network.dto.ActivateRequest
import app.balancee.smartpump.display.data.network.dto.ActivateResponse
import app.balancee.smartpump.display.data.network.dto.AuthoriseRequest
import app.balancee.smartpump.display.data.network.dto.AuthoriseResponse
import app.balancee.smartpump.display.data.network.dto.PumpConfigResponse
import app.balancee.smartpump.display.data.network.dto.TransactionStatusResponse
import app.balancee.smartpump.display.data.network.dto.UploadTransactionRequest
import app.balancee.smartpump.display.data.network.dto.UploadTransactionResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PumpApiClient @Inject constructor(
    private val service: PumpApiService,
) {

    /** First-boot activation. Public/unsigned — the one call valid before credentials exist. */
    suspend fun activate(activationCode: String, deviceId: String): ApiResult<ActivateResponse> =
        safeApiCall { service.activate(ActivateRequest(activationCode, deviceId)) }

    /** Start a sale. On success the response carries the Paystack authorizationUrl for the QR. */
    suspend fun authorise(request: AuthoriseRequest): ApiResult<AuthoriseResponse> =
        safeApiCall { service.authorise(request) }

    /** Poll payment status during the PENDING_PAYMENT window (fallback to the PAID push). */
    suspend fun transactionStatus(transactionId: String): ApiResult<TransactionStatusResponse> =
        safeApiCall { service.transactionStatus(transactionId) }

    /** Current price per fuel type. */
    suspend fun config(): ApiResult<PumpConfigResponse> =
        safeApiCall { service.config() }

    /**
     * Upload a completed dispense. Idempotent on transactionId, so the in-flight [retryingApiCall]
     * backoff is safe. (Durable cross-restart replay of the offline queue is WorkManager's job.)
     */
    suspend fun uploadTransaction(
        request: UploadTransactionRequest,
    ): ApiResult<UploadTransactionResponse> =
        retryingApiCall { safeApiCall { service.uploadTransaction(request) } }
}
