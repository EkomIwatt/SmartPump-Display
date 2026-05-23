// Flow 4, step 1 — attendant types a Naira amount; the system shows the live litres-cutoff
// (rounded DOWN per the cash-fixed invariant) and a big gold AUTHORISE CASH ₦X button.
// The keypad ✓ key authorises too — both paths fire onAuthorise(amountNaira).
// Phase 4 will swap the IdleScreen temp button for the attendant swipe-up overlay;
// this screen itself stays.
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.ui.components.AmountDisplay
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.LedgerRow
import app.balancee.smartpump.display.ui.components.LitresDisplay
import app.balancee.smartpump.display.ui.components.NumericKeypad
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.BrandBlue
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary

private const val CASH_MIN_NAIRA = 200
private const val CASH_MAX_NAIRA = 200_000

@Composable
fun CashFixedAmountEntryScreen(
    pricePerLitre: Int,
    onAuthorise: (Int) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    pumpId: String = "Pump 1",
) {
    var draft by rememberSaveable { mutableStateOf(0) }
    val valid = draft in CASH_MIN_NAIRA..CASH_MAX_NAIRA && pricePerLitre > 0
    val litresCutoff = if (pricePerLitre > 0) {
        Math.floor((draft.toDouble() / pricePerLitre) * 100.0) / 100.0
    } else 0.0

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PumpHeader(
            pumpId = pumpId,
            mode = "Cash · fixed",
            stateLabel = "Authorising",
            stateColor = ActiveCyan,
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Enter the cash amount",
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
            )
            Text(
                text = "System computes the litre cutoff and the pump cuts at exactly that " +
                    "amount. Round-down — never dispenses more than was paid.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
        ) {
            BalanceeCard(
                borderColor = BrandBlue,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LabelText(text = "Cash amount", color = ActiveCyan)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        AmountDisplay(
                            amountNaira = draft,
                            color = BrandBlue,
                            style = MaterialTheme.typography.displayMedium,
                        )
                    }

                    LabelText(text = "Litres cutoff", color = BrandBlue)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        LitresDisplay(
                            litres = litresCutoff,
                            color = BrandBlue,
                            style = MaterialTheme.typography.displaySmall,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LedgerRow(
                            label = "Price / L",
                            value = if (pricePerLitre > 0) "₦$pricePerLitre" else "—",
                        )
                        LedgerRow(
                            label = "Min · Max",
                            value = "₦${formatGrouped(CASH_MIN_NAIRA)} · " +
                                "₦${formatGrouped(CASH_MAX_NAIRA)}",
                        )
                    }
                    Box(modifier = Modifier.weight(1f))
                    BalanceeButton(
                        label = "Cancel",
                        onClick = onCancel,
                        variant = BalanceeButtonVariant.Secondary,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                NumericKeypad(
                    onDigit = { d ->
                        val next = draft * 10 + d
                        if (next <= CASH_MAX_NAIRA) draft = next
                    },
                    onBackspace = { draft /= 10 },
                    onConfirm = { if (valid) onAuthorise(draft) },
                    confirmEnabled = valid,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        BalanceeButton(
            label = if (draft > 0) "Authorise cash ₦${formatGrouped(draft)}" else "Authorise cash",
            onClick = { if (valid) onAuthorise(draft) },
            enabled = valid,
            variant = BalanceeButtonVariant.Brand,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatGrouped(value: Int): String =
    java.text.NumberFormat.getNumberInstance(java.util.Locale.UK).format(value)

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun CashFixedAmountEntryPreview() {
    SmartPumpDisplayTheme {
        CashFixedAmountEntryScreen(
            pricePerLitre = 870,
            onAuthorise = {},
            onCancel = {},
        )
    }
}
