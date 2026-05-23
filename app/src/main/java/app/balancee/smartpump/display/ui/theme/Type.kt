// Typography for the industrial-fintech display language.
package app.balancee.smartpump.display.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import app.balancee.smartpump.display.R

// — Google Fonts Setup ————————————————————————————————————————————————————
private val GoogleFontsProvider: GoogleFont.Provider
    get() = GoogleFont.Provider(
        providerAuthority = "com.google.android.gms.fonts",
        providerPackage = "com.google.android.gms",
        certificates = R.array.com_google_android_gms_fonts_certs,
    )

private val OutfitGoogle get() = GoogleFont("Outfit")
private val PlayfairGoogle get() = GoogleFont("Playfair Display")
private val JetBrainsMonoGoogle get() = GoogleFont("JetBrains Mono")

val BodyFamily: FontFamily
    get() = FontFamily(
        Font(googleFont = OutfitGoogle, fontProvider = GoogleFontsProvider, weight = FontWeight.Normal),
        Font(googleFont = OutfitGoogle, fontProvider = GoogleFontsProvider, weight = FontWeight.Medium),
        Font(googleFont = OutfitGoogle, fontProvider = GoogleFontsProvider, weight = FontWeight.SemiBold),
    )

val HeroSerif: FontFamily
    get() = FontFamily(
        Font(googleFont = PlayfairGoogle, fontProvider = GoogleFontsProvider, weight = FontWeight.Medium, style = FontStyle.Italic),
        Font(googleFont = PlayfairGoogle, fontProvider = GoogleFontsProvider, weight = FontWeight.SemiBold),
    )

val DisplayMono: FontFamily
    get() = FontFamily(
        Font(googleFont = JetBrainsMonoGoogle, fontProvider = GoogleFontsProvider, weight = FontWeight.Normal),
        Font(googleFont = JetBrainsMonoGoogle, fontProvider = GoogleFontsProvider, weight = FontWeight.SemiBold),
    )

// — Base Styles (Font-agnostic) ———————————————————————————————————————————
private val HeroSerifItalicBase = TextStyle(
    fontStyle = FontStyle.Italic,
    fontWeight = FontWeight.Medium,
    fontSize = 40.sp,
    lineHeight = 48.sp,
)

private val DisplayLargeBase = TextStyle(
    fontWeight = FontWeight.Normal,
    fontSize = 120.sp,
    lineHeight = 120.sp,
    letterSpacing = (-2).sp,
    color = TextPrimary,
)

private val DisplayMediumBase = TextStyle(
    fontWeight = FontWeight.Normal,
    fontSize = 72.sp,
    lineHeight = 72.sp,
    letterSpacing = (-1).sp,
    color = TextPrimary,
)

private val DisplaySmallBase = TextStyle(
    fontWeight = FontWeight.Normal,
    fontSize = 48.sp,
    lineHeight = 52.sp,
    color = TextPrimary,
)

private val HeadlineLargeBase = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 28.sp,
    lineHeight = 36.sp,
    color = TextPrimary,
)

private val HeadlineMediumBase = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 22.sp,
    lineHeight = 28.sp,
    color = TextPrimary,
)

private val TitleLargeBase = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 18.sp,
    lineHeight = 24.sp,
    color = TextPrimary,
)

private val TitleMediumBase = TextStyle(
    fontWeight = FontWeight.Medium,
    fontSize = 16.sp,
    lineHeight = 20.sp,
    color = TextPrimary,
)

private val BodyLargeBase = TextStyle(
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    lineHeight = 24.sp,
    color = TextPrimary,
)

private val BodyMediumBase = TextStyle(
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    color = TextSecondary,
)

private val LabelLargeBase = TextStyle(
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 16.sp,
    letterSpacing = 1.1.sp,
    color = TextSecondary,
)

private val LabelMediumBase = TextStyle(
    fontWeight = FontWeight.Medium,
    fontSize = 10.sp,
    lineHeight = 14.sp,
    letterSpacing = 1.0.sp,
    color = TextTertiary,
)

private val LabelSmallBase = TextStyle(
    fontWeight = FontWeight.Medium,
    fontSize = 9.sp,
    lineHeight = 12.sp,
    letterSpacing = 0.9.sp,
    color = TextTertiary,
)

// — Public Tokens —————————————————————————————————————————————————————————

/** Hero serif italic — used for gold display phrases. */
val HeroSerifItalic: TextStyle
    get() = HeroSerifItalicBase.copy(fontFamily = HeroSerif)

/** Preview-safe fallback using system serif. */
val HeroSerifItalicPreview: TextStyle
    get() = HeroSerifItalicBase.copy(fontFamily = FontFamily.Serif)

/**
 * Composable accessor for the monospace family — returns [FontFamily.Monospace] inside
 * @Preview (no Google Play Services in the AS sandbox → downloadable fonts stall) and
 * [DisplayMono] at runtime. Use this instead of referencing [DisplayMono] directly from
 * any composable that needs to render in previews.
 */
@Composable
@ReadOnlyComposable
fun displayMonoFamily(): FontFamily =
    if (LocalInspectionMode.current) FontFamily.Monospace else DisplayMono

/**
 * Composable accessor for the hero serif family — preview-safe analogue to [HeroSerif].
 * Returns [FontFamily.Serif] in @Preview, [HeroSerif] (Playfair Display) at runtime.
 */
@Composable
@ReadOnlyComposable
fun heroSerifFamily(): FontFamily =
    if (LocalInspectionMode.current) FontFamily.Serif else HeroSerif

/**
 * Main typography for the app. Uses downloadable Google Fonts.
 */
val SmartPumpTypography: Typography
    get() = Typography(
        displayLarge = DisplayLargeBase.copy(fontFamily = DisplayMono),
        displayMedium = DisplayMediumBase.copy(fontFamily = DisplayMono),
        displaySmall = DisplaySmallBase.copy(fontFamily = DisplayMono),
        headlineLarge = HeadlineLargeBase.copy(fontFamily = BodyFamily),
        headlineMedium = HeadlineMediumBase.copy(fontFamily = BodyFamily),
        titleLarge = TitleLargeBase.copy(fontFamily = BodyFamily),
        titleMedium = TitleMediumBase.copy(fontFamily = BodyFamily),
        bodyLarge = BodyLargeBase.copy(fontFamily = BodyFamily),
        bodyMedium = BodyMediumBase.copy(fontFamily = BodyFamily),
        labelLarge = LabelLargeBase.copy(fontFamily = BodyFamily),
        labelMedium = LabelMediumBase.copy(fontFamily = BodyFamily),
        labelSmall = LabelSmallBase.copy(fontFamily = BodyFamily),
    )

/**
 * Preview-time fallback using system fonts (Monospace, SansSerif).
 * This avoids touching the Google Fonts provider during initialization in the IDE.
 */
val SmartPumpTypographyPreview: Typography
    get() = Typography(
        displayLarge = DisplayLargeBase.copy(fontFamily = FontFamily.Monospace),
        displayMedium = DisplayMediumBase.copy(fontFamily = FontFamily.Monospace),
        displaySmall = DisplaySmallBase.copy(fontFamily = FontFamily.Monospace),
        headlineLarge = HeadlineLargeBase.copy(fontFamily = FontFamily.SansSerif),
        headlineMedium = HeadlineMediumBase.copy(fontFamily = FontFamily.SansSerif),
        titleLarge = TitleLargeBase.copy(fontFamily = FontFamily.SansSerif),
        titleMedium = TitleMediumBase.copy(fontFamily = FontFamily.SansSerif),
        bodyLarge = BodyLargeBase.copy(fontFamily = FontFamily.SansSerif),
        bodyMedium = BodyMediumBase.copy(fontFamily = FontFamily.SansSerif),
        labelLarge = LabelLargeBase.copy(fontFamily = FontFamily.SansSerif),
        labelMedium = LabelMediumBase.copy(fontFamily = FontFamily.SansSerif),
        labelSmall = LabelSmallBase.copy(fontFamily = FontFamily.SansSerif),
    )
