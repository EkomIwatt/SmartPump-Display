// Giant monospace Naira amount, e.g. "₦33,147". The ₦ prefix renders at ~50% of the
// number size in text-secondary; the figure itself takes the state colour (gold/green/cyan).
// Comma-grouping is enabled by default — disable [groupThousands] for raw layouts.
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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.SuccessGreen
import app.balancee.smartpump.display.ui.theme.TextSecondary
import java.text.NumberFormat
import java.util.Locale

@Composable
fun AmountDisplay(
    amountNaira: Int,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayLarge,
    groupThousands: Boolean = true,
) {
    val prefixSize = (style.fontSize.value * 0.5f).sp
    val figure = if (groupThousands) {
        NumberFormat.getNumberInstance(Locale.UK).format(amountNaira)
    } else {
        amountNaira.toString()
    }
    // Restore font padding + a Both line-height alignment so descenders (the comma in
    // "2,000") have room — without these, the comma reads as a blank gap in tight tiles.
    val figureStyle = style.copy(
        color = color,
        platformStyle = PlatformTextStyle(includeFontPadding = true),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "₦", // ₦
            style = style.copy(
                color = TextSecondary,
                fontSize = prefixSize,
                lineHeight = prefixSize,
            ),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = figure,
            style = figureStyle,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 720)
@Composable
private fun AmountDisplayPreview() {
    SmartPumpDisplayTheme {
        Column(
            modifier = Modifier
                .background(Background)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AmountDisplay(amountNaira = 5_000, color = PrimaryGold)
            AmountDisplay(amountNaira = 33_147, color = SuccessGreen)
            AmountDisplay(amountNaira = 2_767, color = ActiveCyan)
        }
    }
}
