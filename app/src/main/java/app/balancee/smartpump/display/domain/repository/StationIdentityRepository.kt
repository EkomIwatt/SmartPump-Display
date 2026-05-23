// Gateway for reading/writing the single station-identity row + PIN verification.
package app.balancee.smartpump.display.domain.repository

import app.balancee.smartpump.display.domain.model.StationIdentity
import kotlinx.coroutines.flow.Flow

interface StationIdentityRepository {

    /** Live stream — null until the device is provisioned. */
    fun observeIdentity(): Flow<StationIdentity?>

    /** Whether the device has been provisioned (one-shot read, suspends on first DB hit). */
    suspend fun isProvisioned(): Boolean

    /** One-shot read; null if not provisioned. */
    suspend fun getIdentity(): StationIdentity?

    /**
     * Persists a fresh identity. Hashes [rawPin] with a freshly-generated salt before
     * writing — the raw PIN is never stored.
     *
     * Replaces any existing row; intended to be called on first-boot onboarding and from
     * the debug-screen "re-run onboarding" path.
     */
    suspend fun provision(
        stationId: String,
        displayName: String,
        logoBytes: ByteArray?,
        rawPin: String,
    )

    /** Constant-time PIN verification against the stored hash. */
    suspend fun verifyPin(rawPin: String): Boolean

    /** Replaces the PIN; verifies the old PIN first. Returns false if old PIN is wrong. */
    suspend fun updatePin(oldRawPin: String, newRawPin: String): Boolean

    /** Wipes the identity row. Used by the debug-screen "re-run onboarding" action. */
    suspend fun reset()
}
