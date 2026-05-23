// Small selectable tile used on the unified ModeSelect screen for mode / amount / method
// rows. Selected = brand-blue fill + on-brand text; unselected = dark surface + subtle
// border. Disabled = greyed; renders but does not respond to taps.
//
// The tile takes a [content] slot so callers can compose icon + label + subtitle / badge
// however the section needs — mode tiles are tall with a leading icon, amount tiles are
// just a centered figure, method tiles are a wide horizontal row with an optional badge.
package app.balancee.smartpump.display.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.BrandBlue
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.Surface

@Composable
fun SelectableTile(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Dimensions.cornerCard)
    val bg = when {
        !enabled -> Surface
        selected -> BrandBlue
        else -> Surface
    }
    val border = when {
        !enabled -> BorderSubtle
        selected -> BrandBlue
        else -> BorderSubtle
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(Dimensions.borderWidth, border, shape)
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(contentPadding),
    ) {
        content()
    }
}

/** Convenience overload — when callers want a Column directly inside the tile. */
@Composable
fun SelectableTileColumn(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    SelectableTile(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = contentPadding,
    ) {
        androidx.compose.foundation.layout.Column(content = content)
    }
}

/** True/false → content text colour for any selectable tile. */
fun selectedTextColor(selected: Boolean, primary: Color, secondary: Color): Color =
    if (selected) primary else secondary
