// Terminal screen — green-bordered "✓ Done." card with the receipt ledger.
// Shared by every flow that ends in [TransactionState.Complete]; flow-specific copy
// is derived from [Complete.flow] (e.g. "Cash · fixed" vs "Pre-pay digital"). The
// Share-receipt action is a placeholder for Phase 6 wiring (see OPEN_QUESTIONS #14).
package app.balancee.smartpump.display.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.SuccessGreen
import app.balancee.smartpump.display.ui.theme.TextSecondary

private const val AUTO_RETURN_SECONDS = 60

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
    // Flow 1 (digital pre-pay) finishes on a gold receipt card; cash, fill-up, and USSD
    // completions land on a green "dispense succeeded" card. Source: strict-design screens.
    val accent = when (flow) {
        TransactionFlow.FIXED_PREPAY_DIGITAL -> PrimaryGold
        else -> SuccessGreen
    }

    // Screen-local auto-return countdown — Complete is terminal on boot (bootResume resets
    // to Idle), so no persistence needed for the timer. Any tap on Share Receipt resets it
    // so a customer who wants to share multiple times never runs out of time.
    var secondsRemaining by remember(txnId) { mutableIntStateOf(AUTO_RETURN_SECONDS) }
    LaunchedEffect(txnId, secondsRemaining) {
        if (secondsRemaining > 0) {
            delay(1_000L)
            secondsRemaining -= 1
        } else {
            onDismiss()
        }
    }

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
            stateColor = accent,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.TopCenter,
        ) {
            BalanceeCard(
                borderColor = accent,
                modifier = Modifier.sizeIn(maxWidth = 520.dp),
            ) {
                // The receipt's content can run taller than a phone-landscape viewport
                // (5-line ledger for digital flows + buttons + countdown), so the inner
                // column scrolls if it has to. Tighter spacing keeps it fitting on tablets
                // without needing to scroll.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "✓",
                        style = MaterialTheme.typography.displayLarge.copy(color = accent),
                    )
                    HeroSerifText(text = "Done.", color = accent)
                    LabelText(text = "Transaction complete")

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
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

                    // Two paired actions side-by-side. Share-receipt only shares — never
                    // dismisses — and tapping it resets the auto-return countdown so the
                    // customer can share multiple times without timing out. Return to Idle
                    // is the explicit dismissal; the countdown text below also auto-returns.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BalanceeButton(
                            label = "Share receipt",
                            onClick = {
                                secondsRemaining = AUTO_RETURN_SECONDS
                                onShareReceipt()
                            },
                            accentColor = accent,
                            modifier = Modifier.weight(1f),
                        )
                        BalanceeButton(
                            label = "Return to Idle",
                            onClick = onDismiss,
                            variant = BalanceeButtonVariant.Secondary,
                            accentColor = accent,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = "Returns to idle in ${secondsRemaining}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
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

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
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
