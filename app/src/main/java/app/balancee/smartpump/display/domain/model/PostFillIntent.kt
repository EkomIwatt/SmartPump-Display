// The customer's pre-declared payment intent for *after* a fill-up completes,
// captured on the FillupAwaitingAttendantAuth screen before the attendant authorises.
// Advisory — the customer can still change their mind at FillupTankFull. Capturing it
// here gives the strict-design FILL UP confirm screen an actual call-to-action.
package app.balancee.smartpump.display.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class PostFillIntent {
    /** Customer plans to scan a dynamic QR for the verified amount → Flow 3 (fill-up digital). */
    BANK_QR,

    /** Customer plans to hand cash to the attendant → Flow 2 (fill-up cash). */
    CASH_TO_ATTENDANT,
}
