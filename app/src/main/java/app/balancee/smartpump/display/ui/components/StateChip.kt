// State pill — leading 6dp dot, all-caps label, 15% alpha fill, 1dp outline.
// The [color] is the canonical state colour from docs/design-system.md state→border table.
// Use at the top corner of a [BalanceeCard] to label its phase (WAITING / CONFIRMED / DISPENSING …).
package app.balancee.smartpump.display.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.SuccessGreen
import java.util.Locale

@Composable
fun StateChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(Dimensions.cornerChip))
            .background(color.copy(alpha = 0.15f))
            .border(Dimensions.borderWidth, color, RoundedCornerShape(Dimensions.cornerChip))
            .padding(
                horizontal = Dimensions.chipPaddingHorizontal,
                vertical = Dimensions.chipPaddingVertical,
            ),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(Dimensions.chipDotSize)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A)
@Composable
private fun StateChipPreview() {
    SmartPumpDisplayTheme {
        Column(
            modifier = Modifier
                .background(Background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StateChip("Waiting", PrimaryGold)
            StateChip("Dispensing", ActiveCyan)
            StateChip("Confirmed", SuccessGreen)
        }
    }
}
