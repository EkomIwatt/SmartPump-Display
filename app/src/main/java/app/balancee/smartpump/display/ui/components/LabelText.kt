// Small all-caps tracked label — used for field names, section headers, ledger rows.
// Color defaults to text-secondary; pass [color] for the muted-tertiary or state-color variants.
package app.balancee.smartpump.display.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun LabelText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextSecondary,
) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = modifier,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0F)
@Composable
private fun LabelTextPreview() {
    SmartPumpDisplayTheme {
        Column(
            modifier = Modifier
                .background(Background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LabelText("Litres dispensed")
            LabelText("Price / L")
            LabelText("Txn")
        }
    }
}
