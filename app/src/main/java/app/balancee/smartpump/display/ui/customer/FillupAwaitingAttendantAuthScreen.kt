// Flow 2, step 0 — customer picked FILL UP. We're now waiting for the attendant to tap
// FILL UP AUTHORISE (either via Phase 4 swipe-up overlay or, for now, the temp idle-screen
// button). Customer-side shows a cyan card explaining what's about to happen + a Cancel.
package app.balancee.smartpump.display.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.HeroSerifText
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary

@Composable
fun FillupAwaitingAttendantAuthScreen(
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
            mode = "Fill up",
            stateLabel = "Awaiting attendant",
            stateColor = ActiveCyan,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            BalanceeCard(
                borderColor = ActiveCyan,
                modifier = Modifier.sizeIn(maxWidth = 560.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LabelText(text = "Fill up requested", color = ActiveCyan)
                    HeroSerifText(text = "Ask the attendant.", color = ActiveCyan)
                    Text(
                        text = "The pump opens as soon as the attendant taps FILL UP " +
                            "AUTHORISE. Then squeeze the nozzle — it shuts automatically when " +
                            "the tank is full. Pay cash or scan a QR after.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "Step away or tap CANCEL if you change your mind.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        BalanceeButton(
            label = "Cancel",
            onClick = onCancel,
            variant = BalanceeButtonVariant.Secondary,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0F, widthDp = 1024, heightDp = 600)
@Composable
private fun FillupAwaitingAttendantAuthPreview() {
    SmartPumpDisplayTheme {
        FillupAwaitingAttendantAuthScreen(onCancel = {})
    }
}
