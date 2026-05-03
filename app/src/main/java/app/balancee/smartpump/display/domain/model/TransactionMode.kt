// Whether this transaction is a fixed pre-pay or an open-ended fill-up.
package app.balancee.smartpump.display.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class TransactionMode {
    /** Customer pays fixed Naira amount before fuel flows. */
    PRE_PAY,

    /** Attendant authorises open-ended fill; relay opens until nozzle shutoff detected. */
    FILL_UP,
}
