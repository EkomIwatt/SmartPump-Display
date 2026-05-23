// Default attract-state screen. Customer taps "Start transaction" to enter the flow.
// Attendant actions live behind the swipe-up overlay (AttendantOverlayHost, Phase 4).
//
// Branding: Phase 5c stripped the hard-coded "Balanceè" wordmark in favour of the
// station's own identity. If the operator uploaded a logo during onboarding, the logo
// renders; otherwise the station's display name is shown in the hero-serif style. The
// brand-blue card border + brand-blue "Start transaction" CTA stay — that's the
// Balanceè-product chrome, not the station's chrome.
package app.balancee.smartpump.display.ui.customer

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.HeroSerifText
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.BrandBlue
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextSecondary

@Composable
fun IdleScreen(
    onStartTransaction: () -> Unit,
    displayName: String,
    logoBytes: ByteArray?,
    modifier: Modifier = Modifier,
    pumpId: String = "Pump 1",
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(Dimensions.screenPadding),
    ) {
        PumpHeader(
            pumpId = pumpId,
            mode = "Idle",
            stateLabel = "Idle",
            stateColor = BorderSubtle,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        BalanceeCard(
            borderColor = BrandBlue,
            modifier = Modifier
                .align(Alignment.Center)
                .sizeIn(maxWidth = 520.dp),
        ) {
            Column(
                modifier = Modifier.padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                StationBranding(
                    displayName = displayName,
                    logoBytes = logoBytes,
                )
                Text(
                    text = "Smart pump · pay any way",
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                    textAlign = TextAlign.Center,
                )
                LabelText(text = "Tap to fuel")
                BalanceeButton(
                    label = "Start transaction",
                    onClick = onStartTransaction,
                    variant = BalanceeButtonVariant.Brand,
                    modifier = Modifier.width(360.dp),
                )
                Text(
                    text = "Attendant? Swipe up from the bottom edge.",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StationBranding(
    displayName: String,
    logoBytes: ByteArray?,
) {
    // Decode once per logoBytes change. The bytes were already capped at 512px during
    // onboarding so the main-thread decode here is cheap (≈100–300 KB PNG).
    val bitmap = remember(logoBytes) {
        logoBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "$displayName logo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .sizeIn(maxWidth = 320.dp, maxHeight = 160.dp)
                .padding(vertical = 8.dp),
        )
    } else {
        HeroSerifText(
            text = displayName.ifBlank { "Smart pump" },
            color = BrandBlue,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0F, widthDp = 1024, heightDp = 600)
@Composable
private fun IdleScreenPreview() {
    SmartPumpDisplayTheme {
        IdleScreen(
            onStartTransaction = {},
            displayName = "Total Lekki Ph2",
            logoBytes = null,
        )
    }
}
