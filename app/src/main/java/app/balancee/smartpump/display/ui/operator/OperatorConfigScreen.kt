// Operator config screen (Phase 7b) — where a manager tells this pump what it sells and for how
// much. Reached from the attendant overlay's header, behind the same shared PIN that gates the
// authorise actions (OQ #19: V1 has one PIN and no roles; see OPEN_QUESTIONS for the accepted risk).
//
// Not in docs/Strict design screens/ — those cover the customer and attendant surfaces, and no
// operator/settings screen was ever drawn. Built from design-system.md tokens and the existing
// components so it reads as part of the same product rather than a bolted-on form.
//
// Phase 7h added the fuel log at the bottom: fuel the adapter counted while the app was not
// running. It lives here rather than behind its own navigation because it is read by the same
// person, in the same visit, behind the same PIN — and inventing a second untethered screen for a
// list of at most a few rows buys nothing.
//
// Two deliberate choices about being unhelpful:
//  - Fields start BLANK on an unconfigured pump rather than pre-filled with a plausible price. A
//    filled-looking field does not ask to be read, and the failure it invites (accepting a demo
//    ₦870/L on a real forecourt) is a wrong-price sale on every litre until someone notices.
//  - Fuel type has no default selection. There is no safe guess: petrol on a diesel pump authorises
//    against the wrong fuel at the wrong price.
package app.balancee.smartpump.display.ui.operator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.balancee.smartpump.display.domain.model.EventType
import app.balancee.smartpump.display.domain.model.FuelType
import app.balancee.smartpump.display.domain.model.OperationalEvent
import app.balancee.smartpump.display.domain.usecase.CanStartTransactionUseCase
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.SelectableTile
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.OnBrand
import app.balancee.smartpump.display.ui.theme.SuccessGreen
import app.balancee.smartpump.display.ui.theme.Surface
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary
import app.balancee.smartpump.display.ui.theme.WarningRed
import app.balancee.smartpump.display.ui.util.formatNaira
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun OperatorConfigScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    vm: OperatorConfigViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val fuelLog by vm.fuelLog.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimensions.sectionSpacing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LabelText(text = "Operator · pump settings")
            Box(
                modifier = Modifier
                    .clickable(onClick = onClose)
                    .background(Surface, RoundedCornerShape(Dimensions.cornerChip))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "DONE",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                )
            }
        }

        // Says plainly whether this pump can currently sell, and why not. The same information the
        // customer screen is showing, phrased for someone who can actually fix it.
        StatusBanner(missing = ui.missing, loaded = ui.loaded)

        BalanceeCard(borderColor = if (ui.fuelType == null) WarningRed else SuccessGreen) {
            LabelText(text = "Fuel type")
            Spacer(Modifier.height(4.dp))
            Text(
                text = "What this pump dispenses. Sent with every sale — must match the nozzle.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.itemSpacing)) {
                FuelType.entries.forEach { option ->
                    SelectableTile(
                        selected = ui.fuelType == option,
                        onClick = { vm.onFuelTypeSelected(option) },
                        modifier = Modifier.weight(1f),
                        contentPadding = 12.dp,
                    ) {
                        Text(
                            text = option.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (ui.fuelType == option) OnBrand else TextPrimary,
                        )
                    }
                }
            }
        }

        BalanceeCard(borderColor = if (ui.koboPerLitre == null) WarningRed else SuccessGreen) {
            LabelText(text = "Price per litre")
            Spacer(Modifier.height(12.dp))
            ConfigField(
                label = "Naira per litre",
                value = ui.nairaPerLitre,
                onValueChange = { raw ->
                    vm.onPriceChanged(raw.filter { it.isDigit() || it == '.' })
                },
                numeric = true,
                placeholder = "e.g. 870.50",
            )
            // Echo the parsed value back. Kobo is what actually gets stored and billed, so showing
            // the round-tripped figure catches a typo (8705 vs 870.5) before it reaches a customer.
            ui.koboPerLitre?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Will be saved as ${formatNaira(it)} per litre",
                    style = MaterialTheme.typography.bodySmall,
                    color = SuccessGreen,
                )
            }
        }

        BalanceeCard(borderColor = BorderSubtle) {
            LabelText(text = "Identification")
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Station name prints on receipts. Pump label is the caption on this screen.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(12.dp))
            ConfigField(
                label = "Station name",
                value = ui.stationName,
                onValueChange = vm::onStationNameChanged,
                placeholder = "e.g. Total Lekki Ph2",
            )
            Spacer(Modifier.height(Dimensions.itemSpacing))
            ConfigField(
                label = "Pump label",
                value = ui.pumpLabel,
                onValueChange = vm::onPumpLabelChanged,
                placeholder = "e.g. PUMP 1",
            )
        }

        ui.saveError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = WarningRed,
            )
        }
        if (ui.savedAtMillis != null && ui.saveError == null) {
            Text(
                text = "Saved. This pump is configured.",
                style = MaterialTheme.typography.bodyMedium,
                color = SuccessGreen,
            )
        }

        BalanceeButton(
            label = "Save settings",
            enabled = ui.canSave,
            onClick = vm::onSave,
            modifier = Modifier.fillMaxWidth(),
        )

        FuelLogSection(entries = fuelLog)

        Spacer(Modifier.height(Dimensions.sectionSpacing))
    }
}

/**
 * Fuel the adapter counted while the app was not running (Phase 7h, OPEN_QUESTIONS #25).
 *
 * The empty state is worth as much as the populated one: "nothing unaccounted for" is the answer
 * an operator is usually looking for, and a section that vanishes when empty cannot give it.
 *
 * Litres are shown from the K-factor stamped on each row at write time, not today's, so a figure
 * here does not quietly change value when calibration corrects that constant.
 */
@Composable
private fun FuelLogSection(entries: List<OperationalEvent>) {
    val unexplained = entries.count { it.type == EventType.PULSE_GAP_UNEXPLAINED }

    BalanceeCard(borderColor = if (unexplained > 0) WarningRed else BorderSubtle) {
        LabelText(text = "Fuel log")
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Fuel measured by the pump adapter while this screen was off or restarting.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(12.dp))

        if (entries.isEmpty()) {
            Text(
                text = "Nothing unaccounted for.",
                style = MaterialTheme.typography.bodyMedium,
                color = SuccessGreen,
            )
            return@BalanceeCard
        }

        entries.forEachIndexed { index, entry ->
            if (index > 0) Spacer(Modifier.height(Dimensions.itemSpacing))
            FuelLogRow(entry)
        }
    }
}

@Composable
private fun FuelLogRow(entry: OperationalEvent) {
    // TEMPORARY BENCH TRACE — remove before merge. A trace row carries no litres, only the
    // reconciliation's operands, so it is rendered as the raw line rather than as an amount.
    if (entry.type == EventType.TRACE) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatLogTimestamp(entry.createdAtMs),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Text(
                text = entry.detail.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = ActiveCyan,
            )
        }
        return
    }

    val unexplained = entry.type == EventType.PULSE_GAP_UNEXPLAINED
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // An unknown amount is a worse finding than a number, not a smaller one, so it is
                // spelled out rather than shown as a dash the eye slides over.
                text = entry.litres?.let { "%.2f L".format(it) } ?: "Amount unknown",
                style = MaterialTheme.typography.titleMedium,
                color = if (unexplained) WarningRed else TextPrimary,
            )
            Text(
                text = formatLogTimestamp(entry.createdAtMs),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        entry.detail?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        entry.transactionRef?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Sale $it",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

private fun formatLogTimestamp(millis: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))

@Composable
private fun StatusBanner(
    missing: Set<CanStartTransactionUseCase.Missing>,
    loaded: Boolean,
) {
    if (!loaded) return
    val configured = missing.isEmpty()
    val accent = if (configured) SuccessGreen else WarningRed
    val text = when {
        configured -> "This pump is configured and can take sales."
        missing.containsAll(
            setOf(
                CanStartTransactionUseCase.Missing.PRICE,
                CanStartTransactionUseCase.Missing.FUEL_TYPE,
            ),
        ) -> "Not configured — set a fuel type and a price. The pump cannot sell until both are set."

        missing.contains(CanStartTransactionUseCase.Missing.FUEL_TYPE) ->
            "No fuel type set — the pump cannot sell until you choose one."

        else -> "No price set — the pump cannot sell until you enter one."
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(Dimensions.cornerCard))
            .padding(16.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = accent)
    }
}

@Composable
private fun ConfigField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextSecondary) },
        placeholder = placeholder?.let { { Text(it, color = TextSecondary) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedBorderColor = SuccessGreen,
            unfocusedBorderColor = BorderSubtle,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
