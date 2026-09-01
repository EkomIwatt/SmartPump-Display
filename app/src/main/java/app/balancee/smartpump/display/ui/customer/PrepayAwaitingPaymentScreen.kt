// Flow 1, step 3 — "QR WAITING" card. Customer has chosen amount + method;
// we now show a payment artefact (QR for BANK_QR_TRANSFER / BALANCEE_APP, instructions
// for NFC). A 5-min countdown ticks the expiry; the VM auto-cancels back to Idle when it
// hits zero. The Cancel button gives the customer an early-out.
//
// Layout rebuilt (2026-05-23) to match docs/compare/expected.png:
//   - Single centered card (was two side-by-side).
//   - Column header above the card.
//   - Card header row: amount · pump on the left, WAITING chip on the right.
//   - QR in a tinted inset box, method-specific alt caption underneath.
//   - Inline ledger (AMOUNT / TXN / EXPIRES) at the bottom of the card.
//   - Small explainer paragraph below the card.
//
// Chrome is method-aware. BALANCEE_APP routes through the Balanceè-app context per
// docs/design-system.md → brand-blue border + chip. Every other digital method stays
// on the documented WAITING gold (PrimaryGold).
package app.balancee.smartpump.display.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.QrCodeView
import app.balancee.smartpump.display.ui.components.StateChip
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.BrandBlue
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.displayMonoFamily
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.SurfaceVariant
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary
import app.balancee.smartpump.display.ui.theme.TextTertiary
import app.balancee.smartpump.display.ui.util.formatNaira
import app.balancee.smartpump.display.ui.util.isPortrait

@Composable
fun PrepayAwaitingPaymentScreen(
    amountKobo: Long,
    method: PaymentMethod,
    txnId: String,
    priceKoboPerLitre: Long,
    expiresInSeconds: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    pumpLabel: String = "Pump 1",
) {
    val accent = accentFor(method)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Column header above the card.
        LabelText(text = "QR Waiting", color = TextSecondary)

        // Card fills available vertical space; content splits left/right so the QR area
        // and the ledger sit side-by-side and the card never overflows on landscape.
        BalanceeCard(
            borderColor = accent,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Top row: amount · pump left, WAITING chip right — spans the full card width.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${formatNairaAmount(amountKobo)} · $pumpLabel",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = TextSecondary,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    StateChip(label = "Waiting", color = accent)
                }

                if (isPortrait()) {
                    // Portrait: stack the artefact over the ledger.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ArtifactPane(
                            method = method,
                            amountKobo = amountKobo,
                            txnId = txnId,
                            accent = accent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                        LedgerPane(
                            amountKobo = amountKobo,
                            txnId = txnId,
                            expiresInSeconds = expiresInSeconds,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    }
                } else {
                    // Landscape: artefact and ledger side-by-side.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        ArtifactPane(
                            method = method,
                            amountKobo = amountKobo,
                            txnId = txnId,
                            accent = accent,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                        LedgerPane(
                            amountKobo = amountKobo,
                            txnId = txnId,
                            expiresInSeconds = expiresInSeconds,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }

        Text(
            text = belowCardHint(method),
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        BalanceeButton(
            label = "Cancel transaction",
            onClick = onCancel,
            variant = BalanceeButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Artefact (QR / NFC prompt) with its label + method caption. Fills the modifier it's given. */
@Composable
private fun ArtifactPane(
    method: PaymentMethod,
    amountKobo: Long,
    txnId: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LabelText(text = artifactLabel(method), color = TextSecondary)
        PaymentArtifact(
            method = method,
            amountKobo = amountKobo,
            txnId = txnId,
            accent = accent,
        )
        Text(
            text = methodCaption(method),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** AMOUNT / TXN / EXPIRES ledger, divider-separated. Fills the modifier it's given. */
@Composable
private fun LedgerPane(
    amountKobo: Long,
    txnId: String,
    expiresInSeconds: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
    ) {
        HorizontalDivider(color = BorderSubtle)
        LedgerLine(
            label = "Amount",
            value = formatNairaAmount(amountKobo),
            modifier = Modifier.padding(vertical = 14.dp),
        )
        HorizontalDivider(color = BorderSubtle)
        LedgerLine(
            label = "Txn",
            value = txnId,
            modifier = Modifier.padding(vertical = 14.dp),
        )
        HorizontalDivider(color = BorderSubtle)
        LedgerLine(
            label = "Expires",
            value = formatCountdown(expiresInSeconds),
            modifier = Modifier.padding(vertical = 14.dp),
        )
        HorizontalDivider(color = BorderSubtle)
    }
}

/**
 * The "thing the customer interacts with" in the centre of the card. For BANK_QR_TRANSFER
 * and BALANCEE_APP this is a real QR. For NFC_CARD it's a tap-target prompt — no QR, since
 * NFC doesn't need a scanned artefact. USSD and CASH don't reach this screen (they're
 * routed to their own states by the VM) so they're not branched here; we fall through
 * to a QR with their payload as a safety net.
 */
@Composable
private fun PaymentArtifact(
    method: PaymentMethod,
    amountKobo: Long,
    txnId: String,
    accent: Color,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimensions.cornerCodePanel))
            .background(SurfaceVariant)
            .border(
                Dimensions.borderWidth,
                accent.copy(alpha = 0.35f),
                RoundedCornerShape(Dimensions.cornerCodePanel),
            )
            .padding(vertical = 20.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (method == PaymentMethod.NFC_CARD) {
            NfcTapPrompt(accent = accent)
        } else {
            Box(modifier = Modifier.width(180.dp)) {
                QrCodeView(
                    content = qrPayload(method, amountKobo, txnId),
                    sizeDp = 180.dp,
                )
            }
        }
    }
}

@Composable
private fun NfcTapPrompt(accent: Color) {
    Column(
        modifier = Modifier.padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "💳", // 💳 credit-card glyph — placeholder until the spec defines an NFC icon.
            fontSize = 72.sp,
        )
        Text(
            text = "Hold card here",
            style = MaterialTheme.typography.titleMedium.copy(
                color = accent,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun LedgerLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = TextPrimary,
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
                fontFamily = displayMonoFamily(),
                color = valueColor,
                letterSpacing = 0.5.sp,
            ),
        )
    }
}

/** Brand-blue when this is the Balanceè-app context, gold for everyone else (default WAITING). */
private fun accentFor(method: PaymentMethod): Color = when (method) {
    PaymentMethod.BALANCEE_APP -> BrandBlue
    else -> PrimaryGold
}

private fun qrPayload(method: PaymentMethod, amountKobo: Long, txnId: String): String {
    // Pre-pay amounts are whole naira; payloads carry the naira value.
    val naira = amountKobo / 100
    return when (method) {
        PaymentMethod.BANK_QR_TRANSFER ->
            "nip://balancee/$txnId?amount=$naira"
        PaymentMethod.BALANCEE_APP ->
            "balancee://pay?txn=$txnId&amount=$naira"
        PaymentMethod.USSD ->
            "ussd://*737*$naira*${txnId.takeLast(3)}#"
        PaymentMethod.NFC_CARD ->
            "nfc://tap/$txnId/$naira"
        PaymentMethod.CASH_SEE_ATTENDANT ->
            "cash://$txnId/$naira"
    }
}

private fun methodCaption(method: PaymentMethod): String = when (method) {
    PaymentMethod.BALANCEE_APP -> "or open Balanceè app"
    PaymentMethod.BANK_QR_TRANSFER -> "or use any bank app"
    PaymentMethod.USSD -> "or dial the code on your phone"
    PaymentMethod.NFC_CARD -> "Any contactless card or phone wallet"
    PaymentMethod.CASH_SEE_ATTENDANT -> "or hand cash to the attendant"
}

/** Centred label above the artefact — "Scan to pay" vs "Tap to pay". */
private fun artifactLabel(method: PaymentMethod): String = when (method) {
    PaymentMethod.NFC_CARD -> "Tap to pay"
    else -> "Scan to pay"
}

/** Small hint paragraph below the card. Tied to the artefact, not the QR. */
private fun belowCardHint(method: PaymentMethod): String = when (method) {
    PaymentMethod.NFC_CARD ->
        "Hold card to the reader. Pump opens on tap. 5-min wait then auto-cancel."
    else ->
        "QR shown. Payment expected. 5-min expiry then auto-cancel."
}

private fun formatNairaAmount(amountKobo: Long): String = formatNaira(amountKobo)

private fun formatCountdown(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun PrepayAwaitingPaymentBalanceeAppPreview() {
    SmartPumpDisplayTheme {
        PrepayAwaitingPaymentScreen(
            amountKobo = 500_000,
            method = PaymentMethod.BALANCEE_APP,
            txnId = "BLC-00847",
            priceKoboPerLitre = 87_000,
            expiresInSeconds = 277,
            onCancel = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun PrepayAwaitingPaymentBankQrPreview() {
    SmartPumpDisplayTheme {
        PrepayAwaitingPaymentScreen(
            amountKobo = 200_000,
            method = PaymentMethod.BANK_QR_TRANSFER,
            txnId = "BLC-00002",
            priceKoboPerLitre = 87_000,
            expiresInSeconds = 299,
            onCancel = {},
        )
    }
}
