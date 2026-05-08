// Big Naira amount display — kobo in, formatted ₦#,### out. Uses displayLarge typography.
package app.balancee.smartpump.display.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import java.text.NumberFormat
import java.util.Locale

private val NgnFormat: NumberFormat = NumberFormat.getInstance(Locale.US)

@Composable
fun AmountDisplay(
    amountKobo: Long,
    modifier: Modifier = Modifier,
    label: String? = null,
    style: TextStyle = MaterialTheme.typography.displayLarge,
    color: Color = Color.Unspecified,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        label?.let { LabelText(it) }
        Text(
            text = "₦${NgnFormat.format(amountKobo / 100)}",
            style = style,
            color = color,
        )
    }
}
