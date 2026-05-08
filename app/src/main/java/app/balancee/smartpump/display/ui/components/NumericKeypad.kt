// 4×3 numeric keypad for amount entry. Bottom row is C / 0 / ⌫.
// Fixed 72 dp button height; spacing matches Dimensions.itemSpacing scaled up.
package app.balancee.smartpump.display.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.theme.Dimensions

@Composable
fun NumericKeypad(
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = listOf(
        listOf(KeyType.Digit(1), KeyType.Digit(2), KeyType.Digit(3)),
        listOf(KeyType.Digit(4), KeyType.Digit(5), KeyType.Digit(6)),
        listOf(KeyType.Digit(7), KeyType.Digit(8), KeyType.Digit(9)),
        listOf(KeyType.Clear, KeyType.Digit(0), KeyType.Backspace),
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { key ->
                    OutlinedButton(
                        onClick = {
                            when (key) {
                                is KeyType.Digit -> onDigit(key.value)
                                KeyType.Backspace -> onBackspace()
                                KeyType.Clear -> onClear()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(72.dp),
                        shape = RoundedCornerShape(Dimensions.cornerButton),
                    ) {
                        Text(
                            text = when (key) {
                                is KeyType.Digit -> key.value.toString()
                                KeyType.Backspace -> "⌫"
                                KeyType.Clear -> "C"
                            },
                            style = MaterialTheme.typography.headlineLarge,
                        )
                    }
                }
            }
        }
    }
}

private sealed interface KeyType {
    data class Digit(val value: Int) : KeyType
    data object Backspace : KeyType
    data object Clear : KeyType
}
