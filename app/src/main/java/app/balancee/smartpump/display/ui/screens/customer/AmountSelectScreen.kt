// Customer enters a fixed Naira amount via preset chips or the numeric keypad.
// Live-computes the litre cutoff from the current price so the customer sees what they'll get.
package app.balancee.smartpump.display.ui.screens.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.ui.components.AmountDisplay
import app.balancee.smartpump.display.ui.components.BalanceeButtonPrimary
import app.balancee.smartpump.display.ui.components.BalanceeButtonSecondary
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.NumericKeypad
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.Dimensions

private val PresetsKobo = listOf(100_000L, 200_000L, 500_000L, 1_000_000L) // ₦1k / ₦2k / ₦5k / ₦10k
private const val MaxAmountKobo = 50_000_00L // ₦50,000 sanity cap

@Composable
fun AmountSelectScreen(
    config: DeviceConfig,
    onConfirm: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var amountKobo by remember { mutableLongStateOf(0L) }
    val litres = if (amountKobo > 0) config.litresCutoff(amountKobo) else 0.0

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimensions.sectionSpacing),
    ) {
        PumpHeader(config)
        Text(
            text = "How much fuel?",
            style = MaterialTheme.typography.headlineLarge,
        )
        AmountDisplay(
            amountKobo = amountKobo,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "≈ %.2f L".format(litres),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        LabelText("Quick select")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PresetsKobo.forEach { preset ->
                OutlinedButton(
                    onClick = { amountKobo = preset },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(Dimensions.cornerButton),
                ) {
                    Text("₦${preset / 100}", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        NumericKeypad(
            onDigit = { d ->
                val next = amountKobo * 10 + d * 100L // each digit appends a Naira (= 100 kobo)
                if (next <= MaxAmountKobo) amountKobo = next
            },
            onBackspace = { amountKobo = (amountKobo / 1000L) * 100L },
            onClear = { amountKobo = 0L },
        )
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BalanceeButtonSecondary(
                text = "BACK",
                onClick = onBack,
                modifier = Modifier.weight(1f),
            )
            BalanceeButtonPrimary(
                text = "CONFIRM",
                onClick = { onConfirm(amountKobo) },
                enabled = amountKobo > 0,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
