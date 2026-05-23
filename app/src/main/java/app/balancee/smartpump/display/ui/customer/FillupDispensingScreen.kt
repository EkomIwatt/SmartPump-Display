// Flow 2, step 1 — open-ended fill. Relay is energised, pulses are flowing, no litre target.
// The big cyan litre count updates live; the nozzle shuts when the tank is full and the VM's
// 3-second pulse-timeout watchdog catches it. No customer-side stop button — only the
// attendant overlay (Phase 4) can abort.
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
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.LedgerRow
import app.balancee.smartpump.display.ui.components.LitresDisplay
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary

@Composable
fun FillupDispensingScreen(
    txnId: String,
    pricePerLitre: Int,
    litresSoFar: Double,
    modifier: Modifier = Modifier,
    pumpId: String = "Pump 1",
) {
    val runningCostNaira = (litresSoFar * pricePerLitre).toInt()

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
            stateLabel = "Filling",
            stateColor = ActiveCyan,
        )

        BalanceeCard(
            borderColor = ActiveCyan,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LabelText(text = "Litres dispensed", color = ActiveCyan)
                Box(modifier = Modifier.fillMaxWidth()) {
                    LitresDisplay(
                        litres = litresSoFar,
                        color = ActiveCyan,
                    )
                }
                Text(
                    text = "filling… nozzle shuts automatically.",
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
                        LedgerRow(label = "Price / L", value = "₦$pricePerLitre")
                        LedgerRow(label = "Txn", value = txnId)
                        LedgerRow(label = "Mode", value = "Open-ended", valueMonospace = false)
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        LabelText(text = "Running total")
                        AmountDisplay(
                            amountNaira = runningCostNaira,
                            color = ActiveCyan,
                            style = MaterialTheme.typography.displaySmall,
                        )
                    }
                }
            }
        }

        Text(
            text = "Pay after the nozzle shuts — cash or QR. Do not remove the nozzle until done.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun FillupDispensingScreenPreview() {
    SmartPumpDisplayTheme {
        FillupDispensingScreen(
            txnId = "BLC-00921",
            pricePerLitre = 870,
            litresSoFar = 18.42,
        )
    }
}
