// PIN entry modal for the attendant overlay.
//
// Behaviour:
//  - Renders as a scrim + centered card; the caller is responsible for showing/hiding it.
//  - Auto-submits on the 4th digit. The ✓ key on the keypad is also wired but redundant.
//  - Silent retry on wrong PIN: dots shake horizontally for ~300ms and clear. No message,
//    no lockout counter (V1 — operator carries the only PIN). V2 may add throttling.
//  - The customer-facing "Cancel" pill leaves the panel up but closes the modal — the
//    attendant can re-tap an action to retry, or swipe down to dismiss the whole overlay.
package app.balancee.smartpump.display.ui.attendant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.NumericKeypad
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BrandBlue
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary
import app.balancee.smartpump.display.ui.theme.TextTertiary
import kotlinx.coroutines.launch

private const val PIN_LENGTH = 4

@Composable
fun PinEntryModal(
    title: String,
    onVerify: suspend (pin: String) -> Boolean,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var typed by remember { mutableStateOf("") }
    var inFlight by remember { mutableStateOf(false) }
    val shakeOffset = remember { Animatable(0f) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Auto-submit when the 4th digit is entered.
    LaunchedEffect(typed) {
        if (typed.length == PIN_LENGTH && !inFlight) {
            inFlight = true
            val ok = onVerify(typed)
            inFlight = false
            if (ok) {
                onSuccess()
            } else {
                // Shake the dot row, then clear. Silent — no error text.
                val amplitude = with(density) { 10.dp.toPx() }
                shakeOffset.snapTo(0f)
                shakeOffset.animateTo(-amplitude, tween(60))
                shakeOffset.animateTo(amplitude, tween(60))
                shakeOffset.animateTo(-amplitude, tween(60))
                shakeOffset.animateTo(amplitude, tween(60))
                shakeOffset.animateTo(0f, tween(60))
                typed = ""
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Intercept taps on the card itself so they don't bubble up to the scrim.
        BalanceeCard(
            borderColor = BrandBlue,
            modifier = Modifier
                .sizeIn(maxWidth = 480.dp)
                .padding(horizontal = 24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    LabelText(text = "Attendant · PIN")
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                    )
                }
                Box(
                    modifier = Modifier
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "CANCEL",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Four dots — filled = typed, hollow = empty.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            ) {
                repeat(PIN_LENGTH) { index ->
                    val filled = index < typed.length
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(if (filled) BrandBlue else TextTertiary.copy(alpha = 0.4f)),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Wrap the keypad in a Box so we can apply the horizontal shake without
            // affecting the rest of the card layout.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                NumericKeypad(
                    onDigit = { digit ->
                        if (typed.length < PIN_LENGTH && !inFlight) {
                            typed = typed + digit.toString()
                        }
                    },
                    onBackspace = {
                        if (typed.isNotEmpty() && !inFlight) typed = typed.dropLast(1)
                    },
                    onConfirm = {
                        // Manual submit — auto-submit already covers the 4-digit case;
                        // this fires the verify pass only if it hasn't already.
                        if (typed.length == PIN_LENGTH && !inFlight) {
                            scope.launch {
                                inFlight = true
                                val ok = onVerify(typed)
                                inFlight = false
                                if (ok) onSuccess() else typed = ""
                            }
                        }
                    },
                    confirmEnabled = typed.length == PIN_LENGTH && !inFlight,
                    modifier = Modifier.graphicsLayer(translationX = shakeOffset.value),
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun PinEntryModalPreview() {
    SmartPumpDisplayTheme {
        Box(modifier = Modifier.fillMaxSize().background(Background)) {
            PinEntryModal(
                title = "Authorise FILL UP",
                onVerify = { true },
                onSuccess = {},
                onCancel = {},
            )
        }
    }
}
