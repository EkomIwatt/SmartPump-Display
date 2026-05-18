// Pick PRE-PAY (fixed amount, customer pays first) or FILL UP (open-ended, attendant authorises).
// Two tappable cards side-by-side; cancel returns to Idle.
package app.balancee.smartpump.display.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
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
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.PrimaryAmber
import app.balancee.smartpump.display.ui.theme.BrandBlue
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary

@Composable
fun ModeSelectScreen(
    onSelectPrePay: () -> Unit,
    onSelectFillUp: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    pumpId: String = "Pump 1",
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        PumpHeader(
            pumpId = pumpId,
            mode = "Mode select",
            stateLabel = "Choosing",
            stateColor = BorderSubtle,
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "How do you want to fuel?",
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
            )
            Text(
                text = "Pick a mode. Cash flows go through the attendant.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
        ) {
            ModeCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                accent = BrandBlue,
                title = "Pre-pay",
                heroLine = "Fixed amount, pay before fuel flows.",
                bullets = listOf(
                    "Pick a Naira amount.",
                    "Pay digitally — Balanceè, NFC, bank QR, USSD.",
                    "Pump opens on confirmation, closes at target.",
                ),
                onClick = onSelectPrePay,
            )
            ModeCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                accent = ActiveCyan,
                title = "Fill up",
                heroLine = "Open-ended fill. Pay after the nozzle shuts.",
                bullets = listOf(
                    "Attendant authorises the pump.",
                    "Nozzle shuts automatically when tank is full.",
                    "Pay the exact verified amount in cash or QR.",
                ),
                onClick = onSelectFillUp,
            )
        }

        BalanceeButton(
            label = "Cancel",
            onClick = onCancel,
            variant = BalanceeButtonVariant.Secondary,
        )
    }
}

@Composable
private fun ModeCard(
    accent: Color,
    title: String,
    heroLine: String,
    bullets: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BalanceeCard(
        borderColor = accent,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LabelText(text = title, color = accent)
            HeroSerifText(text = heroLine, color = accent)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                bullets.forEach { line ->
                    Text(
                        text = "·  $line",
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                BalanceeButton(
                    label = "Choose $title",
                    onClick = onClick,
                    variant = BalanceeButtonVariant.Brand,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0F, widthDp = 1024, heightDp = 600)
@Composable
private fun ModeSelectScreenPreview() {
    SmartPumpDisplayTheme {
        ModeSelectScreen(
            onSelectPrePay = {},
            onSelectFillUp = {},
            onCancel = {},
        )
    }
}
