// Flow 5, USSD code + SMS wait. Customer dials the per-bank USSD on their 2G phone;
// the bank debits and sends an SMS to the pump unit's SIM. Real path: a SIM-side
// BroadcastReceiver parses the SMS and matches the txn ref against this state. The
// mock path (Phase 3f) uses the existing MockPaymentProcessor: Success on the USSD
// channel stands in for "SMS received and parsed". Phase 4's debug screen will add
// a manual SMS injector for testing parser variants.
//
// Layout rebuilt (2026-05-23) to match docs/compare/expected.png:
//   - Per-card section headers above each card.
//   - Left card: USSD/OFFLINE chip row → DIAL THIS CODE → highlighted primary code box
//     → caption → divider → OTHER BANKS list (name / right-aligned mono code).
//   - Right card: AWAITING SMS chip → satellite icon → WAITING FOR / SMS CONFIRMATION
//     hero → helper text → divider → AMOUNT / REF / SIM STATUS / EXPIRES IN ledger.
//   - Small explainer paragraph below each card.
package app.balancee.smartpump.display.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.components.StateChip
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.BrandBlue
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.displayMonoFamily
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.SuccessGreen
import app.balancee.smartpump.display.ui.theme.SurfaceVariant
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary
import app.balancee.smartpump.display.ui.theme.TextTertiary
import app.balancee.smartpump.display.ui.util.isPortrait
import java.text.NumberFormat
import java.util.Locale

private data class BankCode(val name: String, val prefix: String)

private val OTHER_BANKS: List<BankCode> = listOf(
    BankCode("Access", "*901"),
    BankCode("Zenith", "*966"),
    BankCode("UBA", "*919"),
)
private val GTBANK = BankCode("GTBank", "*737")

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
            stateColor = ActiveCyan,
        )

        if (isPortrait()) {
            // Portrait: stack the dial card over the waiting card. Each card carries its own
            // chip, so the side-by-side column headers are dropped in this orientation.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DialColumn(
                    amountNaira = amountNaira,
                    txnRef = txnRef,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                WaitingColumn(
                    amountNaira = amountNaira,
                    txnRef = txnRef,
                    expiresInSeconds = expiresInSeconds,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        } else {
            // Landscape: two column headers above the cards — match expected.png.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    LabelText(text = "USSD Code Displayed")
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    LabelText(text = "Waiting for SMS")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
            ) {
                DialColumn(
                    amountNaira = amountNaira,
                    txnRef = txnRef,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                WaitingColumn(
                    amountNaira = amountNaira,
                    txnRef = txnRef,
                    expiresInSeconds = expiresInSeconds,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }

        BalanceeButton(
            label = "Cancel transaction",
            onClick = onCancel,
            variant = BalanceeButtonVariant.Secondary,
        )
    }
}

@Composable
private fun DialColumn(
    amountNaira: Int,
    txnRef: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BalanceeCard(
            borderColor = BrandBlue,
            modifier = Modifier.weight(1f),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // USSD chip on the left, OFFLINE label on the right.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StateChip(label = "USSD", color = BrandBlue)
                    Text(
                        text = "OFFLINE",
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = TextSecondary,
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }

                // Centered "DIAL THIS CODE" label.
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    LabelText(text = "Dial this code", color = TextSecondary)
                }

                // Highlighted primary code box.
                PrimaryCodeBox(
                    code = formatUssd(GTBANK, amountNaira, txnRef),
                )

                Text(
                    text = "${GTBANK.name} · ${formatNairaAmount(amountNaira)} · Ref: $txnRef",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider(color = BorderSubtle)

                // "OTHER BANKS" section.
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    LabelText(text = "Other banks", color = TextSecondary)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OTHER_BANKS.forEach { bank ->
                        BankCodeRow(
                            name = bank.name,
                            code = formatUssd(bank, amountNaira, txnRef),
                        )
                    }
                }

                Spacer(Modifier.weight(1f, fill = false))
            }
        }

        Text(
            text = "Pump shows USSD codes for 4 major banks. Customer dials on their 2G phone.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WaitingColumn(
    amountNaira: Int,
    txnRef: String,
    expiresInSeconds: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BalanceeCard(
            borderColor = BrandBlue,
            modifier = Modifier.weight(1f),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StateChip(label = "Awaiting SMS", color = ActiveCyan)

                // Hero block — satellite icon + "WAITING FOR SMS CONFIRMATION".
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "📡", // 📡 satellite antenna
                        fontSize = 56.sp,
                    )
                    Text(
                        text = "Waiting for",
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = TextSecondary,
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Text(
                        text = "SMS CONFIRMATION",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            color = BrandBlue,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                        ),
                    )
                    Text(
                        text = "Bank sends SMS after USSD completes. Usually 10–30 seconds.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }

                HorizontalDivider(color = BorderSubtle)

                // Ledger — match the spec, plus EXPIRES IN for the 5-min timeout.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LedgerLine(
                        label = "Amount",
                        value = formatNairaAmount(amountNaira),
                    )
                    LedgerLine(
                        label = "Ref",
                        value = txnRef,
                    )
                    LedgerLine(
                        label = "Sim status",
                        value = "MTN · Signal",
                        valueColor = SuccessGreen,
                    )
                    LedgerLine(
                        label = "Expires in",
                        value = formatCountdown(expiresInSeconds),
                    )
                }

                Spacer(Modifier.weight(1f, fill = false))
            }
        }

        Text(
            text = "Android monitors pump unit SIM for incoming SMS containing the reference.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PrimaryCodeBox(code: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimensions.cornerCodePanel))
            .background(SurfaceVariant)
            .border(
                Dimensions.borderWidth,
                BrandBlue.copy(alpha = 0.35f),
                RoundedCornerShape(Dimensions.cornerCodePanel),
            )
            .padding(vertical = 20.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = displayMonoFamily(),
                color = BrandBlue,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            ),
        )
    }
}

@Composable
private fun BankCodeRow(name: String, code: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Text(
            text = code,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = displayMonoFamily(),
                color = TextPrimary,
            ),
        )
    }
}

@Composable
private fun LedgerLine(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = TextPrimary,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LabelText(text = label)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = displayMonoFamily(),
                color = valueColor,
            ),
        )
    }
}

private fun formatUssd(bank: BankCode, amountNaira: Int, txnRef: String): String =
    "${bank.prefix}*$amountNaira*$txnRef#"

private fun formatNairaAmount(naira: Int): String =
    "₦" + NumberFormat.getInstance(Locale.UK).format(naira.toLong())

private fun formatCountdown(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
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
