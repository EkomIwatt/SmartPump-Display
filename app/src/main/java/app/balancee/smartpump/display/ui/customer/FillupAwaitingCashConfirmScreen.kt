// Flow 2, step 3 — customer chose cash. Waiting for the attendant to tap CASH RECEIVED.
// Same gold AMOUNT DUE card as TankFull but the only action is the attendant's. The
// "Cash received (attendant)" primary button is a temp affordance until the Phase 4
// swipe-up overlay ships.
package app.balancee.smartpump.display.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.components.AmountDisplay
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.LedgerRow
import app.balancee.smartpump.display.ui.components.LitresDisplay
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.PrimaryAmber
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary

@Composable
fun FillupAwaitingCashConfirmScreen(
    txnId: String,
    verifiedLitres: Double,
    amountDueNaira: Int,
    pricePerLitre: Int,
    onAttendantCashReceived: () -> Unit,
    onCancel: () -> Unit,
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
            mode = "Fill up · cash",
            stateLabel = "Awaiting cash",
            stateColor = PrimaryAmber,
        )

        BalanceeCard(
            borderColor = PrimaryAmber,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LabelText(text = "Hand cash to attendant", color = PrimaryAmber)
                Box(modifier = Modifier.fillMaxWidth()) {
                    AmountDisplay(
                        amountNaira = amountDueNaira,
                        color = PrimaryAmber,
                    )
                }
                Text(
                    text = "Attendant will tap CASH RECEIVED on the overlay to close the " +
                        "transaction. The record links the verified litres to the cash collected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )

                Box(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LedgerRow(label = "Litres", value = "%.2f L".format(verifiedLitres))
                        LedgerRow(label = "Price / L", value = "₦$pricePerLitre")
                        LedgerRow(label = "Txn", value = txnId)
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        LabelText(text = "Verified")
                        LitresDisplay(
                            litres = verifiedLitres,
                            color = PrimaryAmber,
                            style = MaterialTheme.typography.displaySmall,
                        )
                    }
                }
            }
        }

        Text(
            text = "Phase 4 ships the attendant overlay — the button below stands in for " +
                "the swipe-up CASH RECEIVED action.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        BalanceeButton(
            label = "Cash received (attendant)",
            onClick = onAttendantCashReceived,
        )
        BalanceeButton(
            label = "Cancel",
            onClick = onCancel,
            variant = BalanceeButtonVariant.Secondary,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0F, widthDp = 1024, heightDp = 600)
@Composable
private fun FillupAwaitingCashConfirmPreview() {
    SmartPumpDisplayTheme {
        FillupAwaitingCashConfirmScreen(
            txnId = "BLC-00921",
            verifiedLitres = 38.10,
            amountDueNaira = 33_147,
            pricePerLitre = 870,
            onAttendantCashReceived = {},
            onCancel = {},
        )
    }
}
