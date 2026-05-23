// Flow 1, step 3 — gold "QR WAITING" card. Customer has chosen amount + method;
// we now show a payment artefact (QR for BANK_QR_TRANSFER / BALANCEE_APP, instructions
// for NFC). A 5-min countdown ticks the expiry; the VM auto-cancels back to Idle when it
// hits zero. The Cancel button gives the customer an early-out.
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
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.ui.components.AmountDisplay
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.LedgerRow
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.components.QrCodeView
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary

@Composable
fun PrepayAwaitingPaymentScreen(
    amountNaira: Int,
    method: PaymentMethod,
    txnId: String,
    pricePerLitre: Int,
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
            mode = "Pre-pay",
            stateLabel = "Waiting",
            stateColor = PrimaryGold,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
        ) {
            QrCard(
                amountNaira = amountNaira,
                method = method,
                txnId = txnId,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            InfoCard(
                amountNaira = amountNaira,
                method = method,
                txnId = txnId,
                pricePerLitre = pricePerLitre,
                expiresInSeconds = expiresInSeconds,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }

        BalanceeButton(
            label = "Cancel transaction",
            onClick = onCancel,
            variant = BalanceeButtonVariant.Secondary,
        )
    }
}

@Composable
private fun QrCard(
    amountNaira: Int,
    method: PaymentMethod,
    txnId: String,
    modifier: Modifier = Modifier,
) {
    BalanceeCard(
        borderColor = PrimaryGold,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LabelText(text = "Scan to pay", color = PrimaryGold)
            Box(modifier = Modifier.size(260.dp)) {
                QrCodeView(
                    content = qrPayload(method, amountNaira, txnId),
                    sizeDp = 220.dp,
                )
            }
            Text(
                text = methodCaption(method),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun InfoCard(
    amountNaira: Int,
    method: PaymentMethod,
    txnId: String,
    pricePerLitre: Int,
    expiresInSeconds: Int,
    modifier: Modifier = Modifier,
) {
    BalanceeCard(
        borderColor = PrimaryGold,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LabelText(text = "Amount due", color = PrimaryGold)
                AmountDisplay(
                    amountNaira = amountNaira,
                    color = PrimaryGold,
                    style = MaterialTheme.typography.displayMedium,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LedgerRow(label = "Method", value = methodLabel(method), valueMonospace = false)
                LedgerRow(label = "Price / L", value = "₦$pricePerLitre")
                LedgerRow(label = "Txn", value = txnId)
                LedgerRow(label = "Expires in", value = formatCountdown(expiresInSeconds))
            }
            Box(modifier = Modifier.weight(1f))
            Text(
                text = "Pump opens the moment payment confirms. " +
                    "Walk away before paying — the transaction auto-cancels.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}

private fun qrPayload(method: PaymentMethod, amountNaira: Int, txnId: String): String = when (method) {
    PaymentMethod.BANK_QR_TRANSFER ->
        "nip://balancee/$txnId?amount=$amountNaira"
    PaymentMethod.BALANCEE_APP ->
        "balancee://pay?txn=$txnId&amount=$amountNaira"
    PaymentMethod.USSD ->
        "ussd://*737*$amountNaira*${txnId.takeLast(3)}#"
    PaymentMethod.NFC_CARD ->
        "nfc://tap/$txnId/$amountNaira"
    PaymentMethod.CASH_SEE_ATTENDANT ->
        "cash://$txnId/$amountNaira"
}

private fun methodLabel(method: PaymentMethod): String = when (method) {
    PaymentMethod.BALANCEE_APP -> "Balanceè app"
    PaymentMethod.BANK_QR_TRANSFER -> "Bank QR · NIP"
    PaymentMethod.NFC_CARD -> "Tap card · NFC"
    PaymentMethod.USSD -> "USSD *737#"
    PaymentMethod.CASH_SEE_ATTENDANT -> "Cash"
}

private fun methodCaption(method: PaymentMethod): String = when (method) {
    PaymentMethod.BANK_QR_TRANSFER -> "Open any bank app · scan · confirm."
    PaymentMethod.BALANCEE_APP -> "Open Balanceè · scan · confirm."
    PaymentMethod.USSD -> "Dial the code on your phone. Bank SMS unlocks the pump."
    PaymentMethod.NFC_CARD -> "Tap your contactless card on the panel."
    PaymentMethod.CASH_SEE_ATTENDANT -> "Hand cash to the attendant."
}

private fun formatCountdown(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun PrepayAwaitingPaymentPreview() {
    SmartPumpDisplayTheme {
        PrepayAwaitingPaymentScreen(
            amountNaira = 5_000,
            method = PaymentMethod.BANK_QR_TRANSFER,
            txnId = "BLC-00847",
            pricePerLitre = 870,
            expiresInSeconds = 287,
            onCancel = {},
        )
    }
}
