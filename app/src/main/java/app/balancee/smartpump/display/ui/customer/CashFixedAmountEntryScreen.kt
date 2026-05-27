// Flow 4, step 1 — attendant types a cash amount; the system shows the live litres-cutoff
// (floored per the cash-fixed invariant) and a big gold AUTHORISE CASH ₦X button.
//
// Phase 6c (strict-design polish vs Screenshot 2026-05-11 225053.png): adopts the
// dispensing-family card language — in-card StateChip + label header row, and the ledger
// (Price / L · Litres cutoff · Min–Max) sits in the subtly state-tinted rounded panel the
// other screens use. The keypad gains a decimal key (bottom-left), so the attendant can
// enter a sub-naira amount; commit is the bottom AUTHORISE button. Money is kobo: the typed
// naira value (which may carry kobo) is parsed and authorised as kobo.
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BrandBlue
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary
import app.balancee.smartpump.display.ui.util.appendDecimal
import app.balancee.smartpump.display.ui.util.appendDigit
import app.balancee.smartpump.display.ui.util.formatNaira
import app.balancee.smartpump.display.ui.util.isPortrait
import kotlin.math.roundToLong

private const val CASH_MIN_NAIRA = 200
private const val CASH_MAX_NAIRA = 200_000

@Composable
fun CashFixedAmountEntryScreen(
    priceKoboPerLitre: Long,
    onAuthorise: (Long) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    pumpId: String = "Pump 1",
) {
    // Typed as a String so it can hold an in-progress decimal point; parsed to kobo on use.
    var typed by rememberSaveable { mutableStateOf("") }
    val typedNaira = typed.toDoubleOrNull()
    val amountKobo = typedNaira?.let { (it * 100).roundToLong() } ?: 0L
    val valid = typedNaira != null &&
        typedNaira >= CASH_MIN_NAIRA && typedNaira <= CASH_MAX_NAIRA &&
        priceKoboPerLitre > 0L
    val litresCutoff = if (priceKoboPerLitre > 0L && amountKobo > 0L) {
        Math.floor((amountKobo.toDouble() / priceKoboPerLitre) * 100.0) / 100.0
    } else 0.0
    val accent = BrandBlue

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

        // Two panes — the amount/summary card and the keypad. Side-by-side in landscape,
        // stacked (amount on top, keypad below) in portrait.
        val amountCard: @Composable (Modifier) -> Unit = { paneModifier ->
            BalanceeCard(borderColor = accent, modifier = paneModifier) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LabelText(text = "Cash amount", color = ActiveCyan)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        AmountDisplay(
                            amountKobo = amountKobo,
                            color = accent,
                            style = MaterialTheme.typography.displayMedium,
                        )
                    }

                    LabelText(text = "Litres cutoff", color = ActiveCyan)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        LitresDisplay(
                            litres = litresCutoff,
                            color = accent,
                            style = MaterialTheme.typography.displaySmall,
                        )
                    }

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
                        LedgerRow(
                            label = "Price / L",
                            value = if (priceKoboPerLitre > 0L) formatNaira(priceKoboPerLitre) else "—",
                        )
                        LedgerRow(
                            label = "Cutoff at",
                            value = if (litresCutoff > 0.0) "%.2f L".format(litresCutoff) else "—",
                        )
                        LedgerRow(
                            label = "Min · Max",
                            value = "${formatNaira(CASH_MIN_NAIRA * 100L)} · " +
                                formatNaira(CASH_MAX_NAIRA * 100L),
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
        }
        val keypadPane: @Composable (Modifier) -> Unit = { paneModifier ->
            Column(
                modifier = paneModifier,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Enter the cash amount",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                )
                Text(
                    text = "System computes the litre cutoff; the pump cuts at exactly that " +
                        "amount. Floored — never dispenses more than was paid.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                NumericKeypad(
                    onDigit = { d -> typed = appendDigit(typed, d) },
                    onBackspace = { if (typed.isNotEmpty()) typed = typed.dropLast(1) },
                    onDecimal = { typed = appendDecimal(typed) },
                    showConfirmKey = false,
                    showDecimalKey = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (isPortrait()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
            ) {
                amountCard(Modifier.fillMaxWidth().weight(1f))
                keypadPane(Modifier.fillMaxWidth().weight(1f))
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
            ) {
                amountCard(Modifier.weight(1f).fillMaxHeight())
                keypadPane(Modifier.weight(1f).fillMaxHeight())
            }
        }

        BalanceeButton(
            label = if (amountKobo > 0L) "Authorise cash ${formatNaira(amountKobo)}" else "Authorise cash",
            onClick = { if (valid) onAuthorise(amountKobo) },
            enabled = valid,
            variant = BalanceeButtonVariant.Brand,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun CashFixedAmountEntryPreview() {
    SmartPumpDisplayTheme {
        CashFixedAmountEntryScreen(
            priceKoboPerLitre = 87_050,
            onAuthorise = {},
            onCancel = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 600, heightDp = 1024)
@Composable
private fun CashFixedAmountEntryPortraitPreview() {
    SmartPumpDisplayTheme {
        CashFixedAmountEntryScreen(
            priceKoboPerLitre = 87_050,
            onAuthorise = {},
            onCancel = {},
        )
    }
}
