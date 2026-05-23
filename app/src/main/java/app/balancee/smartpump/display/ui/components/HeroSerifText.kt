// Hero serif-italic phrase — gold, italic, oversized. Used for spec section headers
// like "Fixed amount, pay before fuel flows." and the cover "Every state. Every flow."
package app.balancee.smartpump.display.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.HeroSerifItalic
import app.balancee.smartpump.display.ui.theme.HeroSerifItalicPreview
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme

@Composable
fun HeroSerifText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = PrimaryGold,
    style: TextStyle? = null,
) {
    // In @Preview, swap to the system-serif fallback so AS doesn't crash on the
    // GoogleFont loader. At runtime, resolving to Playfair Display Italic.
    val resolvedStyle = style ?: if (LocalInspectionMode.current) {
        HeroSerifItalicPreview
    } else {
        HeroSerifItalic
    }
    Text(
        text = text,
        style = resolvedStyle.copy(color = color),
        modifier = modifier,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 720)
@Composable
private fun HeroSerifTextPreview() {
    SmartPumpDisplayTheme {
        Column(
            modifier = Modifier
                .background(Background)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroSerifText("Every state. Every flow.")
            HeroSerifText("Fixed amount, pay before fuel flows.")
            HeroSerifText("Fill it up.")
        }
    }
}
