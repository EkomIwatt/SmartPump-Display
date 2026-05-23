// Customer-facing fueling mode chosen on the unified ModeSelect screen.
// Distinct from [TransactionFlow] — modes are the two coarse choices a customer makes;
// flows are the five concrete state machines the chosen mode + payment method resolve to.
//   PRE_PAY  → FIXED_PREPAY_DIGITAL or USSD_OFFLINE (depends on payment method)
//   FILL_UP  → FILLUP_CASH or FILLUP_DIGITAL (depends on post-shutoff customer choice)
package app.balancee.smartpump.display.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class TransactionMode {
    PRE_PAY,
    FILL_UP,
}
