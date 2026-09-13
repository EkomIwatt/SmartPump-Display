// Room-backed implementation of EventRepository. Append-only — rows are never updated or deleted.
package app.balancee.smartpump.display.data.repository

import app.balancee.smartpump.display.data.db.EventDao
import app.balancee.smartpump.display.data.db.entities.EventEntity
import app.balancee.smartpump.display.domain.hardware.PULSES_PER_LITRE
import app.balancee.smartpump.display.domain.model.EventType
import app.balancee.smartpump.display.domain.model.OperationalEvent
import app.balancee.smartpump.display.domain.repository.EventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventRepositoryImpl @Inject constructor(
    private val dao: EventDao,
) : EventRepository {

    override suspend fun record(
        type: EventType,
        pulses: Int?,
        transactionRef: String?,
        detail: String?,
    ) {
        dao.insert(
            EventEntity(
                type = type.name,
                createdAtMs = System.currentTimeMillis(),
                transactionRef = transactionRef,
                pulses = pulses,
                // Stamped at write time, not read time. The constant is an unmeasured placeholder
                // (OPEN_QUESTIONS #1) and will change at calibration; without this the litre figure
                // an old entry reports would move with it.
                pulsesPerLitre = PULSES_PER_LITRE,
                detail = detail,
                syncedAt = null,
            )
        )
    }

    override fun observeRecent(limit: Int): Flow<List<OperationalEvent>> =
        dao.getRecent(limit).map { rows -> rows.map { it.toDomain() } }

    private fun EventEntity.toDomain() = OperationalEvent(
        id = id,
        // An unknown type name must not take down the operator's whole log, so an unrecognised
        // row degrades to the unexplained kind rather than throwing. Types are only ever added.
        type = runCatching { EventType.valueOf(type) }.getOrDefault(EventType.PULSE_GAP_UNEXPLAINED),
        createdAtMs = createdAtMs,
        transactionRef = transactionRef,
        pulses = pulses,
        pulsesPerLitre = pulsesPerLitre,
        detail = detail,
    )
}
