// Top status strip: station name, pump ID, current price/L. Visible on every customer screen.
package app.balancee.smartpump.display.ui.components

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
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.domain.model.DeviceConfig

@Composable
fun PumpHeader(
    config: DeviceConfig,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            LabelText("Station")
            Text(config.stationName, style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = config.pumpId,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(horizontalAlignment = Alignment.End) {
            LabelText("Price/L")
            Text(
                text = "₦${"%.2f".format(config.nairaPerLitre)}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
