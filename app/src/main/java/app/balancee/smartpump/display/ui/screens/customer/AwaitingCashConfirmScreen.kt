// Fill-up shutoff has fired and the final litres/amount are locked in.
// Customer pays attendant cash; attendant confirms via overlay (Phase 5).
package app.balancee.smartpump.display.ui.screens.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.ui.components.AmountDisplay
import app.balancee.smartpump.display.ui.components.LitresDisplay
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.Dimensions

@Composable
fun AwaitingCashConfirmScreen(
    config: DeviceConfig,
    litresDispensed: Double,
    amountDueKobo: Long,
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
            text = "Pay attendant cash",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(0.3f))
        LitresDisplay(
            litres = litresDispensed,
            label = "Litres dispensed",
            modifier = Modifier.fillMaxWidth(),
        )
        AmountDisplay(
            amountKobo = amountDueKobo,
            label = "Amount due",
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "Hand cash to attendant. Attendant will confirm receipt to complete the transaction.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
