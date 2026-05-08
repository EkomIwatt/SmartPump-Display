// Transaction complete. Shows receipt-style summary; tap anywhere to return to Idle.
package app.balancee.smartpump.display.ui.screens.customer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.ui.components.AmountDisplay
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.LitresDisplay
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.Dimensions

@Composable
fun CompleteScreen(
    config: DeviceConfig,
    transactionId: String,
    litres: Double,
    amountKobo: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onDone)
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimensions.sectionSpacing),
    ) {
        PumpHeader(config)
        Spacer(Modifier.weight(0.3f))
        Text(
            text = "Thank you",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.fillMaxWidth(),
        )
        LitresDisplay(
            litres = litres,
            label = "Litres",
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.tertiary,
        )
        AmountDisplay(
            amountKobo = amountKobo,
            label = "Total paid",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.displayMedium,
        )
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LabelText("Txn $transactionId")
            Spacer(Modifier.padding(top = 8.dp))
            LabelText("Tap anywhere to finish")
        }
    }
}
