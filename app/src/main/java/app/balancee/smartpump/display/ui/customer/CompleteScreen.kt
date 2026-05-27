// Terminal screen — green-bordered "✓ Done." card with the receipt ledger.
// Shared by every flow that ends in [TransactionState.Complete]; flow-specific copy
// is derived from [Complete.flow] (e.g. "Cash · fixed" vs "Pre-pay digital"). The
// Share-receipt action is a placeholder for Phase 7 wiring (see OPEN_QUESTIONS #14).
package app.balancee.smartpump.display.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
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
import app.balancee.smartpump.display.ui.util.formatNaira

private const val AUTO_RETURN_SECONDS = 60

@Composable
fun CompleteScreen(
    flow: TransactionFlow,
    txnId: String,
    litres: Double,
    amountKobo: Long,
    method: PaymentMethod?,
    priceKoboPerLitre: Long,
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

                    // Receipt ledger in the same state-tinted rounded panel the dispensing
                    // screens use (accent @7% fill, @30% border). Gold for the Flow 1 pre-pay
                    // receipt, green for cash / fill-up / USSD — matches the card border.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Dimensions.cornerCodePanel))
                            .background(accent.copy(alpha = 0.07f))
                            .border(
                                Dimensions.borderWidth,
                                accent.copy(alpha = 0.30f),
                                RoundedCornerShape(Dimensions.cornerCodePanel),
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LedgerRow(label = "Litres", value = "%.2f L".format(litres))
                        LedgerRow(label = "Paid", value = formatNaira(amountKobo))
                        LedgerRow(label = "Price / L", value = formatNaira(priceKoboPerLitre))
                        if (method != null) {
                            LedgerRow(
                                label = "Method",
                                value = methodLabel(method),
                                valueMonospace = false,
                            )
                        }
                        LedgerRow(label = "Txn", value = txnId)
                    }

                    // Two paired actions side-by-side. Share receipt only shares (never
                    // dismisses) and resets the auto-return countdown so the customer can
                    // share repeatedly without timing out; Return to idle frees the pump
                    // early. The caption below carries the WhatsApp note + the passive
                    // auto-return fallback.
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
                            label = "Return to idle",
                            onClick = onDismiss,
                            variant = BalanceeButtonVariant.Secondary,
                            accentColor = accent,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = "Also sent to WhatsApp · returns to idle in ${secondsRemaining}s",
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

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun CompleteScreenPreview() {
    SmartPumpDisplayTheme {
        CompleteScreen(
            flow = TransactionFlow.FIXED_PREPAY_DIGITAL,
            txnId = "BLC-00847",
            litres = 5.75,
            amountKobo = 500_000,
            method = PaymentMethod.BANK_QR_TRANSFER,
            priceKoboPerLitre = 87_000,
            onShareReceipt = {},
            onDismiss = {},
        )
    }
}
