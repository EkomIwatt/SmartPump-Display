// The "go and find out what this pump charges" seam, narrow on purpose.
//
// Phase 10c-bis. Until then nothing in the app ever wrote the server's price into DeviceConfig:
// `PumpConfigResponse` had three consumers and none of them stored anything, while the only writers
// of DeviceConfig were two settings screens and a debug-build seed. The price the customer saw and
// the price the sale was authorised at were therefore unrelated numbers that happened to agree
// because somebody typed one to match the other.
//
// Kept in domain, and deliberately returning nothing: the boot caller only needs "refresh what you
// know", not the payload. The data-layer implementation exposes a richer call for the one consumer
// that needs the response itself.
package app.balancee.smartpump.display.domain.config

interface DeviceConfigSync {

    /**
     * Fetch the server's config and store it. Best-effort by contract — a pump with no connection,
     * or one not yet activated, keeps whatever it already had and carries on selling at it.
     * Failure is expected often enough that it must never be an exception.
     */
    suspend fun refresh()
}
