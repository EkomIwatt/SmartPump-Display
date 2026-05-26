// Flow 2 / Flow 3, step 1 — open-ended fill. Relay is energised, pulses are flowing, no litre
// target. The big cyan litre count updates live; the nozzle shuts when the tank is full and the
// VM's 3-second pulse-timeout watchdog catches it (or the attendant ends it from the overlay).
// No customer-side stop button.
//
// Shares the FixedDispensingScreen layout language: in-card chip + activity word, a centered
// "LITRES DISPENSED" label over the big mono figure, and a state-tinted ledger panel. Adapted
// for open-ended fill-up — the figure is cyan (design-system: fill mode), there is no progress
// bar (no preset target), and the "used · authorised" split line becomes "running total · no
// preset limit".
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
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.LedgerRow
import app.balancee.smartpump.display.ui.components.LitresDisplay
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.components.StateChip
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextSecondary
import app.balancee.smartpump.display.ui.theme.displayMonoFamily

@Composable
fun FillupDispensingScreen(
    txnId: String,
    pricePerLitre: Int,
    litresSoFar: Double,
    modifier: Modifier = Modifier,
    pumpId: String = "Pump 1",
    stationName: String = "Station",
) {
    // Fill-up dispensing is cyan everywhere (design-system: "fill mode" hero numbers + border).
    val accent = ActiveCyan
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
                    StateChip(label = "Filling", color = accent)
                    LabelText(text = "Open-ended")
                }

                // Centred hero — label, big mono litres figure, no-target sub-line.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LabelText(text = "Litres dispensed")
                    LitresDisplay(
                        litres = litresSoFar,
                        color = accent,
                    )
                    Text(
                        text = "filling — pump shuts when the tank is full",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }

                // ₦ running total (left) · no cap (right) — muted mono, mirrors the fixed card's
                // "used · authorised" line. Open-ended fill has no preset limit.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "₦${formatNaira(runningCostNaira)} so far",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = displayMonoFamily(),
                        ),
                        color = TextSecondary,
                    )
                    Text(
                        text = "no preset limit",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = displayMonoFamily(),
                        ),
                        color = TextSecondary,
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
                    LedgerRow(
                        label = "Station",
                        value = stationName.ifBlank { "Station" },
                        valueMonospace = false,
                    )
                    LedgerRow(label = "Price / L", value = "₦$pricePerLitre")
                    LedgerRow(label = "Txn", value = txnId)
                }
            }
        }

        Text(
            text = "Pay after the nozzle shuts — cash or QR. Do not remove the nozzle until done.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatNaira(value: Int): String =
    java.text.NumberFormat.getNumberInstance(java.util.Locale.UK).format(value)

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun FillupDispensingScreenPreview() {
    SmartPumpDisplayTheme {
        FillupDispensingScreen(
            txnId = "BLC-00921",
            pricePerLitre = 870,
            litresSoFar = 18.42,
            stationName = "Total Lekki Ph2",
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 600, heightDp = 1024)
@Composable
private fun FillupDispensingScreenPortraitPreview() {
    SmartPumpDisplayTheme {
        FillupDispensingScreen(
            txnId = "BLC-00921",
            pricePerLitre = 870,
            litresSoFar = 18.42,
            stationName = "Total Lekki Ph2",
        )
    }
}
