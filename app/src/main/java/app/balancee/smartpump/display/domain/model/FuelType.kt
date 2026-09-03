// The fuel this pump dispenses.
//
// Lives in domain rather than alongside the wire DTOs because DeviceConfig needs it: a pump has to
// know what it sells before any backend conversation happens (7b sets it device-locally; a future
// GET /config will push it). Keeping it in data/network/dto would have made the domain layer reach
// into a transport file.
//
// It keeps its @Serializable/@SerialName annotations rather than being mirrored by a separate wire
// enum: the backend's vocabulary IS the domain vocabulary here — /authorise accepts exactly these
// four strings — so a parallel enum plus a mapper would be ceremony guarding nothing. The serial
// names are the contract; a Kotlin-side rename must not change them.
package app.balancee.smartpump.display.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FuelType {
    @SerialName("PETROL") PETROL,
    @SerialName("KEROSENE") KEROSENE,
    @SerialName("DIESEL") DIESEL,
    @SerialName("COOKING_GAS") COOKING_GAS,
    ;

    /** Attendant-facing label, e.g. "Cooking gas". */
    val displayName: String
        get() = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
