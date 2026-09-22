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
import app.balancee.smartpump.display.domain.util.runCatchingCancellable
import kotlinx.coroutines.CompletableDeferred
import java.time.Clock
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
     * Whether [koboPerLitre] is a price at all, as opposed to the absence of one (review #7).
     *
     * `pricePerUnit` is a non-null `Long`, so it fails loudly on `null` and **silently** on `0` —
     * and a station whose price has not been set yet is the ordinary way to get a 0. Nothing about
     * zero is small: quoting against it divides by it, and storing it wipes the last figure this
     * pump knew. Every reader of this class screens it here, once, rather than each discovering
     * the arithmetic for itself.
     */
    val hasUsablePrice: Boolean get() = koboPerLitre > 0

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
        get() = hasUsablePrice && previousKoboPerLitre != null && previousKoboPerLitre != koboPerLitre
}

@Singleton
class PumpConfigSync @Inject constructor(
    private val client: PumpApiClient,
    private val deviceConfig: DeviceConfigRepository,
    private val events: EventRepository,
    // Injected so a test can age a prefetch past its window without waiting a real minute.
    private val clock: Clock = Clock.systemUTC(),
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
     *
     * **Never throws**, which is [DeviceConfigSync.refresh]'s stated contract and, until the
     * re-review (#R6), a claim nothing upheld. `client.config()` was always safe — `safeApiCall`
     * turns every failure into an [ApiResult.Failure] — but [writeThrough] touches Room four
     * times, and review #7's fix added a fifth. Both callers are bare: the boot sync is a
     * `viewModelScope.launch { }` with no catch, so a pump with a full database could not open
     * the app at all, and the processor's call sits inside a `flow { }` whose collector is
     * another `viewModelScope` — the #R5 crash, one layer up, on the path every sale takes.
     *
     * A failure to *store* is not a failure to *fetch*. The response is returned either way, so a
     * sale is still quoted and authorised at the server's price; what is lost is the cached copy
     * the screen reads, which is where the app stood before 10c-bis.
     */
    suspend fun fetch(): ApiResult<SyncedConfig> =
        when (val result = client.config()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(writeThrough(result.data))
        }

    /**
     * Issue the `/config` the **next authorise** will need, now, off the tap path.
     *
     * Called at nozzle shutoff, while the customer is still reading the total. The tap on "pay
     * digitally" used to wait for two trans-Atlantic round trips in sequence — this fetch, then
     * `/authorise` — measured at 1.3 s and 3.2 s on the SM-T220 on 2026-09-21, with the fetch alone
     * taking 1.9 s cold. Starting it at shutoff leaves the tap waiting on `/authorise` only.
     *
     * **OQ #8 still holds**: the price is fetched fresh for this sale; it is simply fetched a few
     * seconds earlier. [fetchForAuthorise] refuses anything older than [PREFETCH_MAX_AGE_MS] and
     * uses a result at most once, so a prefetch never outlives the moment it was made for.
     *
     * Suspends for the whole fetch; the caller runs it on its own coroutine. If that coroutine is
     * cancelled the slot is completed empty rather than left pending, so an authorise waiting on it
     * falls back to a fetch of its own instead of hanging.
     */
    suspend fun prefetchForNextAuthorise() {
        val slot = Prefetch(startedAtMs = clock.millis(), result = CompletableDeferred())
        prefetch = slot
        try {
            slot.result.complete(fetch())
        } finally {
            // Empty, not cancelled: awaiting a cancelled Deferred throws CancellationException into
            // the authorise, which would end the payment flow as though the customer had gone.
            slot.result.complete(null)
        }
    }

    /**
     * The `/config` an authorise quotes against: the prefetched one if it was issued recently and
     * succeeded — awaiting it if it is still in flight — otherwise a fetch of its own.
     *
     * Consumed once. A prefetch made for one sale must not price a later one, and the
     * `previousKoboPerLitre` it carries is only "the price moved during *this* sale" for the sale it
     * was made for.
     */
    suspend fun fetchForAuthorise(): ApiResult<SyncedConfig> {
        val slot = prefetch
        prefetch = null
        if (slot != null && clock.millis() - slot.startedAtMs <= PREFETCH_MAX_AGE_MS) {
            val result = slot.result.await()
            // A failed prefetch is not an answer for the authorise — the network may be back.
            if (result is ApiResult.Success) return result
        }
        return fetch()
    }

    private class Prefetch(
        val startedAtMs: Long,
        val result: CompletableDeferred<ApiResult<SyncedConfig>?>,
    )

    /** See [prefetchForNextAuthorise]. Null when none is waiting to be used. */
    private var prefetch: Prefetch? = null

    private suspend fun writeThrough(config: PumpConfigResponse): SyncedConfig {
        // **A read that failed is not a device with no config**, and the difference decides
        // whether anything may be written at all. `saveConfig` replaces the row, so treating an
        // unreadable database as "nothing stored" would build a fresh [DeviceConfig] and wipe the
        // operator's own fields on a transient Room error. Same rule as the adapter's pulse count
        // on resume: unknown is not zero.
        val existing = runCatchingCancellable { deviceConfig.getConfig() }
            .onFailure {
                android.util.Log.e(TAG, "Could not read the stored config; nothing was written", it)
            }
            .getOrElse { return SyncedConfig(config = config, previousKoboPerLitre = null) }

        val synced = SyncedConfig(config = config, previousKoboPerLitre = existing?.koboPerLitre)

        // The name the backend holds wins, falling back to whatever this device had. See the
        // header for why the receipt's name is the backend's to own.
        val stationName = synced.stationName ?: existing?.stationName

        // **The price is the one field the backend is allowed not to have** (review #7). A 0 is
        // the absence of a price, not a cheap one, and writing it through would take the pump's
        // last known good figure with it — stopping *cash* sales, which need no backend at all,
        // over a field only digital sales consult. The rest of the response is still true and is
        // still stored; only the figure is discarded.
        val price = if (synced.hasUsablePrice) synced.koboPerLitre else existing?.koboPerLitre ?: 0L
        recordRejectedPriceIfNew(synced, keptInstead = existing?.koboPerLitre)

        // Nothing moved — skip the write rather than bump `updatedAt` on every authorise, which
        // would make the timestamp mean "last contacted" instead of "last changed".
        if (existing != null &&
            existing.koboPerLitre == price &&
            existing.fuelType == config.fuelType &&
            existing.stationName == stationName
        ) {
            return synced
        }

        val stored = runCatchingCancellable {
            deviceConfig.saveConfig(
                existing?.copy(
                    koboPerLitre = price,
                    fuelType = config.fuelType,
                    stationName = stationName ?: existing.stationName,
                    updatedAt = System.currentTimeMillis(),
                ) ?: DeviceConfig(
                    // An activated pump that has never been configured by hand becomes sellable
                    // here, which is the point: a price change is meant to stop being a visit to
                    // every pump. Unless the backend had no price either, in which case this
                    // stores the 0 it already had by omission and `CanStartTransactionUseCase`
                    // keeps the pump shut.
                    koboPerLitre = price,
                    fuelType = config.fuelType,
                ).let { fresh ->
                    // Only when the backend actually has a name — otherwise DeviceConfig's own
                    // default stands, and a blank is never written.
                    stationName?.let { fresh.copy(stationName = it) } ?: fresh
                },
            )
        }.onFailure {
            android.util.Log.e(TAG, "Could not store the config fetched from the backend", it)
        }.isSuccess

        // **Only when the row actually moved.** A "price updated" line beside a price that was
        // not stored is a lie in the one log an operator reads, and it would send someone looking
        // for a change the pump never made.
        if (stored && synced.priceChanged) {
            runCatchingCancellable {
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
            }.onFailure {
                android.util.Log.e(TAG, "The price moved but the row saying so was lost", it)
            }
        }
        return synced
    }

    /**
     * Log a refused price once, not once per sale.
     *
     * `/config` is fetched immediately before every authorise, so a backend that has no price for
     * this pump would otherwise write a row per attempted sale and bury the events that need a
     * person. [lastRejectedPrice] is deliberately in memory only: a restart logging one more line
     * is the right amount of noise, and persisting it would mean a schema change to record
     * something whose whole value is that it is recent.
     */
    private suspend fun recordRejectedPriceIfNew(synced: SyncedConfig, keptInstead: Long?) {
        if (synced.hasUsablePrice) {
            lastRejectedPrice = null
            return
        }
        if (lastRejectedPrice == synced.koboPerLitre) return

        val recorded = runCatchingCancellable {
            events.record(
                type = EventType.PRICE_SYNC_REJECTED,
                detail = "Balanceè reported no price for this pump (${synced.koboPerLitre} kobo/L), " +
                    "so it was ignored. " +
                    if (keptInstead != null && keptInstead > 0) {
                        "The pump is still selling at ${formatNaira(keptInstead)} per litre; " +
                            "card sales are refused until the station's price is set."
                    } else {
                        "This pump has no price at all and cannot sell until one is set."
                    },
            )
        }.onFailure {
            android.util.Log.e(TAG, "Could not record the refused price ${synced.koboPerLitre}", it)
        }.isSuccess

        // **Marked only once it has actually been written.** The dedupe exists to stop a row per
        // attempted sale, not to stop the row ever appearing: a write that failed logged nothing,
        // so the next sync is entitled to try again. Otherwise one full-disk moment silences the
        // only warning an operator gets that their pump has no price.
        if (recorded) lastRejectedPrice = synced.koboPerLitre
    }

    /** See [recordRejectedPriceIfNew]. Null means the last sync carried a usable price. */
    private var lastRejectedPrice: Long? = null

    internal companion object {
        const val TAG = "PumpConfigSync"

        /**
         * How old a prefetched `/config` may be and still price a sale. Long enough to cover a
         * customer reading the total and choosing; short enough that a price changed on the
         * backend meanwhile is at worst a refused sale (`AMOUNT_MISMATCH`, which is recoverable and
         * re-fetches), never a wrong charge — the server checks the amount against its own figure.
         */
        const val PREFETCH_MAX_AGE_MS = 60_000L
    }
}

/**
 * Kobo as a naira string. A local copy rather than the UI's formatter — data must not reach up into
 * `ui`, and this is the only shape needed here.
 */
internal fun formatNaira(kobo: Long): String = "₦%,.2f".format(kobo / 100.0)

private const val KOBO_PER_NAIRA = 100L
