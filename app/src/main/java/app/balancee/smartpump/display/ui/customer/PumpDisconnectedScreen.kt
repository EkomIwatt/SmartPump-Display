// 7a-hardening — mid-dispense USB link loss during a FIXED flow (pre-pay / USSD / cash-fixed).
// The customer prepaid for litresTarget, so we never bill the partial litresSoFar: the dispense is
// paused and resumes automatically toward the target when the cable returns (the relay keepalive
// re-energises the board on reconnect). This screen is the "hold" the customer/attendant sees in
// the meantime. The only action is Cancel — for the rare case the link can't be restored and the
// attendant has to abandon the sale (refund handled out-of-band).
//
// Shares the dispensing-family card language (in-card chip + label, big mono litres, state-tinted
// ledger panel) but in WarningRed to read as an attention/fault state.
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
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.LedgerRow
import app.balancee.smartpump.display.ui.components.LitresDisplay
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.components.StateChip
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextSecondary
import app.balancee.smartpump.display.ui.theme.WarningRed
import app.balancee.smartpump.display.ui.util.formatNaira

@Composable
fun PumpDisconnectedScreen(
    litresSoFar: Double,
    litresTarget: Double,
    priceKoboPerLitre: Long,
    txnId: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    pumpId: String = "Pump 1",
) {
    val accent = WarningRed

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PumpHeader(
            pumpId = pumpId,
            mode = "Pre-pay",
            stateLabel = "Disconnected",
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StateChip(label = "Pump disconnected", color = accent)
                    LabelText(text = "Reconnecting…")
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LabelText(text = "Dispensed so far")
                    LitresDisplay(
                        litres = litresSoFar,
                        color = accent,
                    )
                    Text(
                        text = "of ${"%.2f".format(litresTarget)} L paid — resumes when the pump reconnects",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }

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
                    LedgerRow(label = "Paid for", value = "${"%.2f".format(litresTarget)} L")
                    LedgerRow(label = "Price / L", value = formatNaira(priceKoboPerLitre))
                    LedgerRow(label = "Txn", value = txnId)
                }
            }
        }

        BalanceeButton(
            label = "Cancel — fetch attendant",
            onClick = onCancel,
            variant = BalanceeButtonVariant.Secondary,
            accentColor = accent,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun PumpDisconnectedScreenPreview() {
    SmartPumpDisplayTheme {
        PumpDisconnectedScreen(
            litresSoFar = 4.20,
            litresTarget = 10.00,
            priceKoboPerLitre = 87_000,
            txnId = "BLC-00921",
            onCancel = {},
        )
    }
}
