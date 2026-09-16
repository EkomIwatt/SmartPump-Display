// The debug-only API probe panel, below the activation panel on the operator screen.
//
// Design-authority flag (CLAUDE.md): like the activation panel and the error screen before it,
// nothing in docs/Strict design screens/ covers this. Built from existing pieces — BalanceeCard,
// LabelText, CodePanel, the tone-tinted report block — so the deviation is layout only, not tokens.
// It is never seen by a customer or an attendant: compiled into debug builds only, behind the
// attendant PIN.
//
// Two things are deliberately loud. Which server this is pointed at, because a debuggable build
// that can reach production is new. And the line between probes that only ask questions and probes
// that create records — the second kind sits behind a switch that resets every time the panel is
// rebuilt, because /authorise initialises a real Paystack payment.
package app.balancee.smartpump.display.ui.probe

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.balancee.smartpump.display.data.network.ProbeCapture
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.CodePanel
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.BrandBlue
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.OnPrimary
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.SuccessGreen
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary
import app.balancee.smartpump.display.ui.theme.TextTertiary
import app.balancee.smartpump.display.ui.theme.WarningRed
import java.time.Instant

@Composable
fun ApiProbePanel(
    modifier: Modifier = Modifier,
    vm: ApiProbeViewModel = hiltViewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    ApiProbePanelContent(
        state = state,
        actions = ApiProbeActions(
            onProbeConfig = vm::probeConfig,
            onProbeStatus = vm::probeStatus,
            onProbeClockSkew = vm::probeClockSkew,
            onProbeAuthorise = vm::probeAuthorise,
            onProbeUpload = vm::probeUpload,
            onTransactionIdChange = vm::setTransactionId,
            onLitresChange = vm::setLitres,
            onAcknowledgeWrites = vm::setWritesAcknowledged,
            onSaveCaptures = vm::saveCaptures,
            onClearCaptures = vm::clearCaptures,
        ),
        modifier = modifier,
    )
}

/** Grouped so the preview and the content signature do not grow a parameter per button. */
data class ApiProbeActions(
    val onProbeConfig: () -> Unit = {},
    val onProbeStatus: () -> Unit = {},
    val onProbeClockSkew: () -> Unit = {},
    val onProbeAuthorise: (AuthoriseVariant) -> Unit = {},
    val onProbeUpload: () -> Unit = {},
    val onTransactionIdChange: (String) -> Unit = {},
    val onLitresChange: (String) -> Unit = {},
    val onAcknowledgeWrites: (Boolean) -> Unit = {},
    val onSaveCaptures: () -> Unit = {},
    val onClearCaptures: () -> Unit = {},
)

@Composable
internal fun ApiProbePanelContent(
    state: ApiProbeUiState,
    actions: ApiProbeActions,
    modifier: Modifier = Modifier,
) {
    BalanceeCard(
        borderColor = if (state.productionServer) PrimaryGold else BorderSubtle,
        modifier = modifier.fillMaxWidth(),
    ) {
        LabelText(
            text = if (state.productionServer) "API probe · LIVE SERVER" else "API probe · debug",
            color = if (state.productionServer) PrimaryGold else TextSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (state.productionServer) {
                "This build talks to the production backend. Anything sent from here is real."
            } else {
                "Drives the Pump API through the same client the app uses, so what is under test " +
                    "is our signing and parsing, not a second implementation."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.productionServer) PrimaryGold else TextSecondary,
        )

        Spacer(Modifier.height(12.dp))
        DetailRow(label = "Server", value = state.baseUrl)
        Spacer(Modifier.height(8.dp))
        DetailRow(
            label = "Credentials",
            value = if (state.activated) {
                "activated" + (state.pumpId?.let { " · pump $it" } ?: "")
            } else {
                "none — every signed call will stop at the interceptor"
            },
        )
        state.config?.let {
            Spacer(Modifier.height(8.dp))
            DetailRow(
                label = "Config",
                value = "${it.fuelType.name} · ${it.pricePerUnit}/L · ${it.stationName}",
            )
        }

        // ---- asks questions, creates nothing ----------------------------------------------

        Spacer(Modifier.height(Dimensions.sectionSpacing))
        LabelText(text = "Read-only")
        Spacer(Modifier.height(8.dp))
        BalanceeButton(
            label = if (state.running) "Calling…" else "GET /config",
            onClick = actions.onProbeConfig,
            variant = BalanceeButtonVariant.Secondary,
            enabled = state.canProbe,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        ProbeField(
            label = "Transaction id",
            value = state.transactionId,
            onValueChange = actions.onTransactionIdChange,
            placeholder = "blank → probe-not-a-real-id",
        )
        Spacer(Modifier.height(8.dp))
        BalanceeButton(
            label = "GET /transactions/{id}",
            onClick = actions.onProbeStatus,
            variant = BalanceeButtonVariant.Secondary,
            enabled = state.canProbe,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        BalanceeButton(
            label = "GET /config signed 10 min ago",
            onClick = actions.onProbeClockSkew,
            variant = BalanceeButtonVariant.Secondary,
            enabled = state.canProbe,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "A refusal here is the good outcome — it is the freshness window #15 was " +
                "written around but nobody has ever seen.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
        )

        // ---- creates records ---------------------------------------------------------------

        Spacer(Modifier.height(Dimensions.sectionSpacing))
        WritesGate(state = state, onAcknowledge = actions.onAcknowledgeWrites)

        if (state.writesAcknowledged) {
            Spacer(Modifier.height(12.dp))
            ProbeField(
                label = "Litres",
                value = state.litres,
                onValueChange = actions.onLitresChange,
                placeholder = "e.g. 2.0",
                numeric = true,
            )
            Spacer(Modifier.height(8.dp))
            AmountLine(state = state)

            Spacer(Modifier.height(12.dp))
            BalanceeButton(
                label = "POST /authorise",
                onClick = { actions.onProbeAuthorise(AuthoriseVariant.Happy) },
                variant = BalanceeButtonVariant.Primary,
                enabled = state.canWrite,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.itemSpacing)) {
                BalanceeButton(
                    label = "…amount +1",
                    onClick = { actions.onProbeAuthorise(AuthoriseVariant.Mismatch) },
                    variant = BalanceeButtonVariant.Secondary,
                    enabled = state.canWrite,
                    modifier = Modifier.weight(1f),
                )
                BalanceeButton(
                    label = "…decimal amount",
                    onClick = { actions.onProbeAuthorise(AuthoriseVariant.Decimal) },
                    variant = BalanceeButtonVariant.Secondary,
                    enabled = state.canWrite,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))
            BalanceeButton(
                label = "POST /transactions/upload",
                onClick = actions.onProbeUpload,
                variant = BalanceeButtonVariant.Secondary,
                enabled = state.canUpload,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.lastAuthorise == null) {
                Text(
                    text = "Upload needs the transaction id and payment reference that only a real " +
                        "/authorise issues.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }
        }

        // ---- results -----------------------------------------------------------------------

        state.summary?.let {
            Spacer(Modifier.height(Dimensions.sectionSpacing))
            SummaryBlock(summary = it)
        }

        if (state.captures.isNotEmpty()) {
            Spacer(Modifier.height(Dimensions.sectionSpacing))
            LabelText(text = "Raw responses · newest first")
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Verbatim bytes. Fixtures are built from these, never from a restatement " +
                    "of them (#11).",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
            state.captures.forEach { capture ->
                Spacer(Modifier.height(8.dp))
                CaptureBlock(capture = capture)
            }

            Spacer(Modifier.height(Dimensions.sectionSpacing))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.itemSpacing)) {
                BalanceeButton(
                    label = "Save to file",
                    onClick = actions.onSaveCaptures,
                    variant = BalanceeButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                )
                BalanceeButton(
                    label = "Clear",
                    onClick = actions.onClearCaptures,
                    variant = BalanceeButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        state.savedPath?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Saved. MSYS_NO_PATHCONV=1 adb pull \"$it\"",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = SuccessGreen,
            )
        }
        state.saveError?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Could not write the capture file: $it",
                style = MaterialTheme.typography.bodySmall,
                color = WarningRed,
            )
        }
    }
}

/**
 * The switch in front of everything that leaves a record behind. Off by default and not remembered:
 * a panel reopened later starts safe, which is the right default for a control whose cheapest
 * mistake is a live payment initialisation on a real Paystack account.
 */
@Composable
private fun WritesGate(state: ApiProbeUiState, onAcknowledge: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                (if (state.productionServer) PrimaryGold else BorderSubtle).copy(alpha = 0.10f),
                RoundedCornerShape(Dimensions.cornerCard),
            )
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.writesAcknowledged,
                onCheckedChange = onAcknowledge,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = OnPrimary,
                    checkedTrackColor = PrimaryGold,
                    uncheckedTrackColor = BorderSubtle,
                ),
            )
            Spacer(Modifier.height(0.dp))
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = "Allow probes that create records",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                Text(
                    text = if (state.productionServer) {
                        "/authorise returns a Paystack checkout URL. On this server that is a real " +
                            "payment initialisation. Do not scan the QR it produces."
                    } else {
                        "/authorise and upload write transactions the server keeps."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
    }
}

/**
 * What the authorise will actually send — and, when the arithmetic does not fit, the finding
 * itself. The server checks `amount == litres × price` exactly, so a fractional product is not a
 * rounding inconvenience, it is a sale that cannot be authorised.
 */
@Composable
private fun AmountLine(state: ApiProbeUiState) {
    when (val plan = state.amountPlan) {
        null -> Text(
            text = if (state.config == null) {
                "Run GET /config first — the price has to come from the server, not from us."
            } else {
                "Enter a litres figure above zero."
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
        )

        is AmountPlan.Exact -> DetailRow(
            label = "amount to send",
            value = "${plan.naira}  (= ${state.litres} L × ${state.config?.pricePerUnit})",
        )

        is AmountPlan.Fractional -> Text(
            text = "${state.litres} L × ${state.config?.pricePerUnit} = ${plan.naira} — not a " +
                "whole naira, so `amount: Long` cannot carry it. Use the decimal probe: this is " +
                "#18c, and on this arithmetic every fill-up hits it.",
            style = MaterialTheme.typography.bodySmall,
            color = PrimaryGold,
        )
    }
}

@Composable
private fun ProbeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    numeric: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedLabelColor = BrandBlue,
            unfocusedLabelColor = TextSecondary,
            focusedIndicatorColor = BrandBlue,
            unfocusedIndicatorColor = BorderSubtle,
            cursorColor = BrandBlue,
        ),
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
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
private fun SummaryBlock(summary: ProbeSummary) {
    val accent = when (summary.tone) {
        ProbeTone.Success -> SuccessGreen
        ProbeTone.Caution -> PrimaryGold
        ProbeTone.Failure -> WarningRed
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(Dimensions.cornerCard))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = summary.headline,
            style = MaterialTheme.typography.titleMedium,
            color = accent,
        )
        Text(
            text = summary.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
        )
    }
}

/**
 * One response. Horizontally scrollable rather than wrapped: a JSON body re-flowed to the card
 * width is no longer the thing that was received, and this panel exists to show exactly that.
 */
@Composable
private fun CaptureBlock(capture: ProbeCapture) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${capture.method} ${capture.path} → ${capture.httpCode} · ${capture.at}",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        if (capture.truncated) {
            Text(
                text = "Truncated — not fixture material.",
                style = MaterialTheme.typography.bodySmall,
                color = WarningRed,
            )
        }
        Spacer(Modifier.height(4.dp))
        CodePanel(
            text = capture.body,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 900, heightDp = 1400)
@Composable
private fun ApiProbePanelPreview() {
    SmartPumpDisplayTheme {
        Column(
            modifier = Modifier.background(Background).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ApiProbePanelContent(
                state = ApiProbeUiState(
                    baseUrl = "https://api.balancee.app/",
                    activated = true,
                    pumpId = "PUMP-001",
                    writesAcknowledged = true,
                    litres = "2.35",
                    config = PreviewConfig.observed,
                    summary = ProbeSummary(
                        tone = ProbeTone.Success,
                        headline = "Refused, as a stale timestamp should be",
                        detail = "message: Request timestamp is not fresh",
                    ),
                    captures = listOf(
                        ProbeCapture(
                            method = "GET",
                            path = "/api/pump/config",
                            httpCode = 200,
                            at = Instant.parse("2026-09-16T12:00:00Z"),
                            body = "{\"status\":true,\"message\":\"Pump config\",\"data\":{}}",
                            truncated = false,
                        ),
                    ),
                ),
                actions = ApiProbeActions(),
            )
        }
    }
}

/** Preview-only, using the values actually observed on 2026-09-16. */
private object PreviewConfig {
    val observed = app.balancee.smartpump.display.data.network.dto.PumpConfigResponse(
        pumpId = "3727aebf-3c77-4180-a818-4254cbeeae72",
        stationName = "Kachi",
        fuelType = app.balancee.smartpump.display.domain.model.FuelType.PETROL,
        pricePerUnit = 1490,
        updatedAt = "2026-09-15T09:44:39.187Z",
    )
}
