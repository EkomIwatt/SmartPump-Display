// Backing VM for the install-time onboarding flow.
//
// Three steps:
//   1. Identity   — station ID (typed) + display name.
//   2. Logo       — optional PNG, scaled to 512px on the longer side and stored as bytes.
//   3. PIN        — 4-digit PIN entered twice; second entry must match the first.
//
// On Finish, calls StationIdentityRepository.provision(...) which hashes the PIN and writes
// the row. IdentityGateViewModel observes the same row and the gate flips to Provisioned,
// dropping MainActivity into the normal customer/attendant host.
package app.balancee.smartpump.display.ui.onboarding

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.balancee.smartpump.display.domain.repository.StationIdentityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

private const val LOGO_MAX_DIMENSION_PX = 512
private const val MIN_DISPLAY_NAME_LENGTH = 2
private const val MIN_STATION_ID_LENGTH = 3
private const val PIN_LENGTH = 4
private const val TAG = "OnboardingVm"

enum class OnboardingStep { Identity, Logo, Pin }

/** Sub-state inside the PIN step — first entry, then confirm-match. */
enum class PinSubStep { Entering, Confirming }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Identity,
    val stationId: String = "",
    val displayName: String = "",
    val logoBytes: ByteArray? = null,
    val pinSubStep: PinSubStep = PinSubStep.Entering,
    val firstPin: String = "",
    val confirmPin: String = "",
    val pinMismatchFlash: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
) {
    val canAdvanceIdentity: Boolean
        get() = stationId.trim().length >= MIN_STATION_ID_LENGTH &&
            displayName.trim().length >= MIN_DISPLAY_NAME_LENGTH

    // ByteArray equality default-vs-content matters when Compose recomposes on state diffs.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OnboardingUiState) return false
        return step == other.step &&
            stationId == other.stationId &&
            displayName == other.displayName &&
            (logoBytes?.contentEquals(other.logoBytes) ?: (other.logoBytes == null)) &&
            pinSubStep == other.pinSubStep &&
            firstPin == other.firstPin &&
            confirmPin == other.confirmPin &&
            pinMismatchFlash == other.pinMismatchFlash &&
            submitting == other.submitting &&
            error == other.error &&
            finished == other.finished
    }

    override fun hashCode(): Int {
        var r = step.hashCode()
        r = 31 * r + stationId.hashCode()
        r = 31 * r + displayName.hashCode()
        r = 31 * r + (logoBytes?.contentHashCode() ?: 0)
        r = 31 * r + pinSubStep.hashCode()
        r = 31 * r + firstPin.hashCode()
        r = 31 * r + confirmPin.hashCode()
        r = 31 * r + pinMismatchFlash.hashCode()
        r = 31 * r + submitting.hashCode()
        r = 31 * r + (error?.hashCode() ?: 0)
        r = 31 * r + finished.hashCode()
        return r
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: StationIdentityRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(OnboardingUiState())
    val ui: StateFlow<OnboardingUiState> = _ui.asStateFlow()

    fun setStationId(value: String) {
        // Operator-typed ID — uppercase, alphanumerics + dashes only. Trim length to keep
        // the on-screen field tidy; backend constraints are not enforced here.
        val cleaned = value.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(32)
        _ui.update { it.copy(stationId = cleaned, error = null) }
    }

    fun setDisplayName(value: String) {
        _ui.update { it.copy(displayName = value.take(48), error = null) }
    }

    fun loadLogoFromUri(uri: Uri) {
        viewModelScope.launch {
            val bytes = decodeAndScale(uri, context.contentResolver)
            if (bytes == null) {
                _ui.update { it.copy(error = "Couldn't read that image. Try another.") }
            } else {
                _ui.update { it.copy(logoBytes = bytes, error = null) }
            }
        }
    }

    fun clearLogo() {
        _ui.update { it.copy(logoBytes = null, error = null) }
    }

    fun onPinDigit(digit: Int) {
        _ui.update { state ->
            when (state.pinSubStep) {
                PinSubStep.Entering -> {
                    if (state.firstPin.length >= PIN_LENGTH) state
                    else state.copy(
                        firstPin = state.firstPin + digit.toString(),
                        pinMismatchFlash = false,
                    )
                }

                PinSubStep.Confirming -> {
                    if (state.confirmPin.length >= PIN_LENGTH) state
                    else state.copy(
                        confirmPin = state.confirmPin + digit.toString(),
                        pinMismatchFlash = false,
                    )
                }
            }
        }
        // Auto-advance when the active row reaches 4 digits.
        val snap = _ui.value
        when (snap.pinSubStep) {
            PinSubStep.Entering -> if (snap.firstPin.length == PIN_LENGTH) {
                _ui.update { it.copy(pinSubStep = PinSubStep.Confirming) }
            }

            PinSubStep.Confirming -> if (snap.confirmPin.length == PIN_LENGTH) {
                if (snap.firstPin == snap.confirmPin) {
                    finishProvisioning()
                } else {
                    _ui.update {
                        it.copy(
                            pinSubStep = PinSubStep.Entering,
                            firstPin = "",
                            confirmPin = "",
                            pinMismatchFlash = true,
                        )
                    }
                }
            }
        }
    }

    fun onPinBackspace() {
        _ui.update { state ->
            when (state.pinSubStep) {
                PinSubStep.Entering -> state.copy(firstPin = state.firstPin.dropLast(1))
                PinSubStep.Confirming -> state.copy(confirmPin = state.confirmPin.dropLast(1))
            }
        }
    }

    fun resetPinEntry() {
        _ui.update {
            it.copy(
                firstPin = "",
                confirmPin = "",
                pinSubStep = PinSubStep.Entering,
                pinMismatchFlash = false,
            )
        }
    }

    fun goNext() {
        val s = _ui.value
        when (s.step) {
            OnboardingStep.Identity -> {
                if (!s.canAdvanceIdentity) {
                    _ui.update { it.copy(error = "Type a station ID and display name.") }
                    return
                }
                _ui.update { it.copy(step = OnboardingStep.Logo, error = null) }
            }

            OnboardingStep.Logo -> {
                _ui.update { it.copy(step = OnboardingStep.Pin, error = null) }
            }

            OnboardingStep.Pin -> {
                // Pin step finishes via auto-submit on confirm match; goNext is a no-op here.
            }
        }
    }

    fun goBack() {
        val s = _ui.value
        when (s.step) {
            OnboardingStep.Identity -> Unit
            OnboardingStep.Logo -> _ui.update { it.copy(step = OnboardingStep.Identity, error = null) }
            OnboardingStep.Pin -> _ui.update {
                it.copy(
                    step = OnboardingStep.Logo,
                    firstPin = "",
                    confirmPin = "",
                    pinSubStep = PinSubStep.Entering,
                    pinMismatchFlash = false,
                    error = null,
                )
            }
        }
    }

    private fun finishProvisioning() {
        val snap = _ui.value
        if (snap.submitting) return
        _ui.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                repo.provision(
                    stationId = snap.stationId.trim(),
                    displayName = snap.displayName.trim(),
                    logoBytes = snap.logoBytes,
                    rawPin = snap.firstPin,
                )
                _ui.update { it.copy(submitting = false, finished = true) }
            } catch (t: Throwable) {
                Log.e(TAG, "Provisioning failed", t)
                _ui.update {
                    it.copy(
                        submitting = false,
                        error = "Couldn't save setup. Try again.",
                        firstPin = "",
                        confirmPin = "",
                        pinSubStep = PinSubStep.Entering,
                    )
                }
            }
        }
    }

    /**
     * Decode the gallery URI into a PNG byte array, scaling so the longer side ≤ 512px.
     * Runs on Dispatchers.IO to keep the main thread free. Returns null on any failure;
     * the caller surfaces a generic error so the operator can pick a different image.
     */
    private suspend fun decodeAndScale(
        uri: Uri,
        resolver: ContentResolver,
    ): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            // 1) Probe bounds without decoding the pixels — gives width/height for sampling.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val (w, h) = bounds.outWidth to bounds.outHeight
            if (w <= 0 || h <= 0) return@runCatching null

            // 2) Compute a power-of-two inSampleSize that roughly fits LOGO_MAX_DIMENSION_PX.
            var sample = 1
            while ((w / sample) > LOGO_MAX_DIMENSION_PX * 2 ||
                (h / sample) > LOGO_MAX_DIMENSION_PX * 2
            ) {
                sample *= 2
            }

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@runCatching null

            // 3) Final scale to fit LOGO_MAX_DIMENSION_PX on the longer side.
            val scale = (LOGO_MAX_DIMENSION_PX.toFloat() /
                maxOf(decoded.width, decoded.height)).coerceAtMost(1f)
            val finalBitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt(),
                    (decoded.height * scale).toInt(),
                    true,
                )
            } else decoded

            ByteArrayOutputStream().use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }.onFailure { Log.e(TAG, "Logo decode failed", it) }.getOrNull()
    }
}
