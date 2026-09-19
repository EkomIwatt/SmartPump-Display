// GET /api/pump/config, stored. The write-through that Phase 10c-bis existed to add.
//
// **Why a fetch has a side effect.** The correctness guarantee (OQ #8) is that every authorise is
// preceded by a fresh `/config`, so the sale is priced at the server's figure whatever the device
// believes. That already worked — and it is exactly what made the divergence invisible, because the
// authorise was right while the price on the customer's screen was whatever an operator last typed.
// Caching the response the processor already fetches costs one write and makes the two agree by
// construction, on the path that runs most often.
//
// The operator's typed price is not retired by this. It is the pre-activation fallback: `/config`
// is a signed call, so a pump that has not redeemed its activation code cannot reach it at all, and
// a pump with no connection must still be able to sell at the last price it knew.
//
// **The station name is taken too, as of 10g (2026-09-19), on the same terms.** It was deliberately
// left alone when this file was written, because changing it silently inside a price sync was the
// wrong way to resolve a duplication. Resolved on its own terms now, the split is:
//
//   - `DeviceConfig.stationName` — the **receipt**, and its only real reader. A receipt is a
//     financial document, so the name on it is the one the operator's books use: the backend's.
//     It should not be editable by anyone holding the attendant PIN (OQ #19).
//   - `StationIdentity.displayName` — the **screens**, beside `logoBytes`. Branding, set with the
//     logo at onboarding, and rightly local and mutable.
//
// So two fields is the answer rather than a duplication to collapse. The gate found a *third*
// name — `/config` returned "Kachi" for a pump showing "Demo Station" and printing
// "Total Lekki Ph2" — which is what forced the question.
//
// Not yet pinned to the sale: `transactions` has no station-name column, so `ReceiptText` reads
// the *current* config and a rename changes the name on every past receipt re-shared. Same shape
// as #37, which was fixed by storing the price on the row. Boarded, not folded in — it is a
// schema v6 migration.
package app.balancee.smartpump.display.data.config

import app.balancee.smartpump.display.data.network.ApiResult
import app.balancee.smartpump.display.data.network.PumpApiClient
import app.balancee.smartpump.display.data.network.dto.PumpConfigResponse
import app.balancee.smartpump.display.domain.config.DeviceConfigSync
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.EventType
import app.balancee.smartpump.display.domain.repository.DeviceConfigRepository
import app.balancee.smartpump.display.domain.repository.EventRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A `/config` response, plus what this device believed a moment before it arrived.
 *
 * [previousKoboPerLitre] is the part a caller cannot recover afterwards — by the time [PumpConfigSync]
 * returns, the old figure has been overwritten. It is what makes "the price moved during this sale"
 * answerable at all.
 */
data class SyncedConfig(
    val config: PumpConfigResponse,
    val previousKoboPerLitre: Long?,
) {
    /** The server's price in the unit the app carries money in. `/config` states whole naira. */
    val koboPerLitre: Long get() = config.pricePerUnit * KOBO_PER_NAIRA

    /**
     * The station's name, as the backend holds it.
     *
     * Blank is treated as absent rather than as a name: a pump whose receipts print an empty line
     * is worse than one printing a stale name, and an empty string is far more likely to be a gap
     * in the backend's record than a deliberate rename.
     */
    val stationName: String? get() = config.stationName.trim().takeIf { it.isNotBlank() }

    /**
     * The stored price differed from the server's and has just been replaced.
     *
     * False on a pump that had no price at all: that is a first sync, not a change, and calling it
     * one would put a "price changed" row in the log of every freshly activated pump.
     */
    val priceChanged: Boolean
        get() = previousKoboPerLitre != null && previousKoboPerLitre != koboPerLitre
}

@Singleton
class PumpConfigSync @Inject constructor(
    private val client: PumpApiClient,
    private val deviceConfig: DeviceConfigRepository,
    private val events: EventRepository,
) : DeviceConfigSync {

    /** Boot path. The result is the stored config; a failure leaves the device as it was. */
    override suspend fun refresh() {
        fetch()
    }

    /**
     * Fetch, store, and hand back both the response and the price it displaced.
     *
     * The store happens before this returns, so a caller that goes on to authorise is quoting
     * against a figure the screen is already showing.
     */
    suspend fun fetch(): ApiResult<SyncedConfig> =
        when (val result = client.config()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(writeThrough(result.data))
        }

    private suspend fun writeThrough(config: PumpConfigResponse): SyncedConfig {
        val existing = deviceConfig.getConfig()
        val synced = SyncedConfig(config = config, previousKoboPerLitre = existing?.koboPerLitre)

        // The name the backend holds wins, falling back to whatever this device had. See the
        // header for why the receipt's name is the backend's to own.
        val stationName = synced.stationName ?: existing?.stationName

        // Nothing moved — skip the write rather than bump `updatedAt` on every authorise, which
        // would make the timestamp mean "last contacted" instead of "last changed".
        if (existing != null &&
            existing.koboPerLitre == synced.koboPerLitre &&
            existing.fuelType == config.fuelType &&
            existing.stationName == stationName
        ) {
            return synced
        }

        deviceConfig.saveConfig(
            existing?.copy(
                koboPerLitre = synced.koboPerLitre,
                fuelType = config.fuelType,
                stationName = stationName ?: existing.stationName,
                updatedAt = System.currentTimeMillis(),
            ) ?: DeviceConfig(
                // An activated pump that has never been configured by hand becomes sellable here,
                // which is the point: a price change is meant to stop being a visit to every pump.
                koboPerLitre = synced.koboPerLitre,
                fuelType = config.fuelType,
            ).let { fresh ->
                // Only when the backend actually has a name — otherwise DeviceConfig's own
                // default stands, and a blank is never written.
                stationName?.let { fresh.copy(stationName = it) } ?: fresh
            },
        )

        if (synced.priceChanged) {
            events.record(
                type = EventType.PRICE_SYNCED,
                // "from the backend", not "from the operator". This is the /config path, and
                // the operator's own edit records no event at all — so this is the only price
                // event there is, and until 10g (2026-09-19) it credited the one party that
                // cannot have made the change. It exists to tell an operator the price moved
                // with nobody at the pump.
                detail = "Price updated from the backend: " +
                    "${formatNaira(synced.previousKoboPerLitre!!)} → ${formatNaira(synced.koboPerLitre)} per litre.",
            )
        }
        return synced
    }
}

/**
 * Kobo as a naira string. A local copy rather than the UI's formatter — data must not reach up into
 * `ui`, and this is the only shape needed here.
 */
internal fun formatNaira(kobo: Long): String = "₦%,.2f".format(kobo / 100.0)

private const val KOBO_PER_NAIRA = 100L
