// Terminal error screen. recoverable=true shows RETRY; recoverable=false shows DISMISS only.
package app.balancee.smartpump.display.ui.screens.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.ui.components.BalanceeButtonPrimary
import app.balancee.smartpump.display.ui.components.BalanceeButtonSecondary
import app.balancee.smartpump.display.ui.components.ErrorBanner
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.Dimensions

@Composable
fun ErrorScreen(
    config: DeviceConfig,
    message: String,
    recoverable: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimensions.sectionSpacing),
    ) {
        PumpHeader(config)
        Spacer(Modifier.weight(0.3f))
        Text(
            text = "Something went wrong",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.error,
        )
        ErrorBanner(message = message)
        Spacer(Modifier.weight(1f))
        if (recoverable) {
            BalanceeButtonPrimary(text = "RETRY", onClick = onRetry)
        }
        BalanceeButtonSecondary(text = "DISMISS", onClick = onDismiss)
    }
}
