// Flow 3, post-fill-up digital-payment wait. After the nozzle shuts (verified litres locked),
// the customer chose "Pay digitally". The QR is the **Paystack checkout URL** the backend returned
// for this sale's exact amount. Until 10d it was a `nip://transfer?…` payload this app fabricated
// against the operator's virtual account — well-formed, unscannable by any bank, and therefore
// unpayable; OQ #6 had retired the virtual account when payments moved to Paystack and this screen
// was the last thing still using it. Card stays gold (waiting) until the poll reports PAID — then
// it flips to Complete (green). On expiry, falls back to FillupAwaitingCashConfirm.
//
// Single gold card (like PrepayAwaitingPaymentScreen) with a QR | info split. Adopts the
// dispensing-family language: in-card StateChip + word header, an AMOUNT DUE hero, and the
// subtly state-tinted rounded ledger panel. The QR sits in a tinted inset box, also borrowed
// from the pre-pay QR screen.
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.components.AmountDisplay
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.LedgerRow
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.components.QrCodeView
import app.balancee.smartpump.display.ui.components.StateChip
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.SurfaceVariant
import app.balancee.smartpump.display.ui.theme.TextSecondary
import app.balancee.smartpump.display.ui.theme.TextTertiary
import app.balancee.smartpump.display.ui.util.formatNaira
import app.balancee.smartpump.display.ui.util.isPortrait

@Composable
fun FillupDigitalAwaitingPaymentScreen(
    txnId: String,
    verifiedLitres: Double,
    amountDueKobo: Long,
    priceKoboPerLitre: Long,
    qrContent: String,
    expiresInSeconds: Int,
    onCollectCashInstead: () -> Unit,
    modifier: Modifier = Modifier,
    pumpLabel: String = "Pump 1",
) {
    val accent = PrimaryGold

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PumpHeader(
            pumpLabel = pumpLabel,
            mode = "Fill up · digital",
            stateLabel = "Scan to pay",
            stateColor = accent,
        )

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
                // In-card header: state chip (left) + method word (right).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StateChip(label = "Scan to pay", color = accent)
                    LabelText(text = "Bank QR · card")
                }

                // Body: QR artefact + amount/ledger. Side-by-side on landscape, stacked portrait.
                if (isPortrait()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        QrPane(
                            qrContent = qrContent,
                            txnId = txnId,
                            accent = accent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                        InfoPane(
                            txnId = txnId,
                            verifiedLitres = verifiedLitres,
                            amountDueKobo = amountDueKobo,
                            priceKoboPerLitre = priceKoboPerLitre,
                            expiresInSeconds = expiresInSeconds,
                            accent = accent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        QrPane(
                            qrContent = qrContent,
                            txnId = txnId,
                            accent = accent,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                        InfoPane(
                            txnId = txnId,
                            verifiedLitres = verifiedLitres,
                            amountDueKobo = amountDueKobo,
                            priceKoboPerLitre = priceKoboPerLitre,
                            expiresInSeconds = expiresInSeconds,
                            accent = accent,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }

        Text(
            text = "QR opens a secure checkout for the exact fill-up amount — dynamic, per " +
                "transaction. Walk away and the screen falls back to cash collection.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        // Does what it says since #R13: the tank moves to cash collection. It used to drop the
        // sale to Idle and record nothing, with the fuel already gone.
        BalanceeButton(
            label = "Cancel · collect cash instead",
            onClick = onCollectCashInstead,
            variant = BalanceeButtonVariant.Secondary,
        )
    }
}

/**
 * No checkout URL: show the reference an attendant can quote, never an unpayable QR.
 *
 * Reachable when `/authorise` returned no `authorizationUrl`. It is not the old failure mode —
 * until 10d this screen rendered a fabricated `nip://transfer?…` payload that always looked fine
 * and could never be paid.
 */
@Composable
private fun UnavailableQrPrompt(txnId: String) {
    Column(
        modifier = Modifier.padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Payment code unavailable",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Ask the attendant, quoting $txnId",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

/** QR in a tinted inset box (borrowed from PrepayAwaitingPaymentScreen) + scan captions. */
@Composable
private fun QrPane(
    qrContent: String,
    txnId: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Dimensions.cornerCodePanel))
                .background(SurfaceVariant)
                .border(
                    Dimensions.borderWidth,
                    accent.copy(alpha = 0.35f),
                    RoundedCornerShape(Dimensions.cornerCodePanel),
                )
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qrContent.isBlank()) {
                // Never render a QR of nothing. Same rule as the pre-pay screen: a code that
                // cannot be paid is worse than no code, because the customer scans it and waits.
                UnavailableQrPrompt(txnId = txnId)
            } else {
                Box(modifier = Modifier.size(200.dp)) {
                    QrCodeView(content = qrContent, sizeDp = 200.dp)
                }
            }
        }
        Text(
            text = "Open any bank app · scan · confirm.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        Text(
            text = "GTBank · Opay · PalmPay · any bank",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** AMOUNT DUE hero + verified-litres sub-line + the state-tinted rounded ledger panel. */
@Composable
private fun InfoPane(
    txnId: String,
    verifiedLitres: Double,
    amountDueKobo: Long,
    priceKoboPerLitre: Long,
    expiresInSeconds: Int,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LabelText(text = "Exact amount due")
            AmountDisplay(
                amountKobo = amountDueKobo,
                color = accent,
                style = MaterialTheme.typography.displayMedium,
            )
            Text(
                text = "%.2f L · verified".format(verifiedLitres),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        Box(modifier = Modifier.weight(1f))

        // Ledger in a subtly state-tinted rounded panel — matches the dispensing family.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dimensions.cornerCodePanel))
                .background(accent.copy(alpha = 0.07f))
                .border(
                    Dimensions.borderWidth,
                    accent.copy(alpha = 0.30f),
                    RoundedCornerShape(Dimensions.cornerCodePanel),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LedgerRow(label = "Method", value = "Bank QR · NIP", valueMonospace = false)
            LedgerRow(label = "Price / L", value = formatNaira(priceKoboPerLitre))
            LedgerRow(label = "Txn", value = txnId)
            LedgerRow(label = "Expires in", value = formatExpiry(expiresInSeconds))
        }
    }
}

private fun formatExpiry(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun FillupDigitalAwaitingPaymentPreview() {
    SmartPumpDisplayTheme {
        FillupDigitalAwaitingPaymentScreen(
            txnId = "BLC-00921",
            verifiedLitres = 38.10,
            amountDueKobo = 3_316_605,
            priceKoboPerLitre = 87_050,
            qrContent = "https://checkout.paystack.com/jn0ej3u6def5150",
            expiresInSeconds = 287,
            onCollectCashInstead = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 600, heightDp = 1024)
@Composable
private fun FillupDigitalAwaitingPaymentPortraitPreview() {
    SmartPumpDisplayTheme {
        FillupDigitalAwaitingPaymentScreen(
            txnId = "BLC-00921",
            verifiedLitres = 38.10,
            amountDueKobo = 3_316_605,
            priceKoboPerLitre = 87_050,
            qrContent = "https://checkout.paystack.com/jn0ej3u6def5150",
            expiresInSeconds = 287,
            onCollectCashInstead = {},
        )
    }
}
