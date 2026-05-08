// Customer-facing wait state during fill-up flow — attendant must authorise via overlay.
// authorisedByAttendant flips true once the attendant taps FILL UP AUTHORISE.
package app.balancee.smartpump.display.ui.screens.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.ui.components.BalanceeButtonSecondary
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.Dimensions

@Composable
fun FillUpConfirmScreen(
    config: DeviceConfig,
    authorisedByAttendant: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimensions.sectionSpacing),
    ) {
        PumpHeader(config)
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(
                color = if (authorisedByAttendant)
                    MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (authorisedByAttendant) "Ready — start fueling" else "Awaiting attendant",
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = if (authorisedByAttendant)
                    "Lift the nozzle to dispense"
                else "An attendant will authorise your fill-up shortly",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        BalanceeButtonSecondary(text = "CANCEL", onClick = onCancel)
    }
}
