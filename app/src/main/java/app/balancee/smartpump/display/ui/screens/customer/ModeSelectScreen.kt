// Customer chooses PRE-PAY (fixed cash) or FILL-UP (attendant-authorised open dispense).
package app.balancee.smartpump.display.ui.screens.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.ui.components.BalanceeButtonPrimary
import app.balancee.smartpump.display.ui.components.BalanceeButtonSecondary
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.Dimensions

@Composable
fun ModeSelectScreen(
    config: DeviceConfig,
    onSelectMode: (TransactionMode) -> Unit,
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
        Spacer(Modifier.height(24.dp))
        Text(
            text = "How do you want to fuel?",
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.weight(1f))
        BalanceeButtonPrimary(
            text = "PRE-PAY  ·  Fixed amount",
            onClick = { onSelectMode(TransactionMode.PRE_PAY) },
        )
        BalanceeButtonPrimary(
            text = "FILL-UP  ·  Attendant authorised",
            onClick = { onSelectMode(TransactionMode.FILL_UP) },
        )
        Spacer(Modifier.weight(0.5f))
        BalanceeButtonSecondary(text = "BACK", onClick = onBack)
    }
}
