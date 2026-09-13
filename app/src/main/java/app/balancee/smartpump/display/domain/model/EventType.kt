// Kinds of operational event recorded in the events log (EventEntity.type).
// Stored by NAME, so renaming a constant is a data migration — add new ones instead.
package app.balancee.smartpump.display.domain.model

enum class EventType {
    /**
     * Fuel counted by the adapter while the app was not watching, and confidently attributed to
     * the transaction that was in flight. Recorded even though the customer was billed correctly,
     * because the litre count on that sale jumps on resume and nobody can otherwise explain the
     * difference between the app and the dispenser's own totaliser.
     */
    PULSE_GAP_RECOVERED,

    /**
     * Fuel counted by the adapter while the app was not watching that could NOT be attributed:
     * the adapter had itself restarted, no anchor was stored, the adapter was silent, the gap was
     * too large to be plausible, or there was no transaction in flight. The litres are real and
     * the station has lost them. Needs a human.
     */
    PULSE_GAP_UNEXPLAINED,
}
