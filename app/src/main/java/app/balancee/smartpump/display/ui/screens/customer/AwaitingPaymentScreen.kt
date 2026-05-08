// Customer is waiting for the payment processor to confirm. Method-specific UI:
//   BANK_QR / BALANCEE_APP → QR code with payload
//   NFC                   → "tap your card"
//   USSD                  → "dial *xxx#"
package app.balancee.smartpump.display.ui.screens.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.domain.model.DeviceConfig
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.ui.components.AmountDisplay
import app.balancee.smartpump.display.ui.components.BalanceeButtonSecondary
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.components.QrCodeView
import app.balancee.smartpump.display.ui.theme.Dimensions

@Composable
fun AwaitingPaymentScreen(
    config: DeviceConfig,
    amountKobo: Long,
    method: PaymentMethod,
    transactionRef: String,
    qrPayload: String?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimensions.sectionSpacing),
    ) {
        PumpHeader(config)
        Text(
            text = "Waiting for payment",
            style = MaterialTheme.typography.headlineLarge,
        )
        AmountDisplay(
            amountKobo = amountKobo,
            label = "Pay",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.displayMedium,
        )
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MethodInstructions(method = method, qrPayload = qrPayload)
        }
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.padding(top = 8.dp))
            LabelText("Txn $transactionRef")
        }
        Spacer(Modifier.weight(1f))
        BalanceeButtonSecondary(text = "CANCEL", onClick = onCancel)
    }
}

@Composable
private fun MethodInstructions(method: PaymentMethod, qrPayload: String?) {
    when (method) {
        PaymentMethod.BANK_QR, PaymentMethod.BALANCEE_APP -> {
            qrPayload?.let { QrCodeView(payload = it) }
                ?: Text(
                    "Generating QR…",
                    style = MaterialTheme.typography.bodyLarge,
                )
        }
        PaymentMethod.NFC -> Text(
            text = "Tap your card on the reader",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        PaymentMethod.USSD -> Text(
            text = "Dial the USSD code on your phone",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        PaymentMethod.CASH -> Text(
            text = "Pay attendant cash",
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
