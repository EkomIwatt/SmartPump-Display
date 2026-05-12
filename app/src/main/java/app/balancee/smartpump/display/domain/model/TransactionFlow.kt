// One of the five transaction flows defined in docs/flows.md.
// The runtime state machine + the persisted Transaction record both carry this discriminator.
package app.balancee.smartpump.display.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class TransactionFlow {
    /** Flow 1 — customer picks fixed amount, pays digitally, fuel flows up to authorised litres. */
    FIXED_PREPAY_DIGITAL,

    /** Flow 2 — attendant authorises open-ended fill, customer pays cash after nozzle shutoff. */
    FILLUP_CASH,

    /** Flow 3 — same fill-up start as Flow 2, customer pays via dynamic NIP QR after shutoff. */
    FILLUP_DIGITAL,

    /** Flow 4 — attendant enters cash ₦, system computes litre cutoff and dispenses. */
    CASH_FIXED,

    /** Flow 5 — customer dials USSD, bank SMS to pump SIM unlocks the relay. */
    USSD_OFFLINE,
}
