// Typography for the industrial-fintech display language.
// - Hero serif italic: Playfair Display Italic — gold display phrases.
// - Display mono: JetBrains Mono — giant litre counts, naira amounts, ledger values, ref codes.
// - Headings / body: Outfit — every sans-serif surface on the customer + attendant screens.
// - Labels: Outfit, ALL CAPS tracked — field names, state chips.
//
// Fonts resolve via the Google Fonts downloadable provider — no .ttf in res/font, no asset
// shipping. The provider fetches once per font per device and caches indefinitely. On a
// pump kiosk that comes up on WiFi/4G during install, the cache is warm before the
// onboarding flow finishes.
package app.balancee.smartpump.display.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import app.balancee.smartpump.display.R

private val GoogleFontsProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

// — Outfit (body / UI / headings / labels) ————————————————————————————————
private val OutfitGoogle = GoogleFont("Outfit")
val BodyFamily: FontFamily = FontFamily(
    Font(googleFont = OutfitGoogle, fontProvider = GoogleFontsProvider, weight = FontWeight.Normal),
    Font(googleFont = OutfitGoogle, fontProvider = GoogleFontsProvider, weight = FontWeight.Medium),
    Font(googleFont = OutfitGoogle, fontProvider = GoogleFontsProvider, weight = FontWeight.SemiBold),
)

// — Playfair Display (hero serif) —————————————————————————————————————————
private val PlayfairGoogle = GoogleFont("Playfair Display")
val HeroSerif: FontFamily = FontFamily(
    Font(
        googleFont = PlayfairGoogle,
        fontProvider = GoogleFontsProvider,
        weight = FontWeight.Medium,
        style = FontStyle.Italic,
    ),
    Font(
        googleFont = PlayfairGoogle,
        fontProvider = GoogleFontsProvider,
        weight = FontWeight.SemiBold,
    ),
)

// — JetBrains Mono (numbers, ledger values, codes) ————————————————————————
private val JetBrainsMonoGoogle = GoogleFont("JetBrains Mono")
val DisplayMono: FontFamily = FontFamily(
    Font(googleFont = JetBrainsMonoGoogle, fontProvider = GoogleFontsProvider, weight = FontWeight.Normal),
    Font(googleFont = JetBrainsMonoGoogle, fontProvider = GoogleFontsProvider, weight = FontWeight.SemiBold),
)

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

/**
 * Preview-time fallback. Android Studio's preview sandbox has no Google Play Services, so
 * the downloadable-fonts loader fails and previews render blank. [SmartPumpDisplayTheme]
 * swaps to this when [androidx.compose.ui.platform.LocalInspectionMode] is true.
 * Visuals are degraded vs. runtime — system serif/sans/mono — but the layout is intact.
 */
val SmartPumpTypographyPreview: Typography
    get() = SmartPumpTypography.let { real ->
        Typography(
            displayLarge = real.displayLarge.copy(fontFamily = FontFamily.Monospace),
            displayMedium = real.displayMedium.copy(fontFamily = FontFamily.Monospace),
            displaySmall = real.displaySmall.copy(fontFamily = FontFamily.Monospace),
            headlineLarge = real.headlineLarge.copy(fontFamily = FontFamily.SansSerif),
            headlineMedium = real.headlineMedium.copy(fontFamily = FontFamily.SansSerif),
            titleLarge = real.titleLarge.copy(fontFamily = FontFamily.SansSerif),
            titleMedium = real.titleMedium.copy(fontFamily = FontFamily.SansSerif),
            bodyLarge = real.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
            bodyMedium = real.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
            labelLarge = real.labelLarge.copy(fontFamily = FontFamily.SansSerif),
            labelMedium = real.labelMedium.copy(fontFamily = FontFamily.SansSerif),
            labelSmall = real.labelSmall.copy(fontFamily = FontFamily.SansSerif),
        )
    }

/** Preview-safe alias of [HeroSerifItalic] using the system serif. */
val HeroSerifItalicPreview = HeroSerifItalic.copy(fontFamily = FontFamily.Serif)

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
        fontFamily = BodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        color = TextPrimary,
    ),
    headlineMedium = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        color = TextPrimary,
    ),
    titleLarge = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        color = TextPrimary,
    ),
    titleMedium = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        color = TextPrimary,
    ),
    bodyLarge = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = TextPrimary,
    ),
    bodyMedium = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = TextSecondary,
    ),
    // ALL-CAPS field labels e.g. "LITRES DISPENSED", "PRICE/L", "TXN".
    labelLarge = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.1.sp, // ~0.1em at 11sp
        color = TextSecondary,
    ),
    labelMedium = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.0.sp,
        color = TextTertiary,
    ),
    labelSmall = TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.9.sp,
        color = TextTertiary,
    ),
)
