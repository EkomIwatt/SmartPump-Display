// Single-row station identity provisioned during install. Held alongside DeviceConfig but
// kept as its own table because (a) it is set once on-site by the operator and almost never
// changes after, whereas DeviceConfig is operator-pushable any time, and (b) it carries
// security material (PIN hash + salt) that should not be intermixed with config fields.
//
// The Idle screen and receipts read displayName / logoBytes from here. The PIN gate on the
// attendant overlay reads pinHash + pinSalt for verification — never the raw PIN.
package app.balancee.smartpump.display.domain.model

import androidx.compose.runtime.Immutable

/**
 * @param stationId    Backend station identifier typed by the operator on install
 *                     (e.g. "BLC-LAG-0042"). Never derived from the device.
 * @param displayName  Human-readable station name shown on Idle screen + receipts.
 * @param logoBytes    Optional PNG bytes (≤512px on longer side). Null = fall back to
 *                     [displayName] rendered in the hero-serif style on the Idle screen.
 * @param pinHash      Base64-encoded PBKDF2-HMAC-SHA256 hash of the 4-digit PIN.
 * @param pinSalt      Base64-encoded 16-byte random salt unique to this device.
 * @param setupAtMs    Epoch millis when onboarding completed.
 */
@Immutable
data class StationIdentity(
    val stationId: String,
    val displayName: String,
    val logoBytes: ByteArray?,
    val pinHash: String,
    val pinSalt: String,
    val setupAtMs: Long,
) {
    // Compose's @Immutable needs structural equality. ByteArray's default equals is by
    // reference — override so two identities with the same logo compare as equal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StationIdentity) return false
        return stationId == other.stationId &&
            displayName == other.displayName &&
            logoBytes.contentEqualsOrBothNull(other.logoBytes) &&
            pinHash == other.pinHash &&
            pinSalt == other.pinSalt &&
            setupAtMs == other.setupAtMs
    }

    override fun hashCode(): Int {
        var result = stationId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + (logoBytes?.contentHashCode() ?: 0)
        result = 31 * result + pinHash.hashCode()
        result = 31 * result + pinSalt.hashCode()
        result = 31 * result + setupAtMs.hashCode()
        return result
    }
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
    when {
        this == null && other == null -> true
        this == null || other == null -> false
        else -> contentEquals(other)
    }
