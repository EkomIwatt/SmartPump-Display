// Design system color palette. "Industrial brutalism with warmth."
// Dark theme only — no light variant. Built for 600-nit direct-sunlight visibility.
package app.balancee.smartpump.display.ui.theme

import androidx.compose.ui.graphics.Color

// — Backgrounds & surfaces ————————————————————————————————————————————————
val Background = Color(0xFF0A0A0F)          // Near-black with slight blue tint
val Surface = Color(0xFF13131A)             // Cards, elevated elements
val SurfaceVariant = Color(0xFF1C1C26)      // Subtle secondary elevation
val BorderSubtle = Color(0xFF2A2A38)        // 1dp card borders — crisp, not shadow

// — Brand / action ————————————————————————————————————————————————————————
val PrimaryAmber = Color(0xFFF5A623)        // AUTHORISE button — the money colour
val OnPrimary = Color(0xFF0A0A0F)           // Dark text on amber buttons

// — Semantic states ———————————————————————————————————————————————————————
val ActiveCyan = Color(0xFF4FD1C5)          // Dispensing / filling — live state
val SuccessGreen = Color(0xFF48BB78)        // Payment confirmed, transaction complete
val WarningRed = Color(0xFFF56565)          // Error, relay fault, price not set

// — Typography ————————————————————————————————————————————————————————————
val TextPrimary = Color(0xFFF7F7F8)         // Main readable text
val TextSecondary = Color(0xFFA0A0AB)       // Labels, captions, unit suffixes ("L", "₦")
val TextTertiary = Color(0xFF5A5A6B)        // Disabled, greyed-out attendant buttons
