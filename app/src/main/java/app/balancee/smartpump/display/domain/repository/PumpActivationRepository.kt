// First-boot activation: redeem an activation code, and keep what it returns.
//
// This is the only irreversible step in the app. POST /api/pump/activate emits the apiKey and the
// signingSecret exactly once, and it settles this unit's pumpId permanently. A code is single-use,
// so a response we fail to keep cannot be asked for again — the station has to revoke and reissue.
// Everything downstream (every signed call, the whole payment path) depends on this one response
// having been captured.
//
// The seam exists so that capturing is not left to a caller's good intentions. Before this, the API
// client returned the credentials to whoever asked and nothing persisted them; the risk was not
// hypothetical, it was the default. [activate] performs the call, the save and the read-back as one
// operation, and its outcome tells the caller which of those three got as far as happening.
package app.balancee.smartpump.display.domain.repository

interface PumpActivationRepository {

    /** True once this device holds credentials. Cheap — no network. */
    val isActivated: Boolean

    /**
     * Redeem [activationCode] and persist the credentials it returns.
     *
     * Never throws: every failure is an [ActivationOutcome] the caller can act on, because the
     * difference between "the code was rejected" and "the code was spent and we lost the answer"
     * decides whether a second code is needed.
     */
    suspend fun activate(activationCode: String): ActivationOutcome
}

/** What happened to the one-shot activation attempt. */
sealed interface ActivationOutcome {

    /** Credentials are stored and were read back. The device can now sign requests. */
    data class Activated(val pumpId: String) : ActivationOutcome

    /**
     * Refused before activation happened — a wrong, expired or already-redeemed code. The code is
     * spent only if it was already spent; a fresh one can be tried.
     *
     * [code] is the server's stable identifier where it sends one (e.g. "INVALID_REQUEST"); it is
     * absent on several paths, so [message] is the fallback.
     */
    data class Refused(
        val message: String?,
        val code: String?,
        val httpCode: Int?,
    ) : ActivationOutcome

    /**
     * The attempt was already made on this device.
     *
     * Refused locally rather than sent, because a *valid* second code would succeed and overwrite
     * the stored credentials — silently abandoning the pumpId the backend already associates with
     * this unit, and leaving the first code spent on an identity nothing can reach any more. Wipe
     * the credentials deliberately (revoke and reissue) before activating again.
     */
    data object AlreadyActivated : ActivationOutcome

    /**
     * The request did not complete — no connectivity, a timeout, a 5xx.
     *
     * Read this as **unknown, not "no"**. A timeout can land after the server has already committed
     * the activation, in which case the code is spent and the secrets are gone. So the recovery is
     * to check with the backend whether this deviceId activated before burning a second code, not
     * to retry blindly.
     */
    data class Unreachable(val detail: String) : ActivationOutcome

    /**
     * The worst case, and the reason this seam exists: the server activated us and the answer did
     * not survive. Either the response could not be parsed, or it could not be written to storage.
     * The code is spent, the secrets are unrecoverable, and the station must revoke and reissue.
     *
     * [detail] is for an attendant and a log. It never carries the credentials themselves.
     */
    data class CredentialsLost(val detail: String) : ActivationOutcome

    /**
     * Activated and saved, but the server echoed a different deviceId than the one we sent.
     *
     * The credentials are kept deliberately — they are the irreplaceable half, and the server's own
     * deviceId is the one it will authenticate, so storing its value is what keeps the device
     * usable. It is reported separately because it means our permanent identity and the backend's
     * disagree, which nothing downstream can detect on its own.
     */
    data class IdentityMismatch(
        val pumpId: String,
        val sent: String,
        val returned: String,
    ) : ActivationOutcome
}
