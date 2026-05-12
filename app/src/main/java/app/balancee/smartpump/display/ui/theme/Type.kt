// Typography for the industrial-fintech display language.
// - Hero serif italic: high-contrast serif italic for "Every state. Every flow." phrases — gold, italic.
// - Display mono: JetBrains/Space Mono — giant litre counts, naira amounts.
// - Headings / body: system sans (Inter when bundled).
// - Labels: ALL CAPS tracked — field names, state chips.
package app.balancee.smartpump.display.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// TODO: bundle Space Mono + Playfair Display .ttf in res/font/ for offline kiosk.
//   DisplayMono = FontFamily(Font(R.font.space_mono_regular))
//   HeroSerif   = FontFamily(Font(R.font.playfair_display_italic))
val DisplayMono: FontFamily = FontFamily.Monospace
val HeroSerif: FontFamily = FontFamily.Serif

/**
 * Hero serif italic — used for gold display phrases on cover / section headers
 * (e.g. "Every state. Every flow.", "Fixed amount, pay before fuel flows.").
 * Color is applied at call site so it can carry the state colour.
 */
val HeroSerifItalic = TextStyle(
    fontFamily = HeroSerif,
    fontStyle = FontStyle.Italic,
    fontWeight = FontWeight.Medium,
    fontSize = 40.sp,
    lineHeight = 48.sp,
)

val SmartPumpTypography = Typography(
    // Giant litre count and Naira amounts on dispensing/complete screens (120sp+).
    displayLarge = TextStyle(
        fontFamily = DisplayMono,
        fontWeight = FontWeight.Normal,
        fontSize = 120.sp,
        lineHeight = 120.sp,
        letterSpacing = (-2).sp,
        color = TextPrimary,
    ),
    // Medium numeric display e.g. amount-due on awaiting-cash screen.
    displayMedium = TextStyle(
        fontFamily = DisplayMono,
        fontWeight = FontWeight.Normal,
        fontSize = 72.sp,
        lineHeight = 72.sp,
        letterSpacing = (-1).sp,
        color = TextPrimary,
    ),
    // Smaller monospace numbers e.g. running total in Naira.
    displaySmall = TextStyle(
        fontFamily = DisplayMono,
        fontWeight = FontWeight.Normal,
        fontSize = 48.sp,
        lineHeight = 52.sp,
        color = TextPrimary,
    ),
    // Screen headings e.g. "HOW DO YOU WANT TO FUEL?".
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        color = TextPrimary,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        color = TextPrimary,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        color = TextPrimary,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        color = TextPrimary,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = TextPrimary,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = TextSecondary,
    ),
    // ALL-CAPS field labels e.g. "LITRES DISPENSED", "PRICE/L", "TXN".
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.1.sp, // ~0.1em at 11sp
        color = TextSecondary,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.0.sp,
        color = TextTertiary,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.9.sp,
        color = TextTertiary,
    ),
)
