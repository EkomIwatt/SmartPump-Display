// Giant monospace litre count, e.g. "3.42 L". The "L" suffix renders at ~40% of the
// number size in text-secondary, the number itself takes the state colour (cyan/green/gold).
// Used on dispensing, tank-full, and complete cards.
package app.balancee.smartpump.display.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.SuccessGreen
import app.balancee.smartpump.display.ui.theme.TextSecondary

@Composable
fun LitresDisplay(
    litres: Double,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayLarge,
    decimals: Int = 2,
) {
    val suffixSize = (style.fontSize.value * 0.4f).sp
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "%.${decimals}f".format(litres),
            style = style.copy(color = color),
        )
        Text(
            text = "L",
            style = style.copy(
                color = TextSecondary,
                fontSize = suffixSize,
                lineHeight = suffixSize,
            ),
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 480)
@Composable
private fun LitresDisplayPreview() {
    SmartPumpDisplayTheme {
        Column(
            modifier = Modifier
                .background(Background)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LitresDisplay(litres = 3.42, color = SuccessGreen)
            LitresDisplay(litres = 0.18, color = ActiveCyan)
            LitresDisplay(litres = 5.75, color = PrimaryGold)
        }
    }
}
