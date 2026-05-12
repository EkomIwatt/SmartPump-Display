// Flow 1, step 4 — green-bordered DISPENSING card. Payment is confirmed; the relay
// is closed and pulses are counting toward [litresAuthorised]. The litres figure updates
// live (no animation — let the number flicker naturally). Cancel is not available
// once dispensing has started — the relay opens at the target on its own.
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
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.ui.components.AmountDisplay
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.LedgerRow
import app.balancee.smartpump.display.ui.components.LitresDisplay
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.PrimaryAmber
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.SuccessGreen
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary

@Composable
fun FixedDispensingScreen(
    flow: TransactionFlow,
    txnId: String,
    pricePerLitre: Int,
    amountNaira: Int,
    litresAuthorised: Double,
    litresSoFar: Double,
    modifier: Modifier = Modifier,
    pumpId: String = "Pump 1",
) {
    val isCash = flow == TransactionFlow.CASH_FIXED
    val figureColor = if (isCash) PrimaryAmber else SuccessGreen
    val borderColor = if (isCash) PrimaryAmber else SuccessGreen
    val progressFraction = if (litresAuthorised > 0) {
        (litresSoFar / litresAuthorised).coerceIn(0.0, 1.0)
    } else 0.0

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PumpHeader(
            pumpId = pumpId,
            mode = if (isCash) "Cash · fixed" else "Pre-pay",
            stateLabel = if (isCash) "Dispensing" else "Confirmed",
            stateColor = borderColor,
        )

        BalanceeCard(
            borderColor = borderColor,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LabelText(text = "Litres dispensed", color = figureColor)
                Box(modifier = Modifier.fillMaxWidth()) {
                    LitresDisplay(
                        litres = litresSoFar,
                        color = figureColor,
                    )
                }
                Text(
                    text = "of %.2f L authorised".format(litresAuthorised),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Text(
                    text = "₦${formatNaira((litresSoFar * pricePerLitre).toInt())} used · " +
                        "₦${formatNaira(amountNaira)} paid",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
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
                        LedgerRow(
                            label = "Progress",
                            value = "%d%%".format((progressFraction * 100).toInt()),
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        LabelText(text = "Paid")
                        AmountDisplay(
                            amountNaira = amountNaira,
                            color = figureColor,
                            style = MaterialTheme.typography.displaySmall,
                        )
                    }
                }
            }
        }

        Text(
            text = "Pump closes the moment the authorised litres are dispensed. Do not remove the nozzle.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatNaira(value: Int): String =
    java.text.NumberFormat.getNumberInstance(java.util.Locale.UK).format(value)

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0F, widthDp = 1024, heightDp = 600)
@Composable
private fun FixedDispensingPreview() {
    SmartPumpDisplayTheme {
        FixedDispensingScreen(
            flow = TransactionFlow.FIXED_PREPAY_DIGITAL,
            txnId = "BLC-00847",
            pricePerLitre = 870,
            amountNaira = 5_000,
            litresAuthorised = 5.75,
            litresSoFar = 3.42,
        )
    }
}
