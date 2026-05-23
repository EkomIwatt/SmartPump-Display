// 3×4 monospace keypad for cash-amount entry (Flow 4) and custom pre-pay amount.
// Layout:
//   1 2 3
//   4 5 6
//   7 8 9
//   ⌫ 0 ✓
// Buttons are 64dp minimum so they remain reliable with gloves in daylight.
package app.balancee.smartpump.display.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.DisplayMono
import app.balancee.smartpump.display.ui.theme.BrandBlue
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.Surface
import app.balancee.smartpump.display.ui.theme.SurfaceVariant
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.WarningRed

private val KeyLabelStyle = TextStyle(
    fontFamily = DisplayMono,
    fontWeight = FontWeight.SemiBold,
    fontSize = 28.sp,
    lineHeight = 32.sp,
)

@Composable
fun NumericKeypad(
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    cellHeight: androidx.compose.ui.unit.Dp = Dimensions.buttonHeightPrimary,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            listOf(1, 2, 3),
            listOf(4, 5, 6),
            listOf(7, 8, 9),
        ).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { digit ->
                    DigitKey(
                        label = digit.toString(),
                        height = cellHeight,
                        modifier = Modifier.weight(1f),
                        onClick = { onDigit(digit) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DigitKey(
                label = "⌫",
                height = cellHeight,
                modifier = Modifier.weight(1f),
                onClick = onBackspace,
                accentColor = WarningRed,
            )
            DigitKey(
                label = "0",
                height = cellHeight,
                modifier = Modifier.weight(1f),
                onClick = { onDigit(0) },
            )
            DigitKey(
                label = "✓",
                height = cellHeight,
                modifier = Modifier.weight(1f),
                onClick = onConfirm,
                accentColor = BrandBlue,
                filled = true,
                enabled = confirmEnabled,
            )
        }
    }
}

@Composable
private fun DigitKey(
    label: String,
    height: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = BorderSubtle,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(Dimensions.cornerButton)
    val bg = when {
        !enabled -> SurfaceVariant
        filled -> accentColor
        else -> Surface
    }
    val fg = when {
        !enabled -> TextPrimary.copy(alpha = 0.3f)
        filled -> Color(0xFF0B0B0A)
        else -> TextPrimary
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(bg)
            .border(Dimensions.borderWidth, accentColor.copy(alpha = if (filled) 1f else 0.6f), shape)
            .let { if (enabled) it.clickable(onClick = onClick) else it },
    ) {
        Text(text = label, style = KeyLabelStyle.copy(color = fg))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 360, heightDp = 480)
@Composable
private fun NumericKeypadPreview() {
    SmartPumpDisplayTheme {
        Box(
            modifier = Modifier
                .background(Background)
                .padding(24.dp),
        ) {
            NumericKeypad(
                onDigit = {},
                onBackspace = {},
                onConfirm = {},
            )
        }
    }
}
