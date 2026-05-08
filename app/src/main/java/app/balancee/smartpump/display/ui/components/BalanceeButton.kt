// Primary (amber, 64 dp) and secondary (outlined, 48 dp) action buttons.
// Sized for daylight visibility and gloved fingers — see Dimensions.kt.
package app.balancee.smartpump.display.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.balancee.smartpump.display.ui.theme.Dimensions

@Composable
fun BalanceeButtonPrimary(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(Dimensions.buttonHeightPrimary),
        enabled = enabled,
        shape = RoundedCornerShape(Dimensions.cornerButton),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun BalanceeButtonSecondary(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(Dimensions.buttonHeightSecondary),
        enabled = enabled,
        shape = RoundedCornerShape(Dimensions.cornerButton),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}
