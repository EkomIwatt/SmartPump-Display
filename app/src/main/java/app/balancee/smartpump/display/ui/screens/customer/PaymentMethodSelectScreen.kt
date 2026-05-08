// Customer chooses how to pay. CASH is attendant-only — never shown here.
package app.balancee.smartpump.display.ui.screens.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.ui.components.AmountDisplay
import app.balancee.smartpump.display.ui.components.BalanceeButtonPrimary
import app.balancee.smartpump.display.ui.components.BalanceeButtonSecondary
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.Dimensions

private val CustomerMethods = listOf(
    PaymentMethod.BALANCEE_APP to "Balanceè App",
    PaymentMethod.BANK_QR to "Bank Transfer (QR)",
    PaymentMethod.NFC to "Tap to Pay (NFC)",
    PaymentMethod.USSD to "USSD",
)

@Composable
fun PaymentMethodSelectScreen(
    config: DeviceConfig,
    amountKobo: Long,
    onSelectMethod: (PaymentMethod) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimensions.sectionSpacing),
    ) {
        PumpHeader(config)
        Text(
            text = "Choose payment method",
            style = MaterialTheme.typography.headlineLarge,
        )
        AmountDisplay(
            amountKobo = amountKobo,
            label = "Amount due",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(0.5f))
        CustomerMethods.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { (method, label) ->
                    BalanceeButtonPrimary(
                        text = label,
                        onClick = { onSelectMethod(method) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        BalanceeButtonSecondary(text = "BACK", onClick = onBack)
    }
}
