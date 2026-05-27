// Flow 2, step 2 — pulse-timeout watchdog fired (or attendant ended the fill); nozzle is treated
// as shut. Verified litres are locked. The customer now picks how to pay:
//   - Cash → FillupAwaitingCashConfirm (attendant taps CASH RECEIVED next)
//   - Digital → branches to Flow 3 (dynamic QR for the verified amount)
//
// Shares the FixedDispensingScreen layout language (in-card chip + word, centered hero, tinted
// ledger panel) but this is a post-dispensing "amount due" screen, so the hero is the gold
// AMOUNT DUE with a "verified litres" sub-line instead of a live litre count + progress bar.
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
import app.balancee.smartpump.display.ui.util.formatNaira
import app.balancee.smartpump.display.ui.util.isPortrait

@Composable
fun FillupTankFullScreen(
    txnId: String,
    priceKoboPerLitre: Long,
    verifiedLitres: Double,
    amountDueKobo: Long,
    onPayCash: () -> Unit,
    onPayDigital: () -> Unit,
    modifier: Modifier = Modifier,
    pumpId: String = "Pump 1",
    digitalEnabled: Boolean = false,
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
            mode = "Fill up",
            stateLabel = "Tank full",
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
                    StateChip(label = "Tank full", color = accent)
                    LabelText(text = "Verified")
                }

                // Centred hero — the amount due (gold) with the verified-litres sub-line.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LabelText(text = "Amount due")
                    AmountDisplay(
                        amountKobo = amountDueKobo,
                        color = accent,
                        style = MaterialTheme.typography.displayMedium,
                    )
                    Text(
                        text = "%.2f L · verified — cannot be changed".format(verifiedLitres),
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
                    LedgerRow(label = "Price / L", value = formatNaira(priceKoboPerLitre))
                    LedgerRow(label = "Txn", value = txnId)
                }
            }
        }

        LabelText(text = "Pay now")
        // Pay-cash / pay-digital actions: side-by-side on landscape, stacked on portrait
        // (the digital label is long).
        if (isPortrait()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BalanceeButton(
                    label = "Pay cash",
                    onClick = onPayCash,
                    accentColor = accent,
                    modifier = Modifier.fillMaxWidth(),
                )
                BalanceeButton(
                    label = "Pay digitally · scan QR",
                    onClick = onPayDigital,
                    enabled = digitalEnabled,
                    variant = BalanceeButtonVariant.Secondary,
                    accentColor = accent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
            ) {
                BalanceeButton(
                    label = "Pay cash",
                    onClick = onPayCash,
                    accentColor = accent,
                    modifier = Modifier.weight(1f),
                )
                BalanceeButton(
                    label = "Pay digitally · scan QR",
                    onClick = onPayDigital,
                    enabled = digitalEnabled,
                    variant = BalanceeButtonVariant.Secondary,
                    accentColor = accent,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun FillupTankFullPreview() {
    SmartPumpDisplayTheme {
        FillupTankFullScreen(
            txnId = "BLC-00921",
            priceKoboPerLitre = 87_050,
            verifiedLitres = 38.10,
            amountDueKobo = 3_316_605,
            onPayCash = {},
            onPayDigital = {},
            digitalEnabled = true,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 600, heightDp = 1024)
@Composable
private fun FillupTankFullPortraitPreview() {
    SmartPumpDisplayTheme {
        FillupTankFullScreen(
            txnId = "BLC-00921",
            priceKoboPerLitre = 87_050,
            verifiedLitres = 38.10,
            amountDueKobo = 3_316_605,
            onPayCash = {},
            onPayDigital = {},
            digitalEnabled = true,
        )
    }
}
