// Top-of-screen strip: pump id · mode label on the left, [StateChip] on the right.
// Renders nothing else (no app bar, no nav) — kiosk design.
package app.balancee.smartpump.display.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun PumpHeader(
    pumpLabel: String,
    mode: String,
    stateLabel: String,
    stateColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "${pumpLabel.uppercase(Locale.ROOT)} · ${mode.uppercase(Locale.ROOT)}",
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Medium,
                color = TextSecondary,
            ),
        )
        StateChip(label = stateLabel, color = stateColor)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 720)
@Composable
private fun PumpHeaderPreview() {
    SmartPumpDisplayTheme {
        Column(
            modifier = Modifier
                .background(Background)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PumpHeader(
                pumpLabel = "Pump 1",
                mode = "Fill-up",
                stateLabel = "Dispensing",
                stateColor = ActiveCyan,
            )
            PumpHeader(
                pumpLabel = "Pump 1",
                mode = "Pre-pay",
                stateLabel = "Waiting",
                stateColor = PrimaryGold,
            )
        }
    }
}
