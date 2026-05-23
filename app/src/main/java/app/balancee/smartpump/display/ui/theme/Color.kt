// Design system color palette. "Industrial brutalism with warmth."
// Dark theme only — no light variant. Built for 600-nit direct-sunlight visibility.
//
// Phase 5d (2026-05-23) refresh — boss-specified palette pivot. Gold shifts from
// fintech orange (#F5A623) to muted brass (#C8A84B); brand blue deepens
// (#1B3FB8 → #1034A6); background warms a touch (#0A0A0F → #0B0B0A); text drops
// to a warmer off-white (#F7F7F8 → #E8E4DC). Orange (#D4622A) is reserved in the
// spec but not used anywhere yet.
package app.balancee.smartpump.display.ui.theme

import androidx.compose.ui.graphics.Color

// — Backgrounds & surfaces ————————————————————————————————————————————————
val Background = Color(0xFF0B0B0A)          // Near-black, warm
val Surface = Color(0xFF13131A)             // Cards, elevated elements
val SurfaceVariant = Color(0xFF1C1C26)      // Subtle secondary elevation
val CodePanelSurface = Color(0xFF0F0F16)    // Dark code/log blocks (debug, spec)
val BorderSubtle = Color(0xFF2A2A38)        // 1dp card borders — crisp, not shadow

// — Brand / action ————————————————————————————————————————————————————————
val PrimaryGold = Color(0xFFC8A84B)         // AUTHORISE / cash / waiting — muted brass
val OnPrimary = Color(0xFF0B0B0A)           // Dark text on gold buttons
val BrandBlue = Color(0xFF1034A6)           // Balanceè brand — Idle screen surface, brand-CTA buttons, spec cover
val OnBrand = Color(0xFFE8E4DC)             // Light text on brand-blue buttons

// — Semantic states ———————————————————————————————————————————————————————
val ActiveCyan = Color(0xFF4FD1C5)          // Fill-up dispensing — live count
val SuccessGreen = Color(0xFF3AAA6A)        // Confirmed / paid / complete
val WarningRed = Color(0xFFF56565)          // Error, relay fault, price not set

// Reserved per Phase 5d spec — no current callsite. Wire on demand.
val AccentOrange = Color(0xFFD4622A)

// — Typography ————————————————————————————————————————————————————————————
val TextPrimary = Color(0xFFE8E4DC)         // Main readable text — warm off-white
val TextSecondary = Color(0xFFA09C94)       // Labels, captions, unit suffixes ("L", "₦")
val TextTertiary = Color(0xFF5A5A6B)        // Disabled, greyed-out attendant buttons
