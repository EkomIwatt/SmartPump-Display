// Flow 2, step 2 — pulse-timeout watchdog fired; nozzle is treated as shut.
// Verified litres are locked. The customer now picks how to pay:
//   - Cash → FillupAwaitingCashConfirm (attendant taps CASH RECEIVED next)
//   - Digital → branches to Flow 3 (lands in Phase 3e; button disabled until then)
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
fun FillupTankFullScreen(
    txnId: String,
    pricePerLitre: Int,
    verifiedLitres: Double,
    amountDueNaira: Int,
    onPayCash: () -> Unit,
    onPayDigital: () -> Unit,
    modifier: Modifier = Modifier,
    pumpId: String = "Pump 1",
    digitalEnabled: Boolean = false,
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
            mode = "Fill up",
            stateLabel = "Tank full",
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
                LabelText(text = "Amount due", color = PrimaryAmber)
                Box(modifier = Modifier.fillMaxWidth()) {
                    AmountDisplay(
                        amountNaira = amountDueNaira,
                        color = PrimaryAmber,
                    )
                }
                Text(
                    text = "Final verified count — cannot be changed.",
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
            text = "Pay now",
            style = MaterialTheme.typography.labelLarge.copy(color = TextPrimary),
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
        ) {
            BalanceeButton(
                label = "Pay cash",
                onClick = onPayCash,
                modifier = Modifier.weight(1f),
            )
            BalanceeButton(
                label = "Pay digitally · scan QR",
                onClick = onPayDigital,
                enabled = digitalEnabled,
                variant = BalanceeButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0F, widthDp = 1024, heightDp = 600)
@Composable
private fun FillupTankFullPreview() {
    SmartPumpDisplayTheme {
        FillupTankFullScreen(
            txnId = "BLC-00921",
            pricePerLitre = 870,
            verifiedLitres = 38.10,
            amountDueNaira = 33_147,
            onPayCash = {},
            onPayDigital = {},
        )
    }
}
