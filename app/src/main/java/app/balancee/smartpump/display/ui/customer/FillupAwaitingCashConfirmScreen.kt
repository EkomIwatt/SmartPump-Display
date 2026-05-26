// Flow 2, step 3 — customer chose cash. Waiting for the attendant to tap CASH RECEIVED on the
// swipe-up attendant overlay. Purely informational on the customer side; the close-out action
// lives on the overlay and is gated by this state.
//
// Shares the FixedDispensingScreen layout language (in-card chip + word, centered hero, tinted
// ledger panel). Post-dispensing "amount due" screen, so the hero is the gold AMOUNT DUE with a
// verified-litres sub-line.
package app.balancee.smartpump.display.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.components.AmountDisplay
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.LedgerRow
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.components.StateChip
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextSecondary

@Composable
fun FillupAwaitingCashConfirmScreen(
    txnId: String,
    verifiedLitres: Double,
    amountDueNaira: Int,
    pricePerLitre: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    pumpId: String = "Pump 1",
) {
    val accent = PrimaryGold

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PumpHeader(
            pumpId = pumpId,
            mode = "Fill up · cash",
            stateLabel = "Awaiting cash",
            stateColor = accent,
        )

        BalanceeCard(
            borderColor = accent,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // In-card header: state chip (left) + activity word (right).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StateChip(label = "Awaiting cash", color = accent)
                    LabelText(text = "Hand cash")
                }

                // Centred hero — amount to hand over (gold) + verified-litres sub-line.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LabelText(text = "Amount due")
                    AmountDisplay(
                        amountNaira = amountDueNaira,
                        color = accent,
                        style = MaterialTheme.typography.displayMedium,
                    )
                    Text(
                        text = "%.2f L · verified — hand this to the attendant".format(verifiedLitres),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }

                // Ledger in a subtly state-tinted rounded panel.
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
                    LedgerRow(label = "Litres", value = "%.2f L".format(verifiedLitres))
                    LedgerRow(label = "Price / L", value = "₦$pricePerLitre")
                    LedgerRow(label = "Txn", value = txnId)
                }
            }
        }

        Text(
            text = "Attendant taps CASH RECEIVED on the overlay to close the transaction. " +
                "The record links the verified litres to the cash collected.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        BalanceeButton(
            label = "Cancel",
            onClick = onCancel,
            variant = BalanceeButtonVariant.Secondary,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun FillupAwaitingCashConfirmPreview() {
    SmartPumpDisplayTheme {
        FillupAwaitingCashConfirmScreen(
            txnId = "BLC-00921",
            verifiedLitres = 38.10,
            amountDueNaira = 33_147,
            pricePerLitre = 870,
            onCancel = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 600, heightDp = 1024)
@Composable
private fun FillupAwaitingCashConfirmPortraitPreview() {
    SmartPumpDisplayTheme {
        FillupAwaitingCashConfirmScreen(
            txnId = "BLC-00921",
            verifiedLitres = 38.10,
            amountDueNaira = 33_147,
            pricePerLitre = 870,
            onCancel = {},
        )
    }
}
