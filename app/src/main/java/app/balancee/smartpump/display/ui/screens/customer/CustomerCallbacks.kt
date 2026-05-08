// Single bundle of every customer-driven UI intent. The ViewModel (Phase 5) hooks each
// lambda to the corresponding state-machine action; previews can pass empty defaults.
package app.balancee.smartpump.display.ui.screens.customer

import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionMode

data class CustomerCallbacks(
    val onStart: () -> Unit = {},
    val onSelectMode: (TransactionMode) -> Unit = {},
    val onConfirmAmount: (Long) -> Unit = {},
    val onSelectMethod: (PaymentMethod) -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onDismiss: () -> Unit = {},
    val onComplete: () -> Unit = {},
    val onBack: () -> Unit = {},
)
