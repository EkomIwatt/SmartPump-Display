// Three button variants for the kiosk:
//  - Primary: gold background, dark text, 64dp tall — money / authorise actions inside flows.
//  - Secondary: transparent with border-subtle, text-primary label — neutral actions.
//  - Brand: brand-blue background, light text — Idle-screen "Start Transaction" and other brand CTAs.
// All render an all-caps label and disable to a tertiary-text outlined ghost.
package app.balancee.smartpump.display.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.BrandBlue
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.OnBrand
import app.balancee.smartpump.display.ui.theme.OnPrimary
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextTertiary
import java.util.Locale

enum class BalanceeButtonVariant { Primary, Secondary, Brand }

@Composable
fun BalanceeButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BalanceeButtonVariant = BalanceeButtonVariant.Primary,
    enabled: Boolean = true,
    accentColor: Color? = null,
) {
    val shape = RoundedCornerShape(Dimensions.cornerButton)
    val (bg, fg, borderColor) = when {
        !enabled -> Triple(Color.Transparent, TextTertiary, BorderSubtle)
        // Primary with accentColor: filled in the accent (used on receipt screens so the
        // "Share receipt" button takes the same gold/green as the card border).
        variant == BalanceeButtonVariant.Primary && accentColor != null ->
            Triple(accentColor, OnPrimary, accentColor)
        variant == BalanceeButtonVariant.Primary -> Triple(PrimaryGold, OnPrimary, PrimaryGold)
        variant == BalanceeButtonVariant.Brand -> Triple(BrandBlue, OnBrand, BrandBlue)
        // Secondary with accentColor: outline + label in the accent (used by
        // "Return to Idle" so it matches the receipt border).
        accentColor != null -> Triple(Color.Transparent, accentColor, accentColor)
        else -> Triple(Color.Transparent, TextPrimary, BorderSubtle)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(Dimensions.buttonHeightPrimary)
            .clip(shape)
            .background(bg)
            .border(Dimensions.borderWidth, borderColor, shape)
            .let { if (enabled) it.clickable(onClick = onClick) else it },
    ) {
        Text(
            text = label.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 480)
@Composable
private fun BalanceeButtonPreview() {
    SmartPumpDisplayTheme {
        Column(
            modifier = Modifier
                .background(Background)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BalanceeButton(label = "Authorise ₦5,000", onClick = {})
            BalanceeButton(
                label = "Start transaction",
                onClick = {},
                variant = BalanceeButtonVariant.Brand,
            )
            BalanceeButton(
                label = "Cancel transaction",
                onClick = {},
                variant = BalanceeButtonVariant.Secondary,
            )
            BalanceeButton(label = "Cash received", onClick = {}, enabled = false)
        }
    }
}
