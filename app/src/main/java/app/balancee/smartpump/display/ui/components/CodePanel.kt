// Dark monospace panel for inline payload / log blocks (debug + spec pages).
// Not customer-facing in production — used to show webhook JSON, SMS examples,
// device-config previews.
package app.balancee.smartpump.display.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.CodePanelSurface
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.DisplayMono
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextSecondary

private val CodeTextStyle = TextStyle(
    fontFamily = DisplayMono,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    color = TextSecondary,
)

@Composable
fun CodePanel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = CodeTextStyle,
        modifier = modifier
            .clip(RoundedCornerShape(Dimensions.cornerCodePanel))
            .background(CodePanelSurface)
            .padding(16.dp),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 720)
@Composable
private fun CodePanelPreview() {
    SmartPumpDisplayTheme {
        Column(
            modifier = Modifier
                .background(Background)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CodePanel(
                text = """
                    POST /pump/authorise
                    {
                      "mode": "fixed",
                      "amount_naira": 5000,
                      "litres_authorised": 5.75,
                      "transaction_id": "BLC-00847",
                      "nozzle_id": "1",
                      "price_per_litre": 870
                    }
                """.trimIndent(),
            )
        }
    }
}
