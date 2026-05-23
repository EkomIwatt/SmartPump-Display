// State-coloured card — 1dp border in the state colour, 12dp radius, 24dp internal padding.
// No shadows by design — the look is industrial / kiosk.
// [borderColor] is required; pass the colour from the docs/design-system.md state→border table.
package app.balancee.smartpump.display.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.SuccessGreen
import app.balancee.smartpump.display.ui.theme.Surface

@Composable
fun BalanceeCard(
    borderColor: Color,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(Dimensions.cardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Dimensions.cornerCard))
            .background(Surface)
            .border(
                width = Dimensions.borderWidth,
                color = borderColor,
                shape = RoundedCornerShape(Dimensions.cornerCard),
            )
            .padding(contentPadding),
        content = content,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 720)
@Composable
private fun BalanceeCardPreview() {
    SmartPumpDisplayTheme {
        Row(
            modifier = Modifier
                .background(Background)
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
        ) {
            BalanceeCard(
                borderColor = PrimaryGold,
                modifier = Modifier.weight(1f),
            ) {
                LabelText("QR Waiting")
            }
            BalanceeCard(
                borderColor = ActiveCyan,
                modifier = Modifier.weight(1f),
            ) {
                LabelText("Dispensing")
            }
            BalanceeCard(
                borderColor = SuccessGreen,
                modifier = Modifier.weight(1f),
            ) {
                LabelText("Complete")
            }
            BalanceeCard(
                borderColor = BorderSubtle,
                modifier = Modifier.weight(1f),
            ) {
                LabelText("Idle")
            }
        }
    }
}
