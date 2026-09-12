// Implements the one irreversible call in the app. See PumpActivationRepository for why the call,
// the save and the read-back are one operation rather than three things a caller must remember.
package app.balancee.smartpump.display.data.repository

import app.balancee.smartpump.display.data.network.ApiError
import app.balancee.smartpump.display.data.network.ApiResult
import app.balancee.smartpump.display.data.network.PumpApiClient
import app.balancee.smartpump.display.data.network.dto.ActivateResponse
import app.balancee.smartpump.display.domain.network.DeviceIdProvider
import app.balancee.smartpump.display.domain.network.PumpCredentials
import app.balancee.smartpump.display.domain.network.PumpCredentialsStore
import app.balancee.smartpump.display.domain.repository.ActivationOutcome
import app.balancee.smartpump.display.domain.repository.PumpActivationRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PumpActivationRepositoryImpl @Inject constructor(
    private val api: PumpApiClient,
    private val store: PumpCredentialsStore,
    private val deviceIds: DeviceIdProvider,
) : PumpActivationRepository {

    override val isActivated: Boolean get() = store.isActivated

    override val pumpId: String? get() = store.current()?.pumpId

    override suspend fun activate(activationCode: String): ActivationOutcome {
        if (store.isActivated) return ActivationOutcome.AlreadyActivated

        return when (val result = api.activate(activationCode)) {
            is ApiResult.Success -> persist(result.data.toCredentials())
            is ApiResult.Failure -> result.error.toOutcome()
        }
    }

    /**
     * Write the credentials, then read them back and compare.
     *
     * The read-back is the point. A `save` that returns without throwing proves only that no
     * exception escaped; it does not prove the bytes are retrievable, and the store deliberately
     * discards its blob when it cannot be decrypted — so a write that silently produced an
     * unreadable blob would look identical to success. Since the code cannot be redeemed twice,
     * "probably saved" is not a state worth reporting as activated.
     *
     * One retry, because a transient write failure that we give up on costs a revoke-and-reissue.
     */
    private suspend fun persist(credentials: PumpCredentials): ActivationOutcome {
        var lastFailure: String? = null

        repeat(SAVE_ATTEMPTS) {
            val failure = try {
                store.save(credentials)
                val stored = store.current()
                when {
                    stored == null -> "credentials were written but read back as absent"
                    stored != credentials -> "credentials were written but read back different"
                    else -> return credentials.asOutcome()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The message only — never the exception's own toString chain, which on some
                // storage failures quotes the value it was writing.
                "storing the credentials failed (${e.javaClass.simpleName}: ${e.message})"
            }
            lastFailure = failure
        }

        return ActivationOutcome.CredentialsLost(
            "The pump activated, but $lastFailure. The activation code is spent and the keys " +
                "cannot be re-issued to this device — ask the station to revoke and reissue.",
        )
    }

    /**
     * Activated, with the deviceId echo checked. A mismatch does not undo the save: the credentials
     * are the half that cannot be replaced, and the server's deviceId is the one it will
     * authenticate against.
     */
    private fun PumpCredentials.asOutcome(): ActivationOutcome {
        val ours = deviceIds.deviceId()
        return if (deviceId == ours) {
            ActivationOutcome.Activated(pumpId)
        } else {
            ActivationOutcome.IdentityMismatch(pumpId = pumpId, sent = ours, returned = deviceId)
        }
    }

    private fun ApiError.toOutcome(): ActivationOutcome = when (this) {
        // A considered "no" from the server. Nothing was issued.
        is ApiError.Business -> ActivationOutcome.Refused(message, code, httpCode)

        // Non-envelope error bodies: an HTML 404 from a wrong base URL, a proxy's plain-text 502.
        // 5xx especially may have committed on the server before failing to answer, so this is
        // "unknown" and not "no".
        is ApiError.Http ->
            if (code in 500..599) {
                ActivationOutcome.Unreachable("the server returned $code")
            } else {
                ActivationOutcome.Refused(body?.take(ERROR_BODY_CHARS), null, code)
            }

        // No answer reached us. The server may still have activated this device — see Unreachable.
        is ApiError.Network -> ActivationOutcome.Unreachable(cause.message ?: "no response")

        // The server answered with something we could not read. If that answer was the successful
        // one, the code is spent and the secrets are gone — which is exactly CredentialsLost, and
        // must not be softened into "try again".
        is ApiError.Serialization -> ActivationOutcome.CredentialsLost(
            "The server answered but the response could not be read, so any credentials it issued " +
                "are lost. Ask the station whether this device activated before using another code.",
        )

        // Unsigned endpoint — unreachable in practice, handled so the branch is total.
        ApiError.NotActivated -> ActivationOutcome.Unreachable("activation was treated as a signed call")

        is ApiError.Unknown -> ActivationOutcome.Unreachable(
            cause.message ?: cause.javaClass.simpleName,
        )
    }

    /**
     * The one DTO→domain mapping that cannot wait for the payment phase, because the response it
     * maps is emitted once. Every field is carried across: dropping one here is unrecoverable, and
     * `pumpId` going missing is precisely the defect TODO #13 fixed.
     */
    private fun ActivateResponse.toCredentials() = PumpCredentials(
        deviceId = deviceId,
        pumpId = pumpId,
        apiKey = apiKey,
        signingSecret = signingSecret,
    )

    private companion object {
        const val SAVE_ATTEMPTS = 2
        const val ERROR_BODY_CHARS = 200
    }
}
