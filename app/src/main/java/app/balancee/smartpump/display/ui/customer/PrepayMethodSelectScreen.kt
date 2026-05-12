// Flow 1, step 2 — customer picks a payment method for the already-chosen amount.
// Five methods per docs/flows.md: Balanceè App, Bank QR / Transfer, NFC card, USSD, Cash.
// Cash routes to the attendant (returns to Idle in Phase 3b — attendant overlay lands in Phase 4).
package app.balancee.smartpump.display.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.ui.components.AmountDisplay
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.PrimaryAmber
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.SuccessGreen
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary

private data class MethodTile(
    val method: PaymentMethod,
    val title: String,
    val tag: String,
    val description: String,
    val accent: Color,
)

private val MethodTiles = listOf(
    MethodTile(
        method = PaymentMethod.BALANCEE_APP,
        title = "Balanceè",
        tag = "App",
        description = "Open the Balanceè app. Tap to confirm.",
        accent = PrimaryAmber,
    ),
    MethodTile(
        method = PaymentMethod.BANK_QR_TRANSFER,
        title = "Bank QR",
        tag = "Transfer",
        description = "Scan with any bank app. NIP transfer.",
        accent = SuccessGreen,
    ),
    MethodTile(
        method = PaymentMethod.NFC_CARD,
        title = "Tap card",
        tag = "NFC",
        description = "Tap your bank card on the panel.",
        accent = ActiveCyan,
    ),
    MethodTile(
        method = PaymentMethod.USSD,
        title = "USSD *737#",
        tag = "Offline",
        description = "Dial from any phone. We listen for the SMS.",
        accent = PrimaryAmber,
    ),
    MethodTile(
        method = PaymentMethod.CASH_SEE_ATTENDANT,
        title = "Cash",
        tag = "See attendant",
        description = "Hand cash to the attendant. They authorise.",
        accent = BorderSubtle,
    ),
)

@Composable
fun PrepayMethodSelectScreen(
    amountNaira: Int,
    onMethodChosen: (PaymentMethod) -> Unit,
    onBack: () -> Unit,
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
            mode = "Pre-pay",
            stateLabel = "Method",
            stateColor = BorderSubtle,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Pick a payment method",
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary,
                )
                Text(
                    text = "Fuel flows the moment payment confirms.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                LabelText(text = "Pre-pay amount")
                AmountDisplay(
                    amountNaira = amountNaira,
                    color = PrimaryAmber,
                    style = MaterialTheme.typography.displaySmall,
                )
            }
        }

        val rows = MethodTiles.chunked(3)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
        ) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
                ) {
                    row.forEach { tile ->
                        MethodCard(
                            tile = tile,
                            onClick = { onMethodChosen(tile.method) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize(),
                        )
                    }
                    if (row.size < 3) {
                        repeat(3 - row.size) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        BalanceeButton(
            label = "Back",
            onClick = onBack,
            variant = BalanceeButtonVariant.Secondary,
        )
    }
}

@Composable
private fun MethodCard(
    tile: MethodTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BalanceeCard(
        borderColor = tile.accent,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LabelText(text = tile.tag, color = tile.accent)
            Text(
                text = tile.title,
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
            )
            Text(
                text = tile.description,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0F, widthDp = 1024, heightDp = 600)
@Composable
private fun PrepayMethodSelectPreview() {
    SmartPumpDisplayTheme {
        PrepayMethodSelectScreen(
            amountNaira = 5_000,
            onMethodChosen = {},
            onBack = {},
        )
    }
}
