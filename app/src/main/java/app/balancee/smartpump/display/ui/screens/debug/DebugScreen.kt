// Debug-only control panel. Drives the mock hardware/payment layer so we can exercise
// every state transition without an Arduino or a real payment backend.
//   • Pulse rate slider  — drives MockPulseSource.pulsesPerSecond.
//   • Inject disconnect / parse error — exercise the error path.
//   • Auto-approve toggle + pending delay — exercise payment success/failure timing.
//   • Device config form — set pumpId / station / price / virtual account.
//   • Live relay readout + current state.
package app.balancee.smartpump.display.ui.screens.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.balancee.smartpump.display.domain.model.TransactionState
import app.balancee.smartpump.display.ui.components.BalanceeButtonPrimary
import app.balancee.smartpump.display.ui.components.BalanceeButtonSecondary
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.theme.Dimensions

@Composable
fun DebugScreen(
    currentState: TransactionState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    vm: DebugViewModel = hiltViewModel(),
) {
    val pps by vm.pulsesPerSecond.collectAsState()
    val autoApprove by vm.autoApprove.collectAsState()
    val pendingDelay by vm.pendingDelayMs.collectAsState()
    val relayOpen by vm.relayOpen.collectAsState()
    val config by vm.config.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimensions.sectionSpacing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("DEBUG", style = MaterialTheme.typography.headlineLarge)
            BalanceeButtonSecondary(
                text = "CLOSE",
                onClick = onClose,
                modifier = Modifier.weight(0.4f, fill = false),
            )
        }

        StateCard(currentState = currentState, relayOpen = relayOpen)
        PulseCard(
            pulsesPerSecond = pps,
            onPpsChange = vm::setPulsesPerSecond,
            onInjectDisconnect = vm::injectDisconnect,
            onInjectParseError = vm::injectParseError,
        )
        PaymentCard(
            autoApprove = autoApprove,
            onAutoApproveChange = vm::setAutoApprove,
            pendingDelayMs = pendingDelay,
            onPendingDelayChange = vm::setPendingDelayMs,
        )
        DeviceConfigCard(
            initialPumpId = config?.pumpId.orEmpty(),
            initialStationName = config?.stationName.orEmpty(),
            initialKoboPerLitre = config?.koboPerLitre ?: 87_000L,
            initialVirtualAccount = config?.virtualAccountNumber.orEmpty(),
            onSave = vm::saveConfig,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StateCard(currentState: TransactionState, relayOpen: Boolean) {
    BalanceeCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelText("Current state")
            Text(currentState::class.simpleName ?: "?", style = MaterialTheme.typography.titleLarge)
            Text(
                text = currentState.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LabelText("Relay")
            Text(
                text = if (relayOpen) "OPEN — fuel flows" else "CLOSED",
                style = MaterialTheme.typography.titleMedium,
                color = if (relayOpen) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun PulseCard(
    pulsesPerSecond: Int,
    onPpsChange: (Int) -> Unit,
    onInjectDisconnect: () -> Unit,
    onInjectParseError: () -> Unit,
) {
    BalanceeCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LabelText("Mock pulse source")
            Text(
                "Pulses / second: $pulsesPerSecond  (≈ ${"%.1f".format(pulsesPerSecond * 0.6)} L/min)",
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = pulsesPerSecond.toFloat(),
                onValueChange = { onPpsChange(it.toInt()) },
                valueRange = 0f..200f,
                steps = 0,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BalanceeButtonSecondary(
                    text = "Inject disconnect",
                    onClick = onInjectDisconnect,
                    modifier = Modifier.weight(1f),
                )
                BalanceeButtonSecondary(
                    text = "Inject parse error",
                    onClick = onInjectParseError,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PaymentCard(
    autoApprove: Boolean,
    onAutoApproveChange: (Boolean) -> Unit,
    pendingDelayMs: Long,
    onPendingDelayChange: (Long) -> Unit,
) {
    BalanceeCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LabelText("Mock payments")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-approve", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = autoApprove, onCheckedChange = onAutoApproveChange)
            }
            Text(
                text = "Pending delay: ${pendingDelayMs} ms",
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = pendingDelayMs.toFloat(),
                onValueChange = { onPendingDelayChange(it.toLong()) },
                valueRange = 0f..15_000f,
            )
        }
    }
}

@Composable
private fun DeviceConfigCard(
    initialPumpId: String,
    initialStationName: String,
    initialKoboPerLitre: Long,
    initialVirtualAccount: String,
    onSave: (pumpId: String, stationName: String, koboPerLitre: Long, virtualAccountNumber: String?) -> Unit,
) {
    var pumpId by rememberSaveable(initialPumpId) { mutableStateOf(initialPumpId.ifBlank { "PUMP 1" }) }
    var stationName by rememberSaveable(initialStationName) {
        mutableStateOf(initialStationName.ifBlank { "SmartPump Station" })
    }
    var nairaPerLitre by rememberSaveable(initialKoboPerLitre) {
        mutableStateOf(("%.2f".format(initialKoboPerLitre / 100.0)))
    }
    var virtualAccount by rememberSaveable(initialVirtualAccount) { mutableStateOf(initialVirtualAccount) }
    val parsedKobo = remember(nairaPerLitre) {
        nairaPerLitre.toDoubleOrNull()?.let { (it * 100).toLong() } ?: 0L
    }

    BalanceeCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LabelText("Device config")
            OutlinedTextField(
                value = pumpId,
                onValueChange = { pumpId = it },
                label = { Text("Pump ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = stationName,
                onValueChange = { stationName = it },
                label = { Text("Station name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = nairaPerLitre,
                onValueChange = { nairaPerLitre = it },
                label = { Text("Price (₦ / L)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = virtualAccount,
                onValueChange = { virtualAccount = it },
                label = { Text("Virtual account (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            BalanceeButtonPrimary(
                text = "SAVE CONFIG",
                onClick = { onSave(pumpId, stationName, parsedKobo, virtualAccount) },
                enabled = parsedKobo > 0L && pumpId.isNotBlank() && stationName.isNotBlank(),
            )
        }
    }
}
