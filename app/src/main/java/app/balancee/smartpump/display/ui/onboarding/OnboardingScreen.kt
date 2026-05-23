// First-boot install flow shown when StationIdentityRepository.isProvisioned() == false.
// Locks the device until the operator finishes all three steps; the customer-facing host
// is not reachable from this screen, only the engineering long-press hotspot stays live.
package app.balancee.smartpump.display.ui.onboarding

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.BalanceeCard
import app.balancee.smartpump.display.ui.components.HeroSerifText
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.NumericKeypad
import app.balancee.smartpump.display.ui.components.PumpHeader
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.BrandBlue
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.SuccessGreen
import app.balancee.smartpump.display.ui.theme.SurfaceVariant
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary
import app.balancee.smartpump.display.ui.theme.TextTertiary
import app.balancee.smartpump.display.ui.theme.WarningRed
import androidx.compose.foundation.shape.CircleShape

@Composable
fun OnboardingScreen(
    modifier: Modifier = Modifier,
    vm: OnboardingViewModel = hiltViewModel(),
) {
    val state by vm.ui.collectAsState()

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) vm.loadLogoFromUri(uri)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PumpHeader(
            pumpId = "Pump 1",
            mode = "Setup",
            stateLabel = "Step ${state.step.ordinal + 1} of 3",
            stateColor = BrandBlue,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            BalanceeCard(
                borderColor = BrandBlue,
                modifier = Modifier.sizeIn(maxWidth = 720.dp).fillMaxWidth(),
            ) {
                when (state.step) {
                    OnboardingStep.Identity -> IdentityStep(
                        stationId = state.stationId,
                        displayName = state.displayName,
                        error = state.error,
                        onStationIdChange = vm::setStationId,
                        onDisplayNameChange = vm::setDisplayName,
                    )

                    OnboardingStep.Logo -> LogoStep(
                        logoBytes = state.logoBytes,
                        onPickLogo = {
                            photoPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        onClearLogo = vm::clearLogo,
                    )

                    OnboardingStep.Pin -> PinStep(
                        firstPin = state.firstPin,
                        confirmPin = state.confirmPin,
                        subStep = state.pinSubStep,
                        mismatchFlash = state.pinMismatchFlash,
                        submitting = state.submitting,
                        onDigit = vm::onPinDigit,
                        onBackspace = vm::onPinBackspace,
                    )
                }
            }
        }

        NavigationRow(
            step = state.step,
            canAdvanceIdentity = state.canAdvanceIdentity,
            submitting = state.submitting,
            onBack = vm::goBack,
            onNext = vm::goNext,
        )
    }
}

@Composable
private fun IdentityStep(
    stationId: String,
    displayName: String,
    error: String?,
    onStationIdChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        LabelText(text = "Step 1 · station identity", color = BrandBlue)
        HeroSerifText(text = "Who is this pump for?", color = BrandBlue)
        Text(
            text = "Type the station ID exactly as given by Balanceè support. " +
                "The display name shows up on the Idle screen and receipts.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )

        OnboardingTextField(
            label = "Station ID",
            value = stationId,
            onValueChange = onStationIdChange,
            helper = "Letters, digits, and dashes. e.g. BLC-LAG-0042.",
        )
        OnboardingTextField(
            label = "Display name",
            value = displayName,
            onValueChange = onDisplayNameChange,
            helper = "Shown on the Idle screen and on every receipt.",
            capitalisation = KeyboardCapitalization.Words,
        )

        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = WarningRed,
            )
        }
    }
}

@Composable
private fun LogoStep(
    logoBytes: ByteArray?,
    onPickLogo: () -> Unit,
    onClearLogo: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        LabelText(text = "Step 2 · station logo", color = BrandBlue)
        HeroSerifText(text = "Add a logo.", color = BrandBlue)
        Text(
            text = "Pick a PNG or JPEG. We'll resize it for you. " +
                "Skip if the station doesn't have one — the display name will be shown instead.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )

        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(RoundedCornerShape(Dimensions.cornerCard))
                .background(SurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = remember(logoBytes) {
                logoBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Station logo preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
            } else {
                Text(
                    text = "No logo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BalanceeButton(
                label = if (logoBytes == null) "Pick from gallery" else "Replace logo",
                onClick = onPickLogo,
                variant = BalanceeButtonVariant.Brand,
                modifier = Modifier.weight(1f),
            )
            if (logoBytes != null) {
                BalanceeButton(
                    label = "Remove",
                    onClick = onClearLogo,
                    variant = BalanceeButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PinStep(
    firstPin: String,
    confirmPin: String,
    subStep: PinSubStep,
    mismatchFlash: Boolean,
    submitting: Boolean,
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LabelText(text = "Step 3 · attendant PIN", color = BrandBlue)
        HeroSerifText(
            text = when (subStep) {
                PinSubStep.Entering -> "Set a 4-digit PIN."
                PinSubStep.Confirming -> "Confirm the PIN."
            },
            color = BrandBlue,
        )
        Text(
            text = if (mismatchFlash) {
                "PINs didn't match. Try again."
            } else {
                "Attendants will type this to authorise FILL UP, AUTHORISE CASH, " +
                    "or CASH RECEIVED. Don't share it with customers."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (mismatchFlash) WarningRed else TextSecondary,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        ) {
            val activePin = when (subStep) {
                PinSubStep.Entering -> firstPin
                PinSubStep.Confirming -> confirmPin
            }
            repeat(4) { index ->
                val filled = index < activePin.length
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(
                            if (filled) BrandBlue
                            else TextTertiary.copy(alpha = 0.4f),
                        ),
                )
            }
        }

        if (submitting) {
            Text(
                text = "Saving…",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        NumericKeypad(
            onDigit = onDigit,
            onBackspace = onBackspace,
            onConfirm = { /* auto-submit when 4 digits land */ },
            confirmEnabled = false,
        )
    }
}

@Composable
private fun NavigationRow(
    step: OnboardingStep,
    canAdvanceIdentity: Boolean,
    submitting: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (step != OnboardingStep.Identity) {
            BalanceeButton(
                label = "Back",
                onClick = onBack,
                variant = BalanceeButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
        }
        when (step) {
            OnboardingStep.Identity -> BalanceeButton(
                label = "Next",
                onClick = onNext,
                variant = BalanceeButtonVariant.Brand,
                enabled = canAdvanceIdentity,
                modifier = Modifier.weight(1f),
            )

            OnboardingStep.Logo -> BalanceeButton(
                label = "Next",
                onClick = onNext,
                variant = BalanceeButtonVariant.Brand,
                modifier = Modifier.weight(1f),
            )

            OnboardingStep.Pin -> {
                // No bottom-bar primary on PIN step — finishing is gated by entering+
                // confirming the 4 digits. The submitting state is reflected inline.
                Box(modifier = Modifier.weight(1f)) {
                    if (submitting) {
                        Text(
                            text = "Saving setup…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    helper: String? = null,
    capitalisation: KeyboardCapitalization = KeyboardCapitalization.Characters,
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = capitalisation),
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
        helper?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun OnboardingScreenIdentityPreview() {
    SmartPumpDisplayTheme {
        // Preview-only — VM not injected. Show the static identity step skeleton.
        Box(modifier = Modifier.fillMaxSize().background(Background).padding(32.dp)) {
            BalanceeCard(borderColor = BrandBlue) {
                IdentityStep(
                    stationId = "BLC-LAG-0042",
                    displayName = "Total Lekki Ph2",
                    error = null,
                    onStationIdChange = {},
                    onDisplayNameChange = {},
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 600)
@Composable
private fun OnboardingScreenPinPreview() {
    SmartPumpDisplayTheme {
        Box(modifier = Modifier.fillMaxSize().background(Background).padding(32.dp)) {
            BalanceeCard(borderColor = BrandBlue) {
                PinStep(
                    firstPin = "12",
                    confirmPin = "",
                    subStep = PinSubStep.Entering,
                    mismatchFlash = false,
                    submitting = false,
                    onDigit = {},
                    onBackspace = {},
                )
            }
        }
    }
}
