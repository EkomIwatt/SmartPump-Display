// Payment methods shown to the customer on the payment selection screen.
package app.balancee.smartpump.display.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class PaymentMethod {
    /** Deep-link into the Balanceè mobile app. */
    BALANCEE_APP,

    /** NIP bank-transfer QR code — scanned in any Nigerian bank app. */
    BANK_QR,

    /** NFC / contactless card tap on the payment panel. */
    NFC,

    /** USSD code dialled on customer's phone; confirmed via incoming SMS on pump SIM. */
    USSD,

    /** Cash collected by attendant — attendant-driven flow only. */
    CASH,
}
