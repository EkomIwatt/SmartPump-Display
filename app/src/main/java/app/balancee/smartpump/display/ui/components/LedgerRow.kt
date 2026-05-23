// Receipt / ledger row — left label (small all-caps), right value (mono or sans).
// Used on completion screens and the bottom block of post-fill cards.
package app.balancee.smartpump.display.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.displayMonoFamily
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextPrimary

@Composable
fun LedgerRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueMonospace: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LabelText(text = label)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = if (valueMonospace) displayMonoFamily() else FontFamily.Default,
            ),
            color = TextPrimary,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A)
@Composable
private fun LedgerRowPreview() {
    SmartPumpDisplayTheme {
        Column(
            modifier = Modifier
                .background(Background)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LedgerRow("Litres", "5.75 L")
            LedgerRow("Paid", "₦5,000")
            LedgerRow("Price / L", "₦870")
            LedgerRow("Txn", "BLC-00847")
            LedgerRow("Station", "Total Lekki Ph2", valueMonospace = false)
        }
    }
}
