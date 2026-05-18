// Default attract-state screen. Customer taps "Start transaction" to enter the flow.
// Attendant actions live behind the swipe-up overlay (AttendantOverlayHost, Phase 4).
package app.balancee.smartpump.display.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
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
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.BrandBlue
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextSecondary

@Composable
fun IdleScreen(
    onStartTransaction: () -> Unit,
    modifier: Modifier = Modifier,
    pumpId: String = "Pump 1",
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(Dimensions.screenPadding),
    ) {
        PumpHeader(
            pumpId = pumpId,
            mode = "Idle",
            stateLabel = "Idle",
            stateColor = BorderSubtle,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        BalanceeCard(
            borderColor = BrandBlue,
            modifier = Modifier
                .align(Alignment.Center)
                .sizeIn(maxWidth = 520.dp),
        ) {
            Column(
                modifier = Modifier.padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                HeroSerifText(text = "balanceè", color = BrandBlue)
                Text(
                    text = "Smart pump · pay any way",
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                    textAlign = TextAlign.Center,
                )
                LabelText(text = "Tap to fuel")
                BalanceeButton(
                    label = "Start transaction",
                    onClick = onStartTransaction,
                    variant = BalanceeButtonVariant.Brand,
                    modifier = Modifier.width(360.dp),
                )
                Text(
                    text = "Attendant? Swipe up from the bottom edge.",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0F, widthDp = 1024, heightDp = 600)
@Composable
private fun IdleScreenPreview() {
    SmartPumpDisplayTheme {
        IdleScreen(onStartTransaction = {})
    }
}
