// Three equal-width phase cards laid out in a row with a 16dp gap.
// Used on flow overview screens (e.g. WAITING → CONFIRMED → COMPLETE).
// Callers supply three composable slots; this primitive just enforces the layout rules.
package app.balancee.smartpump.display.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.SuccessGreen

@Composable
fun ThreeCardRow(
    first: @Composable RowScope.() -> Unit,
    second: @Composable RowScope.() -> Unit,
    third: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
    ) {
        first()
        second()
        third()
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 360)
@Composable
private fun ThreeCardRowPreview() {
    SmartPumpDisplayTheme {
        ThreeCardRow(
            modifier = Modifier
                .background(Background)
                .padding(24.dp),
            first = {
                BalanceeCard(
                    borderColor = PrimaryGold,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    StateChip("Waiting", PrimaryGold)
                }
            },
            second = {
                BalanceeCard(
                    borderColor = ActiveCyan,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    StateChip("Dispensing", ActiveCyan)
                }
            },
            third = {
                BalanceeCard(
                    borderColor = SuccessGreen,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    StateChip("Complete", SuccessGreen)
                }
            },
        )
    }
}
