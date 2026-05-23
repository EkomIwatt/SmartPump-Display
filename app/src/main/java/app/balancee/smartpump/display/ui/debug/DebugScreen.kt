// Phase 4b debug screen. Engineering-only — not part of the customer/attendant UX.
// Reachable from a long-press on the top-left corner hotspot (see MainActivity).
//
// Covers the knobs the rebuild documented across all five flows:
//  - Hardware: pulse rate + tank capacity on MockPulseSource, plus inject-failure buttons.
//  - Payment: auto-approve toggle, pending delay slider, failure-reason text, force-resolve
//    button (Flow 5 "SMS arrived" injector — also works as a generic instant-resolve).
//  - Device config: pumpId / stationName / koboPerLitre / virtualAccountNumber form. Saves
//    push through DeviceConfigRepository, so CustomerViewModel's price guard picks them up
//    on its next read.
package app.balancee.smartpump.display.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.LedgerRow
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.PrimaryAmber
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.SuccessGreen
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary
import app.balancee.smartpump.display.ui.theme.WarningRed

@Composable
fun DebugScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    vm: DebugViewModel = hiltViewModel(),
) {
    val state by vm.ui.collectAsState()
    DebugScreenContent(
        state = state,
        onClose = onClose,
        onPulsesPerSecond = vm::setPulsesPerSecond,
        onTankCapacity = vm::setTankCapacityLitres,
        onInjectDisconnect = vm::injectDisconnect,
        onInjectParseError = vm::injectParseError,
        onAutoApprove = vm::setAutoApprove,
        onPendingDelayMs = vm::setPendingDelayMs,
        onFailureReason = vm::setFailureReason,
        onTriggerInstantResolve = vm::triggerInstantResolve,
        onSaveConfig = vm::saveDeviceConfig,
        onPinBypass = vm::setPinBypass,
        onResetOnboarding = vm::resetOnboarding,
        modifier = modifier,
    )
}

@Composable
private fun DebugScreenContent(
    state: DebugUiState,
    onClose: () -> Unit,
    onPulsesPerSecond: (Int) -> Unit,
    onTankCapacity: (Double) -> Unit,
    onInjectDisconnect: () -> Unit,
    onInjectParseError: () -> Unit,
    onAutoApprove: (Boolean) -> Unit,
    onPendingDelayMs: (Long) -> Unit,
    onFailureReason: (String) -> Unit,
    onTriggerInstantResolve: () -> Unit,
    onSaveConfig: (String, String, Long, String?) -> Unit,
    onPinBypass: (Boolean) -> Unit,
    onResetOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                LabelText(text = "Engineering · debug")
                Text(
                    text = "SmartPump Display",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                )
                Text(
                    text = "Mock-stack knobs. Not visible to customers or attendants.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
            BalanceeButton(
                label = "Done",
                onClick = onClose,
                variant = BalanceeButtonVariant.Secondary,
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        HardwareCard(
            pulsesPerSecond = state.pulsesPerSecond,
            tankCapacityLitres = state.tankCapacityLitres,
            onPulsesPerSecond = onPulsesPerSecond,
            onTankCapacity = onTankCapacity,
            onInjectDisconnect = onInjectDisconnect,
            onInjectParseError = onInjectParseError,
        )

        PaymentCard(
            autoApprove = state.autoApprove,
            pendingDelayMs = state.pendingDelayMs,
            failureReason = state.failureReason,
            onAutoApprove = onAutoApprove,
            onPendingDelayMs = onPendingDelayMs,
            onFailureReason = onFailureReason,
            onTriggerInstantResolve = onTriggerInstantResolve,
        )

        if (state.isDebugBuild) {
            SecurityCard(
                pinBypassEnabled = state.pinBypassEnabled,
                stationId = state.stationId,
                stationDisplayName = state.stationDisplayName,
                resetStatus = state.resetStatus,
                onPinBypass = onPinBypass,
                onResetOnboarding = onResetOnboarding,
            )
        }

        DeviceConfigCard(
            current = state.deviceConfig,
            saveStatus = state.saveStatus,
            onSave = onSaveConfig,
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun HardwareCard(
    pulsesPerSecond: Int,
    tankCapacityLitres: Double,
    onPulsesPerSecond: (Int) -> Unit,
    onTankCapacity: (Double) -> Unit,
    onInjectDisconnect: () -> Unit,
    onInjectParseError: () -> Unit,
) {
    BalanceeCard(borderColor = ActiveCyan) {
        LabelText(text = "Mock hardware", color = ActiveCyan)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Pulse rate: $pulsesPerSecond pps",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        Slider(
            value = pulsesPerSecond.toFloat(),
            onValueChange = { onPulsesPerSecond(it.toInt()) },
            valueRange = 0f..200f,
            steps = 39,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tank capacity: %.1f L".format(tankCapacityLitres),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        Slider(
            value = tankCapacityLitres.toFloat(),
            onValueChange = { onTankCapacity(it.toDouble()) },
            valueRange = 0.5f..200f,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BalanceeButton(
                label = "Inject disconnect",
                onClick = onInjectDisconnect,
                variant = BalanceeButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            BalanceeButton(
                label = "Inject parse error",
                onClick = onInjectParseError,
                variant = BalanceeButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PaymentCard(
    autoApprove: Boolean,
    pendingDelayMs: Long,
    failureReason: String,
    onAutoApprove: (Boolean) -> Unit,
    onPendingDelayMs: (Long) -> Unit,
    onFailureReason: (String) -> Unit,
    onTriggerInstantResolve: () -> Unit,
) {
    BalanceeCard(borderColor = PrimaryAmber) {
        LabelText(text = "Mock payment processor", color = PrimaryAmber)
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Auto-approve",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                Text(
                    text = if (autoApprove) "Next payment will succeed."
                    else "Next payment will fail with the configured reason.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Switch(checked = autoApprove, onCheckedChange = onAutoApprove)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Pending delay: ${pendingDelayMs} ms",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        Slider(
            value = pendingDelayMs.toFloat(),
            onValueChange = { onPendingDelayMs(it.toLong()) },
            valueRange = 0f..30_000f,
        )
        Spacer(Modifier.height(8.dp))
        DebugTextField(
            label = "Failure reason",
            value = failureReason,
            onValueChange = onFailureReason,
            enabled = !autoApprove,
        )
        Spacer(Modifier.height(12.dp))
        BalanceeButton(
            label = "SMS arrived · force resolve",
            onClick = onTriggerInstantResolve,
        )
        Text(
            text = "Bypasses the pending delay on the in-flight payment. Use for Flow 5 " +
                "(USSD) and any pre-pay digital wait.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SecurityCard(
    pinBypassEnabled: Boolean,
    stationId: String?,
    stationDisplayName: String?,
    resetStatus: String?,
    onPinBypass: (Boolean) -> Unit,
    onResetOnboarding: () -> Unit,
) {
    BalanceeCard(borderColor = app.balancee.smartpump.display.ui.theme.BrandBlue) {
        LabelText(
            text = "Security · debug builds only",
            color = app.balancee.smartpump.display.ui.theme.BrandBlue,
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Skip PIN modal on attendant actions",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                Text(
                    text = if (pinBypassEnabled) {
                        "ON · attendant actions fire immediately."
                    } else {
                        "OFF · each action prompts for the 4-digit PIN."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Switch(checked = pinBypassEnabled, onCheckedChange = onPinBypass)
        }
        Spacer(Modifier.height(16.dp))

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LedgerRow(
                label = "Station ID",
                value = stationId ?: "(not provisioned)",
            )
            LedgerRow(
                label = "Display name",
                value = stationDisplayName ?: "(not provisioned)",
            )
        }
        Spacer(Modifier.height(12.dp))

        BalanceeButton(
            label = "Re-run onboarding",
            onClick = onResetOnboarding,
            variant = BalanceeButtonVariant.Secondary,
        )
        Text(
            text = "Wipes the local station identity. Debug builds auto-seed the Demo " +
                "identity on the next launch — flip this off only to walk the real " +
                "install flow once.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
        resetStatus?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Reset failed")) WarningRed else SuccessGreen,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun DeviceConfigCard(
    current: app.balancee.smartpump.display.domain.model.DeviceConfig?,
    saveStatus: String?,
    onSave: (String, String, Long, String?) -> Unit,
) {
    var pumpId by remember(current?.pumpId) { mutableStateOf(current?.pumpId ?: "PUMP 1") }
    var stationName by remember(current?.stationName) {
        mutableStateOf(current?.stationName ?: "SmartPump Station")
    }
    var nairaPerLitre by remember(current?.koboPerLitre) {
        mutableStateOf(((current?.koboPerLitre ?: 87_000L) / 100).toString())
    }
    var virtualAccount by remember(current?.virtualAccountNumber) {
        mutableStateOf(current?.virtualAccountNumber.orEmpty())
    }

    BalanceeCard(borderColor = SuccessGreen) {
        LabelText(text = "Device config", color = SuccessGreen)
        Spacer(Modifier.height(12.dp))

        current?.let {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LedgerRow(label = "Price / L (live)", value = "₦${it.koboPerLitre / 100}")
                LedgerRow(label = "Updated at", value = it.updatedAt.toString())
            }
            Spacer(Modifier.height(12.dp))
        }

        DebugTextField(label = "Pump ID", value = pumpId, onValueChange = { pumpId = it })
        Spacer(Modifier.height(8.dp))
        DebugTextField(
            label = "Station name",
            value = stationName,
            onValueChange = { stationName = it },
        )
        Spacer(Modifier.height(8.dp))
        DebugTextField(
            label = "Naira per litre",
            value = nairaPerLitre,
            onValueChange = { nairaPerLitre = it.filter { ch -> ch.isDigit() } },
        )
        Spacer(Modifier.height(8.dp))
        DebugTextField(
            label = "Virtual account (NIP)",
            value = virtualAccount,
            onValueChange = { virtualAccount = it.filter { ch -> ch.isDigit() } },
        )
        Spacer(Modifier.height(12.dp))
        BalanceeButton(
            label = "Save config",
            onClick = {
                val naira = nairaPerLitre.toLongOrNull() ?: return@BalanceeButton
                onSave(
                    pumpId.trim(),
                    stationName.trim(),
                    naira * 100,
                    virtualAccount.trim().takeIf { it.isNotEmpty() },
                )
            },
        )
        saveStatus?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Save failed")) WarningRed else SuccessGreen,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun DebugTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            disabledTextColor = TextSecondary,
            focusedLabelColor = PrimaryAmber,
            unfocusedLabelColor = TextSecondary,
            focusedIndicatorColor = PrimaryAmber,
            unfocusedIndicatorColor = BorderSubtle,
            cursorColor = PrimaryAmber,
        ),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0F, widthDp = 1024, heightDp = 1200)
@Composable
private fun DebugScreenPreview() {
    SmartPumpDisplayTheme {
        Box(modifier = Modifier.fillMaxSize().background(Background)) {
            DebugScreenContent(
                state = DebugUiState(
                    pulsesPerSecond = 50,
                    tankCapacityLitres = 60.0,
                    autoApprove = true,
                    pendingDelayMs = 3_000L,
                    failureReason = "Mock: payment declined",
                    pinBypassEnabled = true,
                    isDebugBuild = true,
                    stationId = "DEMO-001",
                    stationDisplayName = "Demo Station",
                ),
                onClose = {},
                onPulsesPerSecond = {},
                onTankCapacity = {},
                onInjectDisconnect = {},
                onInjectParseError = {},
                onAutoApprove = {},
                onPendingDelayMs = {},
                onFailureReason = {},
                onTriggerInstantResolve = {},
                onSaveConfig = { _, _, _, _ -> },
                onPinBypass = {},
                onResetOnboarding = {},
                modifier = Modifier.padding(PaddingValues(0.dp)),
            )
        }
    }
}
