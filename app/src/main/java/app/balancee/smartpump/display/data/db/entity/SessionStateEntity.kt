// Single-row table (id = 1) storing the current TransactionState as JSON.
// Written on every state transition so a power cut never loses state.
package app.balancee.smartpump.display.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_state")
data class SessionStateEntity(
    @PrimaryKey val id: Int = 1,
    /** kotlinx-serialization JSON encoding of TransactionState. */
    val stateJson: String,
    val updatedAt: Long,
)
