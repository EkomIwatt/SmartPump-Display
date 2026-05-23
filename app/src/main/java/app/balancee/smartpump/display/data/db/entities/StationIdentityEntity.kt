// Single-row table (id = 1) holding the station identity provisioned at install.
// Raw PIN is never stored — only the PBKDF2 hash + per-device salt.
package app.balancee.smartpump.display.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "station_identity")
data class StationIdentityEntity(
    @PrimaryKey val id: Int = 1,
    val stationId: String,
    val displayName: String,
    /** PNG bytes for the station logo, capped to 512px on the longer side. Null = use [displayName] in serif fallback. */
    val logoBytes: ByteArray?,
    /** Base64-NO_WRAP of the PBKDF2-HMAC-SHA256 hash. */
    val pinHash: String,
    /** Base64-NO_WRAP of the 16-byte per-device salt. */
    val pinSalt: String,
    val setupAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StationIdentityEntity) return false
        return id == other.id &&
            stationId == other.stationId &&
            displayName == other.displayName &&
            (logoBytes?.contentEquals(other.logoBytes) ?: (other.logoBytes == null)) &&
            pinHash == other.pinHash &&
            pinSalt == other.pinSalt &&
            setupAtMs == other.setupAtMs
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + stationId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + (logoBytes?.contentHashCode() ?: 0)
        result = 31 * result + pinHash.hashCode()
        result = 31 * result + pinSalt.hashCode()
        result = 31 * result + setupAtMs.hashCode()
        return result
    }
}
