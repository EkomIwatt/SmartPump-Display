// Gateway for the append-only operational event log (Phase 7h).
//
// Separate from TransactionRepository on purpose: a sale is a completed, balanced record, whereas
// an event is usually something that went wrong and is still open until a person looks at it. The
// cases this exists for — fuel counted while the app was blind, with no sale to attribute it to —
// have no transaction row to hang off at all.
package app.balancee.smartpump.display.domain.repository

import app.balancee.smartpump.display.domain.model.EventType
import app.balancee.smartpump.display.domain.model.OperationalEvent
import kotlinx.coroutines.flow.Flow

interface EventRepository {

    /**
     * Append one event. [pulses] is the raw adapter count the event concerns, null where the size
     * was never knowable; the K-factor in force is recorded alongside it by the implementation so
     * the entry stays interpretable after calibration changes that constant.
     */
    suspend fun record(
        type: EventType,
        pulses: Int? = null,
        transactionRef: String? = null,
        detail: String? = null,
    )

    /** Most recent events first, for the operator view. */
    fun observeRecent(limit: Int = 50): Flow<List<OperationalEvent>>
}
