// Append-only operational event log — things that happened to the pump that are not sales.
//
// Phase 7h adds it for pulse-gap reconciliation: fuel the adapter counted while the app was not
// watching, which the app could NOT confidently attribute to a transaction. Those litres are
// physically gone and belong in a record a human can act on, rather than being absorbed into a
// new baseline (the pre-7h behaviour, OPEN_QUESTIONS #25).
//
// Deliberately typed rather than a pulse-gap-specific table: the Prototype Specification already
// requires a second kind of entry nothing writes yet (`PWR-03`, "power event logged"), and a
// dedicated table would mean a second migration when that arrives.
//
// Money/units note: the raw fact is the PULSE COUNT, which is what the adapter actually measured.
// Litres are derived through a K-factor that is still an unmeasured placeholder (OPEN_QUESTIONS
// #1), so the K in force at logging time is stored ALONGSIDE the count rather than a pre-computed
// litre figure. A historic entry then stays exactly reproducible after calibration changes K,
// and it is still possible to see what the operator was told at the time.
package app.balancee.smartpump.display.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [app.balancee.smartpump.display.domain.model.EventType] name. */
    val type: String,
    val createdAtMs: Long,
    /** Transaction this relates to, when there was one ("BLC-00847"); null when there was not. */
    val transactionRef: String?,
    /** Raw adapter pulses the event concerns, or null for events that are not about fuel. */
    val pulses: Int?,
    /** K-factor in force when the row was written, so [pulses] stays interpretable. */
    val pulsesPerLitre: Double?,
    /** Human-readable detail for the attendant / operator view. */
    val detail: String?,
    /** Epoch millis when synced to the backend; null if pending. Mirrors TransactionEntity. */
    val syncedAt: Long?,
)
