// Flow 3, post-fill-up digital-payment wait. After the nozzle shuts (verified litres
// locked), the customer chose "Pay digitally". A dynamic NIP-transfer QR is generated
// encoding the station's virtual account, the exact amount due, and the transaction
// reference. Card stays gold (waiting) until the webhook confirms — then it flips to
// Complete (green). On 5-min expiry, falls back to FillupAwaitingCashConfirm.
package app.balancee.smartpump.display.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.components.AmountDisplay
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.LedgerRow
import app.balancee.smartpump.display.ui.components.LitresDisplay
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.components.QrCodeView
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.PrimaryAmber
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextSecondary

@Composable
fun FillupDigitalAwaitingPaymentScreen(
    txnId: String,
    verifiedLitres: Double,
    amountDueNaira: Int,
    pricePerLitre: Int,
    qrContent: String,
    expiresInSeconds: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    pumpId: String = "Pump 1",
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PumpHeader(
            pumpId = pumpId,
            mode = "Fill up · digital",
            stateLabel = "QR generated",
            stateColor = PrimaryAmber,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
        ) {
            QrCard(
                qrContent = qrContent,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            InfoCard(
                txnId = txnId,
                verifiedLitres = verifiedLitres,
                amountDueNaira = amountDueNaira,
                pricePerLitre = pricePerLitre,
                expiresInSeconds = expiresInSeconds,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }

        BalanceeButton(
            label = "Cancel · collect cash instead",
            onClick = onCancel,
            variant = BalanceeButtonVariant.Secondary,
        )
    }
}

@Composable
private fun QrCard(
    qrContent: String,
    modifier: Modifier = Modifier,
) {
    BalanceeCard(
        borderColor = PrimaryAmber,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LabelText(text = "Scan to pay", color = PrimaryAmber)
            Box(modifier = Modifier.size(260.dp)) {
                QrCodeView(
                    content = qrContent,
                    sizeDp = 220.dp,
                )
            }
            Text(
                text = "Open any bank app · scan · confirm.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "GTBank · Opay · PalmPay · any bank",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun InfoCard(
    txnId: String,
    verifiedLitres: Double,
    amountDueNaira: Int,
    pricePerLitre: Int,
    expiresInSeconds: Int,
    modifier: Modifier = Modifier,
) {
    BalanceeCard(
        borderColor = PrimaryAmber,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LabelText(text = "Exact amount due", color = PrimaryAmber)
                AmountDisplay(
                    amountNaira = amountDueNaira,
                    color = PrimaryAmber,
                    style = MaterialTheme.typography.displayMedium,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LitresDisplay(
                    litres = verifiedLitres,
                    color = PrimaryAmber,
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = "  · verified",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LedgerRow(label = "Method", value = "Bank QR · NIP", valueMonospace = false)
                LedgerRow(label = "Price / L", value = "₦$pricePerLitre")
                LedgerRow(label = "Txn", value = txnId)
                LedgerRow(label = "Expires in", value = formatExpiry(expiresInSeconds))
            }
            Box(modifier = Modifier.weight(1f))
            Text(
                text = "QR encodes the exact fill-up amount. Dynamic — changes per " +
                    "transaction. If the customer walks away, the screen falls back to " +
                    "cash collection.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}

private fun formatExpiry(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0F, widthDp = 1024, heightDp = 600)
@Composable
private fun FillupDigitalAwaitingPaymentPreview() {
    SmartPumpDisplayTheme {
        FillupDigitalAwaitingPaymentScreen(
            txnId = "BLC-00921",
            verifiedLitres = 38.10,
            amountDueNaira = 33_147,
            pricePerLitre = 870,
            qrContent = "nip://transfer?account=0123456789&amount=33147&ref=BLC-00921",
            expiresInSeconds = 287,
            onCancel = {},
        )
    }
}
