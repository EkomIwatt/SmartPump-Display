// The debug-only API probe panel, below the activation panel on the operator screen.
//
// Design-authority flag (CLAUDE.md): like the activation panel and the error screen before it,
// nothing in docs/Strict design screens/ covers this. It is built from existing pieces —
// BalanceeCard, LabelText, CodePanel, the tone-tinted report block the activation panel uses — so
// the deviation is layout only, not tokens. It is also never seen by a customer or an attendant: it
// is compiled into debug builds only and sits behind the attendant PIN on the settings screen.
//
// The loudest thing on the card is which server it is pointed at. That is deliberate. A debuggable
// build that can reach production is new (the debugProd variant), and the failure it invites is
// running a test call against a real station's pump because the tablet looked like the dev one.
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
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
import app.balancee.smartpump.display.ui.theme.Dimensions
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
        onProbeConfig = vm::probeConfig,
        onSaveCaptures = vm::saveCaptures,
        onClearCaptures = vm::clearCaptures,
        modifier = modifier,
    )
}

@Composable
internal fun ApiProbePanelContent(
    state: ApiProbeUiState,
    onProbeConfig: () -> Unit,
    onSaveCaptures: () -> Unit,
    onClearCaptures: () -> Unit,
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

        Spacer(Modifier.height(Dimensions.sectionSpacing))
        BalanceeButton(
            label = if (state.running) "Calling…" else "GET /config",
            onClick = onProbeConfig,
            variant = BalanceeButtonVariant.Secondary,
            enabled = state.canProbe,
            modifier = Modifier.fillMaxWidth(),
        )

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
                    onClick = onSaveCaptures,
                    variant = BalanceeButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                )
                BalanceeButton(
                    label = "Clear",
                    onClick = onClearCaptures,
                    variant = BalanceeButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        state.savedPath?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Saved. adb pull \"$it\"",
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

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 900, heightDp = 900)
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
                    summary = ProbeSummary(
                        tone = ProbeTone.Caution,
                        headline = "200 OK, but zero prices parsed",
                        detail = "The envelope unwrapped and the body parsed, yet prices came " +
                            "out empty.",
                    ),
                    captures = listOf(
                        ProbeCapture(
                            method = "GET",
                            path = "/api/pump/config",
                            httpCode = 200,
                            at = Instant.parse("2026-09-16T12:00:00Z"),
                            body = "{\"status\":true,\"message\":\"OK\",\"data\":{}}",
                            truncated = false,
                        ),
                    ),
                ),
                onProbeConfig = {},
                onSaveCaptures = {},
                onClearCaptures = {},
            )
        }
    }
}
