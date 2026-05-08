// Surface card with crisp 1dp border and 12dp radius — the base container for every
// information panel in the app. No shadow; industrial flat aesthetic.
package app.balancee.smartpump.display.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.balancee.smartpump.display.ui.theme.Dimensions

@Composable
fun BalanceeCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Dimensions.cornerCard),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Dimensions.borderWidth, MaterialTheme.colorScheme.outline),
    ) {
        Box(modifier = Modifier.padding(Dimensions.cardPadding)) {
            content()
        }
    }
}
