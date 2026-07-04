// Per-device API credentials issued by the backend at activation.
//
// Returned exactly once by POST /api/pump/activate (see docs/phase7_blocker_resolution.md) and
// cached on-device. [signingSecret] is the HMAC key for every signed request and must never leave
// the device or be logged; if lost, the station has to revoke + reissue.
package app.balancee.smartpump.display.domain.network

/**
 * @param deviceId      This unit's device identifier (echoes what we sent to /activate). Goes out
 *                      as the `X-Device-Id` header.
 * @param apiKey        Public-ish key sent as the `X-Api-Key` header (e.g. "bal_live_…").
 * @param signingSecret Secret HMAC-SHA256 key. Never sent on the wire — only used to compute
 *                      `X-Signature`. Treat as sensitive: no logging, secure-at-rest storage.
 */
data class PumpCredentials(
    val deviceId: String,
    val apiKey: String,
    val signingSecret: String,
)
