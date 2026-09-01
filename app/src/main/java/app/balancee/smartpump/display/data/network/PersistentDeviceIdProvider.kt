// Mints this device's deviceId once and never lets it change (TODO #16).
//
// Two deliberate choices, both about the one failure this class exists to prevent — a deviceId that
// changes after activation, which fails every signed request with `401 Device id does not match
// credential` and is only recoverable by revoking and reissuing credentials at the station:
//
//  - A random UUID, not ANDROID_ID. ANDROID_ID is tempting (unique already, no storage needed) but
//    it resets on factory reset — precisely the maintenance action a technician performs on a kiosk
//    tablet that is "acting up", which would silently turn a misbehaving tablet into a permanently
//    unauthenticated pump.
//
//  - Stored in its own plain SharedPreferences file, NOT inside the encrypted credentials blob. The
//    deviceId is not secret (it travels in the clear as X-Device-Id), so encryption buys nothing —
//    and it would couple identity to the KeyStore key. KeystorePumpCredentialsStore deliberately
//    drops its blob when that key is invalidated or the ciphertext is unreadable; if the deviceId
//    went with it we would mint a new identity on the next boot. A separate file also means
//    credentials clear() (revoke/reissue, debug re-onboarding) cannot touch it, so re-activation
//    reuses the same identity — which is what the backend expects.
package app.balancee.smartpump.display.data.network

import android.content.Context
import app.balancee.smartpump.display.domain.network.DeviceIdProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersistentDeviceIdProvider internal constructor(
    private val storage: DeviceIdStorage,
    private val mint: () -> String,
) : DeviceIdProvider {

    @Inject
    constructor(@ApplicationContext context: Context) :
        this(PrefsDeviceIdStorage(context), { UUID.randomUUID().toString() })

    // Blank is treated as absent: a truncated or half-written value is not an identity.
    private val cached = AtomicReference(storage.read()?.takeIf { it.isNotBlank() })

    override fun deviceId(): String {
        cached.get()?.let { return it }
        return synchronized(this) {
            // Re-check under the lock — another thread may have minted while we waited.
            cached.get() ?: mint().also { fresh ->
                // Fail loudly rather than hand back an id that will not survive a reboot: a
                // caller that activated against an unpersisted id would bind the station's
                // credentials to an identity this device can never present again.
                check(storage.write(fresh)) {
                    "Could not persist deviceId — refusing to issue an identity that would not " +
                        "survive a restart"
                }
                cached.set(fresh)
            }
        }
    }
}

/** Storage seam — keeps the mint-once/never-change logic above testable off-device. */
internal interface DeviceIdStorage {
    fun read(): String?

    /** Durable write. Returns false if the value did not reach disk. */
    fun write(value: String): Boolean
}

internal class PrefsDeviceIdStorage(context: Context) : DeviceIdStorage {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY_DEVICE_ID, null)

    // commit(), not apply(): the caller is about to send this id to /activate, so it must already
    // be on disk — an async write that loses a race with a crash would orphan the credentials.
    override fun write(value: String): Boolean =
        prefs.edit().putString(KEY_DEVICE_ID, value).commit()

    private companion object {
        const val PREFS_NAME = "device_identity"
        const val KEY_DEVICE_ID = "device_id"
    }
}
