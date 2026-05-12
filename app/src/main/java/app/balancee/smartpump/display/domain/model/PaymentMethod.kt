// Customer-facing payment methods on the PRE-PAY selection screen.
// CASH_SEE_ATTENDANT routes to the attendant overlay rather than a customer payment flow.
package app.balancee.smartpump.display.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class PaymentMethod {
    /** Deep-link into the Balanceè mobile app. */
    BALANCEE_APP,

    /** NIP bank-transfer QR — scanned in any Nigerian bank app. */
    BANK_QR_TRANSFER,

    /** NFC / contactless card tap on the payment panel. */
    NFC_CARD,

    /** USSD code dialled on customer's phone; confirmed via incoming SMS on pump SIM. */
    USSD,

    /** Cash — handled by the attendant via the swipe-up overlay. */
    CASH_SEE_ATTENDANT,
}
