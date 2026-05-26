// Flow 1 / Flow 5 / Flow 4 share this fixed-target DISPENSING card. Payment (or cash) is
// confirmed; the relay is closed and pulses are counting toward [litresAuthorised]. The
// litres figure updates live (no animation — let it flicker as it updates). Cancel is not
// available once dispensing starts — the relay opens at the target on its own.
//
// Rebuilt (2026-05-25) to match docs/compare/expected.png (Flow 1 "PAYMENT CONFIRMED" card)
// and the Flow 4 "DISPENSING TO CUTOFF" card:
//   - Pre-pay / USSD → green accent (border/chip/progress), litres figure in white.
//   - Cash-fixed     → gold accent, litres figure in gold.
//   - Big litres figure uses the shared mono LitresDisplay (JetBrains Mono, the non-negotiable
//     display style), centered, coloured per the rules above.
//   - In-card header row: state chip on the left, activity word ("DISPENSING" / "CASH") on the right.
//   - Centered "LITRES DISPENSED" label + "of X.XXL authorised|target" sub-line.
//   - Progress bar, then a "₦used  ·  ₦auth|cash" split line.
//   - Ledger sits in a subtly state-tinted rounded panel: pre-pay/USSD = STATION / PRICE-L / TXN,
//     cash = PRICE-L / CUTOFF AT.
package app.balancee.smartpump.display.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.domain.model.TransactionFlow
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.LedgerRow
import app.balancee.smartpump.display.ui.components.LitresDisplay
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.components.StateChip
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.SuccessGreen
import app.balancee.smartpump.display.ui.theme.SurfaceVariant
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary
import app.balancee.smartpump.display.ui.theme.displayMonoFamily

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
    stationName: String = "Station",
) {
    val isCash = flow == TransactionFlow.CASH_FIXED
    // Accent drives the border, chip, progress bar. Cash = gold; pre-pay/USSD = green.
    val accent = if (isCash) PrimaryGold else SuccessGreen
    // The litres figure is gold in cash mode but stays white for the confirmed pre-pay/USSD state.
    val figureColor = if (isCash) PrimaryGold else TextPrimary
    val progressFraction = if (litresAuthorised > 0) {
        (litresSoFar / litresAuthorised).coerceIn(0.0, 1.0)
    } else 0.0
    val usedNaira = (litresSoFar * pricePerLitre).toInt()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PumpHeader(
            pumpId = pumpId,
            mode = when {
                isCash -> "Cash · fixed"
                flow == TransactionFlow.USSD_OFFLINE -> "USSD"
                else -> "Pre-pay"
            },
            stateLabel = if (isCash) "Dispensing" else "Confirmed",
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
                    StateChip(
                        label = if (isCash) "Dispensing" else "Confirmed",
                        color = accent,
                    )
                    LabelText(text = if (isCash) "Cash" else "Dispensing")
                }

                // Centred hero — label, big serif litres figure (no "L" suffix), target sub-line.
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
                        color = figureColor,
                    )
                    Text(
                        text = "of %.2fL %s".format(
                            litresAuthorised,"authorised",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }

                ProgressBar(fraction = progressFraction.toFloat(), color = accent)

                // ₦ used (left) · ₦ authorised/cash (right) — muted mono, mirrors the spec.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "₦${formatNaira(usedNaira)} used",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = displayMonoFamily(),
                        ),
                        color = TextSecondary,
                    )
                    Text(
                        text = "₦${formatNaira(amountNaira)} ${if (isCash) "cash" else "auth"}",
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
                    if (!isCash) {
                        LedgerRow(
                            label = "Station",
                            value = stationName.ifBlank { "Station" },
                            valueMonospace = false,
                        )
                    }
                    LedgerRow(label = "Price / L", value = "₦$pricePerLitre")
                    if (isCash) {
                        LedgerRow(label = "Cutoff at", value = "%.2f L".format(litresAuthorised))
                    } else {
                        LedgerRow(label = "Txn", value = txnId)
                    }
                }
            }
        }

        Text(
            text = if (isCash) {
                "Counting to the litre cutoff — cuts automatically. Never dispenses more than the cash paid."
            } else {
                "Pump closes the moment the authorised litres are dispensed. Do not remove the nozzle."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProgressBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val safe = fraction.coerceIn(0f, 1f)
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(shape)
            .background(SurfaceVariant),
    ) {
        // Industrial-brutalism — sharp tick, no animation. State-colour fill on top of
        // a flat track surface; reads under direct sunlight at glance distance.
        Box(
            modifier = Modifier
                .fillMaxWidth(safe)
                .height(8.dp)
                .background(color),
        )
    }
}

private fun formatNaira(value: Int): String =
    java.text.NumberFormat.getNumberInstance(java.util.Locale.UK).format(value)

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun FixedDispensingPrepayPreview() {
    SmartPumpDisplayTheme {
        FixedDispensingScreen(
            flow = TransactionFlow.FIXED_PREPAY_DIGITAL,
            txnId = "BLC-00847",
            pricePerLitre = 870,
            amountNaira = 5_000,
            litresAuthorised = 5.75,
            litresSoFar = 3.42,
            stationName = "Total Lekki Ph2",
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun FixedDispensingCashPreview() {
    SmartPumpDisplayTheme {
        FixedDispensingScreen(
            flow = TransactionFlow.CASH_FIXED,
            txnId = "BLC-00031",
            pricePerLitre = 870,
            amountNaira = 5_000,
            litresAuthorised = 5.75,
            litresSoFar = 3.18,
            stationName = "Total Lekki Ph2",
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 600, heightDp = 1024)
@Composable
private fun FixedDispensingPortraitPreview() {
    SmartPumpDisplayTheme {
        FixedDispensingScreen(
            flow = TransactionFlow.FIXED_PREPAY_DIGITAL,
            txnId = "BLC-00847",
            pricePerLitre = 870,
            amountNaira = 5_000,
            litresAuthorised = 5.75,
            litresSoFar = 3.42,
            stationName = "Total Lekki Ph2",
        )
    }
}
