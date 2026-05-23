// Dark-only Material3 theme. No light variant, no dynamic colour — kiosk display.
package app.balancee.smartpump.display.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalInspectionMode

private val SmartPumpColorScheme = darkColorScheme(
    primary = PrimaryGold,
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
    // Android Studio's @Preview sandbox has no Google Play Services, so the downloadable
    // Google Fonts provider fails and previews go blank. In inspection mode, swap to a
    // system-font Typography so layouts render — degraded visual, intact structure.
    val typography = if (LocalInspectionMode.current) SmartPumpTypographyPreview
                     else SmartPumpTypography
    MaterialTheme(
        colorScheme = SmartPumpColorScheme,
        typography = typography,
        content = content,
    )
}
