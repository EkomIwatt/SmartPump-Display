// Terminal screen — green-bordered "✓ Done." card with the receipt ledger.
// Shared by every flow that ends in [TransactionState.Complete]; flow-specific copy
// is derived from [Complete.flow] (e.g. "Cash · fixed" vs "Pre-pay digital"). The
// Share-receipt action is a placeholder for Phase 6 wiring (see OPEN_QUESTIONS #14).
package app.balancee.smartpump.display.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.HeroSerifText
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.LedgerRow
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.PrimaryAmber
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.SuccessGreen
import app.balancee.smartpump.display.ui.theme.TextSecondary

@Composable
fun CompleteScreen(
    flow: TransactionFlow,
    txnId: String,
    litres: Double,
    amountNaira: Int,
    method: PaymentMethod?,
    pricePerLitre: Int,
    onShareReceipt: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    pumpId: String = "Pump 1",
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PumpHeader(
            pumpId = pumpId,
            mode = flowMode(flow),
            stateLabel = "Complete",
            stateColor = SuccessGreen,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            BalanceeCard(
                borderColor = SuccessGreen,
                modifier = Modifier.sizeIn(maxWidth = 520.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "✓",
                        style = MaterialTheme.typography.displayLarge.copy(color = SuccessGreen),
                    )
                    HeroSerifText(text = "Done.", color = PrimaryAmber)
                    LabelText(text = "Transaction complete")

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LedgerRow(label = "Litres", value = "%.2f L".format(litres))
                        LedgerRow(label = "Paid", value = "₦${formatNaira(amountNaira)}")
                        LedgerRow(label = "Price / L", value = "₦$pricePerLitre")
                        if (method != null) {
                            LedgerRow(
                                label = "Method",
                                value = methodLabel(method),
                                valueMonospace = false,
                            )
                        }
                        LedgerRow(label = "Txn", value = txnId)
                    }

                    Text(
                        text = "Receipt sent to WhatsApp. Tap to share again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )

                    BalanceeButton(
                        label = "Share receipt",
                        onClick = onShareReceipt,
                    )
                    BalanceeButton(
                        label = "Done",
                        onClick = onDismiss,
                        variant = BalanceeButtonVariant.Secondary,
                    )
                }
            }
        }
    }
}

private fun flowMode(flow: TransactionFlow): String = when (flow) {
    TransactionFlow.FIXED_PREPAY_DIGITAL -> "Pre-pay"
    TransactionFlow.FILLUP_CASH -> "Fill-up · cash"
    TransactionFlow.FILLUP_DIGITAL -> "Fill-up · digital"
    TransactionFlow.CASH_FIXED -> "Cash · fixed"
    TransactionFlow.USSD_OFFLINE -> "USSD"
}

private fun methodLabel(method: PaymentMethod): String = when (method) {
    PaymentMethod.BALANCEE_APP -> "Balanceè app"
    PaymentMethod.BANK_QR_TRANSFER -> "Bank QR · NIP"
    PaymentMethod.NFC_CARD -> "Tap card · NFC"
    PaymentMethod.USSD -> "USSD *737#"
    PaymentMethod.CASH_SEE_ATTENDANT -> "Cash"
}

private fun formatNaira(value: Int): String =
    java.text.NumberFormat.getNumberInstance(java.util.Locale.UK).format(value)

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0F, widthDp = 1024, heightDp = 600)
@Composable
private fun CompleteScreenPreview() {
    SmartPumpDisplayTheme {
        CompleteScreen(
            flow = TransactionFlow.FIXED_PREPAY_DIGITAL,
            txnId = "BLC-00847",
            litres = 5.75,
            amountNaira = 5_000,
            method = PaymentMethod.BANK_QR_TRANSFER,
            pricePerLitre = 870,
            onShareReceipt = {},
            onDismiss = {},
        )
    }
}
