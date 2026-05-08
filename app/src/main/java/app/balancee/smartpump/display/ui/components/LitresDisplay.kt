// Big litres display — formatted to 2 decimal places with "L" unit suffix.
package app.balancee.smartpump.display.ui.components

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
import androidx.compose.ui.unit.dp

@Composable
fun LitresDisplay(
    litres: Double,
    modifier: Modifier = Modifier,
    label: String? = null,
    style: TextStyle = MaterialTheme.typography.displayLarge,
    color: Color = Color.Unspecified,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        label?.let { LabelText(it) }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "%.2f".format(litres),
                style = style,
                color = color,
            )
            Text(
                text = "L",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(start = 8.dp, bottom = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
