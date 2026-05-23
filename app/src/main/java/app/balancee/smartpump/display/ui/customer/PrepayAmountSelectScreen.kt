// Flow 1, step 1 — customer picks the Naira amount they want to pre-pay.
// Six preset tiles (₦2k/₦5k/₦10k/₦20k/₦50k/Custom). Picking Custom replaces the
// tile grid with a numeric keypad for any amount. Spec doesn't dictate a custom
// screen (see OPEN_QUESTIONS #16); this kiosk-native design keeps the customer
// on-screen rather than routing through the attendant.
package app.balancee.smartpump.display.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.components.AmountDisplay
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.NumericKeypad
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.BrandBlue
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary

private val PresetAmounts = listOf(2_000, 5_000, 10_000, 20_000, 50_000)
private const val CUSTOM_MAX_NAIRA = 200_000
private const val CUSTOM_MIN_NAIRA = 200

@Composable
fun PrepayAmountSelectScreen(
    onAmountChosen: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    pumpId: String = "Pump 1",
) {
    var customMode by rememberSaveable { mutableStateOf(false) }

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
            stateLabel = "Amount",
            stateColor = BorderSubtle,
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "How much fuel?",
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
            )
            Text(
                text = "Pick a preset or enter your own. Pay digitally on the next step.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        if (customMode) {
            CustomAmountPanel(
                onConfirm = onAmountChosen,
                onCancelCustom = { customMode = false },
                modifier = Modifier.weight(1f),
            )
        } else {
            PresetGrid(
                onPreset = onAmountChosen,
                onCustom = { customMode = true },
                modifier = Modifier.weight(1f),
            )
        }

        BalanceeButton(
            label = "Cancel",
            onClick = onBack,
            variant = BalanceeButtonVariant.Secondary,
        )
    }
}

@Composable
private fun PresetGrid(
    onPreset: (Int) -> Unit,
    onCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = PresetAmounts.chunked(3)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
            ) {
                row.forEach { amount ->
                    PresetTile(
                        amount = amount,
                        onClick = { onPreset(amount) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
                if (row.size < 3) {
                    repeat(3 - row.size) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
        ) {
            CustomTile(
                onClick = onCustom,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            Box(modifier = Modifier.weight(1f))
            Box(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PresetTile(
    amount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BalanceeCard(
        borderColor = BorderSubtle,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LabelText(text = "Amount")
            AmountDisplay(
                amountNaira = amount,
                color = BrandBlue,
                style = MaterialTheme.typography.headlineLarge,
            )
        }
    }
}

@Composable
private fun CustomTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BalanceeCard(
        borderColor = ActiveCyan,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LabelText(text = "Custom", color = ActiveCyan)
            Text(
                text = "Enter ₦",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
                color = ActiveCyan,
            )
            Text(
                text = "Type any amount on the keypad.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun CustomAmountPanel(
    onConfirm: (Int) -> Unit,
    onCancelCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf(0) }
    val valid = draft in CUSTOM_MIN_NAIRA..CUSTOM_MAX_NAIRA

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
    ) {
        BalanceeCard(
            borderColor = BrandBlue,
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LabelText(text = "Custom amount", color = ActiveCyan)
                Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    AmountDisplay(
                        amountNaira = draft,
                        color = BrandBlue,
                        style = MaterialTheme.typography.displayMedium,
                    )
                }
                Text(
                    text = "Min ₦$CUSTOM_MIN_NAIRA · Max ₦${formatGrouped(CUSTOM_MAX_NAIRA)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Box(modifier = Modifier.weight(1f))
                BalanceeButton(
                    label = "Back to presets",
                    onClick = onCancelCustom,
                    variant = BalanceeButtonVariant.Secondary,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NumericKeypad(
                onDigit = { d ->
                    val next = draft * 10 + d
                    if (next <= CUSTOM_MAX_NAIRA) draft = next
                },
                onBackspace = { draft /= 10 },
                onConfirm = { if (valid) onConfirm(draft) },
                confirmEnabled = valid,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun formatGrouped(value: Int): String =
    java.text.NumberFormat.getNumberInstance(java.util.Locale.UK).format(value)

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun PrepayAmountSelectPreview() {
    SmartPumpDisplayTheme {
        PrepayAmountSelectScreen(
            onAmountChosen = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun PrepayAmountSelectCustomPreview() {
    SmartPumpDisplayTheme {
        // Render the custom keypad branch directly by simulating the toggle.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
                .padding(Dimensions.screenPadding),
        ) {
            CustomAmountPanel(onConfirm = {}, onCancelCustom = {}, modifier = Modifier.weight(1f))
        }
    }
}
