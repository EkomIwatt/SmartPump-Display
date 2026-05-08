// Live dispense view. Litres + amount update from incoming pulses.
// litresAuthorised / amountKobo are null in fill-up mode (open-ended).
package app.balancee.smartpump.display.ui.screens.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.ui.components.AmountDisplay
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.LitresDisplay
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.Dimensions

@Composable
fun DispensingScreen(
    config: DeviceConfig,
    litresDispensed: Double,
    amountDispensedKobo: Long,
    amountAuthorisedKobo: Long?,
    litresAuthorised: Double?,
    modifier: Modifier = Modifier,
) {
    val progress = when {
        litresAuthorised != null && litresAuthorised > 0.0 ->
            (litresDispensed / litresAuthorised).coerceIn(0.0, 1.0).toFloat()
        else -> null
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimensions.sectionSpacing),
    ) {
        PumpHeader(config)
        Spacer(Modifier.weight(0.5f))
        LitresDisplay(
            litres = litresDispensed,
            label = "Litres dispensed",
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondary,
        )
        AmountDisplay(
            amountKobo = amountDispensedKobo,
            label = "Amount",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.displayMedium,
        )
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        if (amountAuthorisedKobo != null) {
            LabelText(
                text = "Authorised: ₦${amountAuthorisedKobo / 100}",
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = "Fill-up in progress",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}
