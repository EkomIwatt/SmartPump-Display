// The single source of truth for this device's API credentials.
//
// Read synchronously by the signing interceptor on OkHttp's dispatcher thread ([current]), and
// written once by the activation flow ([save]). Kept as a domain seam so the storage mechanism
// (in-memory now; encrypted-at-rest when the activation/onboarding flow lands) can change without
// touching the interceptor or the API service.
package app.balancee.smartpump.display.domain.network

interface PumpCredentialsStore {

    /**
     * Current credentials, or null if the device has not been activated. Synchronous by design —
     * the signing interceptor calls this inline while building each request. Implementations must
     * keep it cheap (memory or a fast local read), never a network call.
     */
    fun current(): PumpCredentials?

    /** True once [save] has stored a credential set. Convenience over `current() != null`. */
    val isActivated: Boolean

    /** Persist the credentials returned by /api/pump/activate. Overwrites any existing set. */
    suspend fun save(credentials: PumpCredentials)

    /** Wipe stored credentials (revoke/reissue, or debug re-onboarding). */
    suspend fun clear()
}
