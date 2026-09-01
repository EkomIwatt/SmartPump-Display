// This unit's permanent identity on the Balancee backend.
//
// Reference §4.1: deviceId is "a unique hardware identifier defined by your hardware client
// software" — i.e. ours to mint, not issued by the server. It is sent to /activate and thereafter
// on every signed request as X-Device-Id, and the backend binds the issued credentials to it.
//
// Kept as a domain seam (like PumpCredentialsStore) so the activation flow and the API client
// depend on the identity, not on where it is stored.
package app.balancee.smartpump.display.domain.network

interface DeviceIdProvider {

    /**
     * This device's identifier, minted on first call and stable for the life of the install.
     *
     * Synchronous and cheap by design — callers treat it like a constant. Must return the *same*
     * value forever once activation has run: if it changes, every signed request fails
     * `401 Device id does not match credential` and the station has to revoke and reissue on-site.
     */
    fun deviceId(): String
}
