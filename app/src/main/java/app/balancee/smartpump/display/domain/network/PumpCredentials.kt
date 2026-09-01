// Per-device API credentials issued by the backend at activation.
//
// Returned exactly once by POST /api/pump/activate (see docs/phase7_blocker_resolution.md) and
// cached on-device. [signingSecret] is the HMAC key for every signed request and must never leave
// the device or be logged; if lost, the station has to revoke + reissue.
package app.balancee.smartpump.display.domain.network

/**
 * @param deviceId      This unit's device identifier (echoes what we sent to /activate, minted by
 *                      [DeviceIdProvider]). Goes out as the `X-Device-Id` header.
 * @param pumpId        The backend's UUID for the physical pump this device drives, issued once by
 *                      /activate and required in the BODY of /authorise and /transactions/upload.
 *                      Emitted only in that one response, so it must be captured at activation or
 *                      the station has to revoke and reissue (TODO #13). Not to be confused with
 *                      `DeviceConfig.pumpLabel` ("PUMP 1"), which is only a caption on the screen —
 *                      sending the label here yields `401 pumpId does not match authenticated
 *                      device`. Required (no default) so activation code cannot forget it.
 * @param apiKey        Public-ish key sent as the `X-Api-Key` header (e.g. "bal_live_…").
 * @param signingSecret Secret HMAC-SHA256 key. Never sent on the wire — only used to compute
 *                      `X-Signature`. Treat as sensitive: no logging, secure-at-rest storage.
 */
data class PumpCredentials(
    val deviceId: String,
    val pumpId: String,
    val apiKey: String,
    val signingSecret: String,
) {
    /**
     * Redacted. The generated data-class toString() would print both secrets, so any
     * `Log.d(TAG, "$credentials")`, any crash report, any `ApiResult` dump would leak them — the
     * same defect as logging the /activate body (TODO #12), just via a different door. deviceId and
     * pumpId are kept: neither is secret (both go out in the clear — one as X-Device-Id, one in the
     * request body) and they are what you actually need when reading a log.
     */
    override fun toString(): String =
        "PumpCredentials(deviceId=$deviceId, pumpId=$pumpId, apiKey=***, signingSecret=***)"
}
