    // Flow 2/3, step 0 — customer picked FILL UP from ModeSelect. This screen does three
// things at once:
//  1. Acknowledges the FILL UP choice and explains what will happen.
//  2. Lets the customer pre-declare how they'll pay *after* the tank is full
//     (Bank QR for Flow 3, or cash to attendant for Flow 2). The choice is captured
//     on the state via onSelectIntent so a power-cut resume preserves it; it stays
//     advisory because the customer can still change their mind at FillupTankFull.
//  3. Acts as the "Tell attendant to start" hand-off — the attendant authorises the
//     pump from the swipe-up overlay (FILL UP AUTHORISE), not from this screen.
//
// Phase 6d (2026-05-23) rebuild: matches `docs/compare/expected.png`. Replaces the
// older "Ask the attendant." wait card that lacked the post-fill payment chooser.
package app.balancee.smartpump.display.ui.customer

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.balancee.smartpump.display.domain.model.PostFillIntent
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.HeroSerifText
import app.balancee.smartpump.display.ui.components.SelectableTile
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.BrandBlue
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.heroSerifFamily
import app.balancee.smartpump.display.ui.theme.OnBrand
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary
import app.balancee.smartpump.display.ui.theme.TextTertiary

@Composable
fun FillupAwaitingAttendantAuthScreen(
    intent: PostFillIntent?,
    displayName: String,
    logoBytes: ByteArray?,
    onSelectIntent: (PostFillIntent) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Local-only "I've signaled the attendant" feedback. Doesn't change state — the
    // attendant authorises from the swipe-up overlay, not from this screen — but
    // gives the customer a visible response to their tap so the CTA feels alive.
    // Not persisted: a reboot mid-wait just re-shows the active CTA. The attendant
    // can still authorise either way.
    var toldAttendant by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        HeaderRow(displayName = displayName, logoBytes = logoBytes)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            BalanceeCard(
                borderColor = ActiveCyan,
                modifier = Modifier
                    .sizeIn(maxWidth = 640.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CardTopRow(onBack = onCancel)

                    Text(
                        text = "🚗", // 🚗
                        fontSize = 44.sp,
                    )

                    HeroSerifText(
                        text = "Fill it up.",
                        color = ActiveCyan,
                    )

                    Text(
                        text = "Pump runs until your tank is full. Pay the verified " +
                            "amount after.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )

                    HorizontalDivider(
                        color = BorderSubtle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )

                    Text(
                        text = "HOW WILL YOU PAY AFTER?",
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = TextSecondary,
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )

                    IntentTile(
                        selected = intent == PostFillIntent.BANK_QR,
                        glyph = "▦", // ▦
                        glyphColor = ActiveCyan,
                        title = "Bank QR",
                        subtitle = "Generated after fill-up",
                        onClick = {
                            toldAttendant = false
                            onSelectIntent(PostFillIntent.BANK_QR)
                        },
                    )
                    IntentTile(
                        selected = intent == PostFillIntent.CASH_TO_ATTENDANT,
                        glyph = "₦", // ₦
                        glyphColor = ActiveCyan,
                        title = "Cash to attendant",
                        subtitle = "Hand cash after the nozzle shuts",
                        onClick = {
                            toldAttendant = false
                            onSelectIntent(PostFillIntent.CASH_TO_ATTENDANT)
                        },
                    )

                    BalanceeButton(
                        label = if (toldAttendant) "Waiting for attendant…" else "Tell attendant to start",
                        onClick = { if (intent != null) toldAttendant = true },
                        variant = BalanceeButtonVariant.Primary,
                        accentColor = ActiveCyan,
                        enabled = intent != null && !toldAttendant,
                    )

                    Text(
                        text = "Attendant taps FILL UP AUTHORISE on this screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        BalanceeButton(
            label = "Cancel",
            onClick = onCancel,
            variant = BalanceeButtonVariant.Secondary,
        )
    }
}

@Composable
private fun HeaderRow(
    displayName: String,
    logoBytes: ByteArray?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val bitmap = remember(logoBytes) {
            logoBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "$displayName logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(36.dp),
            )
        } else {
            HeroSerifText(
                text = displayName.ifBlank { "Smart pump" },
                color = BrandBlue,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontStyle = FontStyle.Italic,
                    fontFamily = heroSerifFamily(),
                ),
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Dimensions.cornerChip))
                .background(ActiveCyan.copy(alpha = 0.12f))
                .border(Dimensions.borderWidth, ActiveCyan, RoundedCornerShape(Dimensions.cornerChip))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = "FILL UP",
                style = MaterialTheme.typography.labelLarge.copy(
                    color = ActiveCyan,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                ),
            )
        }
    }
}

@Composable
private fun CardTopRow(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Back",
            style = MaterialTheme.typography.labelLarge.copy(
                color = TextSecondary,
                letterSpacing = 1.5.sp,
            ),
            modifier = Modifier
                .clip(RoundedCornerShape(Dimensions.cornerChip))
                .clickable(onClick = onBack)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun IntentTile(
    selected: Boolean,
    glyph: String,
    glyphColor: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    SelectableTile(
        selected = selected,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        contentPadding = 16.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = glyph,
                    fontSize = 22.sp,
                    color = if (selected) OnBrand else glyphColor,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = if (selected) OnBrand else TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = if (selected) OnBrand.copy(alpha = 0.75f) else TextSecondary,
                    ),
                )
            }
            Spacer(Modifier.weight(1f))
            // Trailing dot — open ring when unselected, filled when selected. Same affordance
            // as the method tiles on ModeSelect so customers learn one pattern.
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (selected) OnBrand else BorderSubtle),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 720)
@Composable
private fun FillupAwaitingAttendantAuthPreview() {
    SmartPumpDisplayTheme {
        FillupAwaitingAttendantAuthScreen(
            intent = null,
            displayName = "Total Lekki Ph2",
            logoBytes = null,
            onSelectIntent = {},
            onCancel = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 720)
@Composable
private fun FillupAwaitingAttendantAuthSelectedPreview() {
    SmartPumpDisplayTheme {
        FillupAwaitingAttendantAuthScreen(
            intent = PostFillIntent.BANK_QR,
            displayName = "Total Lekki Ph2",
            logoBytes = null,
            onSelectIntent = {},
            onCancel = {},
        )
    }
}
