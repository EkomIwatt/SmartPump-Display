// Flow 5, USSD code + SMS wait. Customer dials the per-bank USSD on their 2G phone;
// the bank debits and sends an SMS to the pump unit's SIM. Real path: a SIM-side
// BroadcastReceiver parses the SMS and matches the txn ref against this state. The
// mock path (Phase 3f) uses the existing MockPaymentProcessor: Success on the USSD
// channel stands in for "SMS received and parsed". Phase 4's debug screen will add
// a manual SMS injector for testing parser variants.
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.components.AmountDisplay
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.CodePanel
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.LedgerRow
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.DisplayMono
import app.balancee.smartpump.display.ui.theme.PrimaryAmber
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary

@Composable
fun UssdAwaitingSmsScreen(
    amountNaira: Int,
    txnRef: String,
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
            mode = "USSD · offline",
            stateLabel = "Awaiting SMS",
            stateColor = PrimaryAmber,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
        ) {
            DialCodeCard(
                amountNaira = amountNaira,
                txnRef = txnRef,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            WaitingCard(
                amountNaira = amountNaira,
                txnRef = txnRef,
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
private fun DialCodeCard(
    amountNaira: Int,
    txnRef: String,
    modifier: Modifier = Modifier,
) {
    BalanceeCard(
        borderColor = PrimaryAmber,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LabelText(text = "Dial this code", color = PrimaryAmber)
            Text(
                text = gtBankCode(amountNaira, txnRef),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = DisplayMono,
                    color = PrimaryAmber,
                ),
            )
            Text(
                text = "Primary — GTBank. Other banks below.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            CodePanel(
                text = listOf(
                    "GTBank   *737*$amountNaira*$txnRef#",
                    "Access   *901*$amountNaira*$txnRef#",
                    "Zenith   *966*$amountNaira*$txnRef#",
                    "UBA      *919*$amountNaira*$txnRef#",
                ).joinToString("\n"),
                modifier = Modifier.fillMaxWidth(),
            )
            Box(modifier = Modifier.weight(1f))
            Text(
                text = "Works on any phone — including 2G. No data required.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun WaitingCard(
    amountNaira: Int,
    txnRef: String,
    txnId: String,
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
                LabelText(text = "Waiting for SMS confirmation", color = PrimaryAmber)
                AmountDisplay(
                    amountNaira = amountNaira,
                    color = PrimaryAmber,
                    style = MaterialTheme.typography.displayMedium,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LedgerRow(label = "Ref", value = txnRef)
                LedgerRow(label = "Price / L", value = "₦$pricePerLitre")
                LedgerRow(label = "Txn", value = txnId)
                LedgerRow(label = "Sim status", value = "MTN · signal OK", valueMonospace = false)
                LedgerRow(label = "Expires in", value = formatCountdown(expiresInSeconds))
            }
            Box(modifier = Modifier.weight(1f))
            Text(
                text = "Bank sends an SMS after the USSD completes — usually 10–30 seconds. " +
                    "The pump listens to its SIM for an inbox match against this reference, " +
                    "then unlocks automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}

private fun gtBankCode(amountNaira: Int, txnRef: String): String =
    "*737*$amountNaira*$txnRef#"

private fun formatCountdown(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0F, widthDp = 1024, heightDp = 600)
@Composable
private fun UssdAwaitingSmsPreview() {
    SmartPumpDisplayTheme {
        UssdAwaitingSmsScreen(
            amountNaira = 5_000,
            txnRef = "847",
            txnId = "BLC-00847",
            pricePerLitre = 870,
            expiresInSeconds = 287,
            onCancel = {},
        )
    }
}
