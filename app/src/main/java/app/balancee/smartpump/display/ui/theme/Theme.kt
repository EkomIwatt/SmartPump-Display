// Dark-only Material3 theme. No light variant, no dynamic colour — kiosk display.
package app.balancee.smartpump.display.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val SmartPumpColorScheme = darkColorScheme(
    primary = PrimaryAmber,
    onPrimary = OnPrimary,
    secondary = ActiveCyan,
    onSecondary = OnPrimary,
    tertiary = SuccessGreen,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = WarningRed,
    onError = TextPrimary,
    outline = BorderSubtle,
)

@Composable
fun SmartPumpDisplayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SmartPumpColorScheme,
        typography = SmartPumpTypography,
        content = content,
    )
}
