// Attendant swipe-up overlay (Phase 4). Three actions per docs/flows.md, never more:
//  - FILL UP AUTHORISE          — enabled when the pump is Idle or FillupAwaitingAttendantAuth.
//  - AUTHORISE CASH ₦…          — enabled when the pump is Idle (routes to the existing
//                                 CashFixedAmountEntry keypad screen — the strict-design
//                                 "inline ₦" placeholder is rendered here as a teaser; the
//                                 actual amount is entered on the dedicated screen).
//  - CASH RECEIVED              — enabled only in FillupAwaitingCashConfirm. Greyed otherwise.
//
// Visual: state-coloured BalanceeCard per slot (cyan / gold / green), grey-ghost when disabled
// (border-subtle, text-tertiary) so the attendant immediately sees which actions are live.
package app.balancee.smartpump.display.ui.attendant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.balancee.smartpump.display.domain.model.TransactionState
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.HeroSerifText
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.BrandBlue
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.SuccessGreen
import app.balancee.smartpump.display.ui.theme.Surface
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary
import app.balancee.smartpump.display.ui.theme.TextTertiary

@Composable
fun AttendantPanel(
    state: TransactionState,
    onFillUpAuthorise: () -> Unit,
    onAuthoriseCash: () -> Unit,
    onCashReceived: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fillUpEnabled = state is TransactionState.Idle ||
        state is TransactionState.FillupAwaitingAttendantAuth
    val cashFixedEnabled = state is TransactionState.Idle
    val cashReceivedEnabled = state is TransactionState.FillupAwaitingCashConfirm

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Background)
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                LabelText(text = "Attendant · interface")
            }
            // Pull-tab affordance: tappable area to dismiss without a swipe.
            Box(
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .background(
                        color = Surface,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(
                            Dimensions.cornerChip,
                        ),
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "DISMISS",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
        ) {
            ActionCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                enabled = fillUpEnabled,
                accent = ActiveCyan,
                label = "Action 1 · open-ended",
                title = "FILL UP",
                actionLabel = "AUTHORISE",
                helper = "Opens the pump open-ended. Nozzle shuts on 3s flow gap.",
                onClick = { if (fillUpEnabled) { onFillUpAuthorise(); onDismiss() } },
            )
            ActionCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                enabled = cashFixedEnabled,
                accent = BrandBlue,
                label = "Action 2 · fixed cash",
                title = "AUTHORISE CASH ₦…",
                actionLabel = "ENTER AMOUNT",
                helper = "Enter the cash on the keypad. Cuts at the exact litre cutoff.",
                onClick = { if (cashFixedEnabled) { onAuthoriseCash(); onDismiss() } },
            )
            ActionCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                enabled = cashReceivedEnabled,
                accent = SuccessGreen,
                label = "Action 3 · close cash",
                title = "CASH RECEIVED",
                actionLabel = "CONFIRM",
                helper = "Only available after a fill-up completes and the customer pays cash.",
                onClick = { if (cashReceivedEnabled) { onCashReceived(); onDismiss() } },
            )
        }
    }
}

@Composable
private fun ActionCard(
    modifier: Modifier,
    enabled: Boolean,
    accent: Color,
    label: String,
    title: String,
    actionLabel: String,
    helper: String,
    onClick: () -> Unit,
) {
    val borderColor = if (enabled) accent else BorderSubtle
    val titleColor = if (enabled) TextPrimary else TextTertiary
    val actionColor = if (enabled) accent else TextTertiary
    val helperColor = if (enabled) TextSecondary else TextTertiary

    BalanceeCard(
        borderColor = borderColor,
        modifier = if (enabled) modifier.clickable(onClick = onClick) else modifier,
    ) {
        LabelText(text = label, color = if (enabled) accent else TextTertiary)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = titleColor,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (enabled) actionLabel else "DISABLED",
            style = MaterialTheme.typography.titleLarge,
            color = actionColor,
            fontStyle = if (!enabled) FontStyle.Italic else FontStyle.Normal,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = helper,
            style = MaterialTheme.typography.bodySmall,
            color = helperColor,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0F, widthDp = 1024, heightDp = 360)
@Composable
private fun AttendantPanelIdlePreview() {
    SmartPumpDisplayTheme {
        AttendantPanel(
            state = TransactionState.Idle,
            onFillUpAuthorise = {},
            onAuthoriseCash = {},
            onCashReceived = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0F, widthDp = 1024, heightDp = 360)
@Composable
private fun AttendantPanelAwaitingCashPreview() {
    SmartPumpDisplayTheme {
        AttendantPanel(
            state = TransactionState.FillupAwaitingCashConfirm(
                txnId = "BLC-00342",
                verifiedLitres = 38.1,
                amountDueNaira = 33_147,
            ),
            onFillUpAuthorise = {},
            onAuthoriseCash = {},
            onCashReceived = {},
            onDismiss = {},
        )
    }
}
