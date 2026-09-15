// The activation code panel, shared by onboarding step 4 and the operator settings screen.
//
// Design-authority flag (CLAUDE.md): there is no activation screen in `docs/Strict design
// screens/`, so this layout is invention, like the error screen before it. It is built out of the
// existing design-system pieces — BalanceeCard, LabelText, the onboarding text field, the
// green/gold/red border language — so the deviation is in the layout only, not the tokens.
package app.balancee.smartpump.display.ui.activation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.BrandBlue
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.SuccessGreen
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary
import app.balancee.smartpump.display.ui.theme.TextTertiary
import app.balancee.smartpump.display.ui.theme.WarningRed

/**
 * Stateful entry point. Both call sites get their own [ActivationViewModel] from Hilt, which is
 * correct: they are never on screen at the same time, and the VM holds nothing worth sharing.
 */
@Composable
fun ActivationPanel(
    modifier: Modifier = Modifier,
    vm: ActivationViewModel = hiltViewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    ActivationPanelContent(
        state = state,
        onCodeChange = vm::setCode,
        onSubmit = vm::submit,
        modifier = modifier,
    )
}

@Composable
internal fun ActivationPanelContent(
    state: ActivationUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BalanceeCard(
        borderColor = if (state.activated) SuccessGreen else BorderSubtle,
        modifier = modifier.fillMaxWidth(),
    ) {
        LabelText(
            text = if (state.activated) "Activation · done" else "Activation",
            color = if (state.activated) SuccessGreen else BrandBlue,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (state.activated) {
                "This pump is registered with Balanceè. Card payments and price sync run off " +
                    "these keys."
            } else {
                "Type the activation code from Balanceè support. It can only be used once, so " +
                    "check it before sending. Cash sales work without it; card payments do not."
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )

        Spacer(Modifier.height(12.dp))
        IdentityRow(label = "Device ID", value = state.deviceId)
        state.pumpId?.let {
            Spacer(Modifier.height(8.dp))
            IdentityRow(label = "Pump ID", value = it)
        }

        if (!state.activated) {
            Spacer(Modifier.height(Dimensions.sectionSpacing))
            OutlinedTextField(
                value = state.code,
                onValueChange = onCodeChange,
                label = { Text("Activation code") },
                singleLine = true,
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedLabelColor = BrandBlue,
                    unfocusedLabelColor = TextSecondary,
                    focusedIndicatorColor = BrandBlue,
                    unfocusedIndicatorColor = BorderSubtle,
                    cursorColor = BrandBlue,
                ),
            )

            // The one place a different code is the wrong move: the last attempt left activation
            // unknown, so a fresh code may burn a spare on a pump that is already registered.
            if (state.warnNewCodeAfterUnknown) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "This is a different code from the one already sent. Only use it if " +
                        "Balanceè support has confirmed the first one was never redeemed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PrimaryGold,
                )
            }

            Spacer(Modifier.height(12.dp))
            BalanceeButton(
                label = when {
                    state.submitting -> "Activating…"
                    state.report?.mayRetrySameCode == true && !state.warnNewCodeAfterUnknown ->
                        "Send the same code again"
                    else -> "Activate this pump"
                },
                onClick = onSubmit,
                variant = BalanceeButtonVariant.Brand,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        state.report?.let {
            Spacer(Modifier.height(Dimensions.sectionSpacing))
            ReportBlock(report = it)
        }
    }
}

/**
 * One identifier, monospaced and selectable-looking. Long-form rather than truncated on purpose:
 * the recovery path for an ambiguous activation is an operator reading this out to support.
 */
@Composable
private fun IdentityRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = TextPrimary,
        )
    }
}

@Composable
private fun ReportBlock(report: ActivationReport) {
    val accent = when (report.tone) {
        ActivationTone.Success -> SuccessGreen
        ActivationTone.Caution -> PrimaryGold
        ActivationTone.Failure -> WarningRed
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(Dimensions.cornerCard))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = report.headline,
            style = MaterialTheme.typography.titleMedium,
            color = accent,
        )
        Text(
            text = report.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 900, heightDp = 700)
@Composable
private fun ActivationPanelPreview() {
    SmartPumpDisplayTheme {
        Column(
            modifier = Modifier.background(Background).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ActivationPanelContent(
                state = ActivationUiState(
                    deviceId = "11111111-2222-3333-4444-555555555555",
                    code = "BLC-ACT-7F19",
                ),
                onCodeChange = {},
                onSubmit = {},
            )
            ActivationPanelContent(
                state = ActivationUiState(
                    deviceId = "11111111-2222-3333-4444-555555555555",
                    code = "BLC-ACT-7F19",
                    report = ActivationOutcomePreview.unreachable,
                ),
                onCodeChange = {},
                onSubmit = {},
            )
        }
    }
}

/** Preview-only sample so the caution tone can be eyeballed without a network. */
private object ActivationOutcomePreview {
    val unreachable = ActivationReport(
        tone = ActivationTone.Caution,
        headline = "No answer from the server.",
        detail = "We do not know whether the code was used (timeout). Check the connection and " +
            "send the same code again — that is safe. Do not enter a different code.",
        mayRetrySameCode = true,
        mayTryNewCode = false,
    )
}
