// Layout constants for the industrial-kiosk design system.
// 32dp screen padding, 64dp buttons, 12dp card radius — all views in direct Nigerian sunlight.
package app.balancee.smartpump.display.ui.theme

import androidx.compose.ui.unit.dp

object Dimensions {

    // — Screen layout —————————————————————————————————————————————————————
    /** Default horizontal + vertical padding on every full-screen composable. */
    val screenPadding = 32.dp

    /** Internal padding inside every BalanceeCard. */
    val cardPadding = 24.dp

    /** Spacing between stacked cards or major sections. */
    val sectionSpacing = 20.dp

    /** Tight spacing between a label and its value within a card. */
    val itemSpacing = 8.dp

    /** Gap between the equal-width cards in a three-card-row flow layout. */
    val threeCardGap = 16.dp

    // — Corners ———————————————————————————————————————————————————————————
    val cornerCard = 12.dp
    val cornerButton = 8.dp
    val cornerChip = 6.dp
    val cornerCodePanel = 8.dp
    val cornerBadge = 4.dp

    // — Borders ———————————————————————————————————————————————————————————
    /** 1dp crisp border on cards — no shadows, industrial look. */
    val borderWidth = 1.dp

    // — Chips —————————————————————————————————————————————————————————————
    val chipPaddingHorizontal = 8.dp
    val chipPaddingVertical = 4.dp
    val chipDotSize = 6.dp

    // — Buttons ———————————————————————————————————————————————————————————
    /** Primary action buttons — 64dp minimum for fingers/gloves in daylight. */
    val buttonHeightPrimary = 64.dp

    /** Secondary / smaller action buttons. */
    val buttonHeightSecondary = 48.dp

    // — Icons —————————————————————————————————————————————————————————————
    val iconSizeNormal = 24.dp
    val iconSizeLarge = 48.dp

    // — Attendant overlay —————————————————————————————————————————————————
    /** Fraction of screen height that acts as swipe-up target zone. */
    const val attendantSwipeZoneFraction = 0.20f

    /** Height of the attendant overlay panel when fully revealed. */
    val attendantOverlayHeight = 320.dp
}
