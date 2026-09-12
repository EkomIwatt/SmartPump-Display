// Backing VM for the one irreversible step an operator can take: redeeming the activation code.
//
// It lives in its own package rather than inside onboarding because it has two call sites that
// cannot be collapsed into one. A pump installed before a code existed is already provisioned, so
// it will never see the onboarding flow again — its only way in is the operator screen behind the
// attendant PIN. The same is true of every debug build, which auto-provisions a demo identity on
// first boot and therefore never shows onboarding at all.
//
// The presentation of an outcome lives here as [ActivationReport] rather than in the composable,
// because what separates these outcomes is not styling: "the code was refused" and "we never heard
// back" read alike on screen and mean opposite things about whether a second code may be used.
// That distinction is worth a unit test, and a Compose `when` is not testable on the JVM.
package app.balancee.smartpump.display.ui.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.balancee.smartpump.display.domain.network.DeviceIdProvider
import app.balancee.smartpump.display.domain.repository.ActivationOutcome
import app.balancee.smartpump.display.domain.repository.PumpActivationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Shortest code we will send. Guards a stray tap, not the format — the server decides that. */
private const val MIN_CODE_LENGTH = 4
private const val MAX_CODE_LENGTH = 64

data class ActivationUiState(
    /** This unit's permanent identity. Shown so an operator can read it out to support. */
    val deviceId: String = "",
    /** The backend's id for this pump, once known. Null before activation. */
    val pumpId: String? = null,
    val activated: Boolean = false,
    val code: String = "",
    val submitting: Boolean = false,
    /** The last attempt's outcome, or null if none has been made in this session. */
    val report: ActivationReport? = null,
    /** What was actually sent last time, so a *changed* code can be told from a resend. */
    val lastAttemptedCode: String? = null,
) {
    val canSubmit: Boolean
        get() = !submitting && !activated && code.trim().length >= MIN_CODE_LENGTH

    /**
     * True when the operator has typed a *different* code after an outcome that left activation
     * unknown. Warned about rather than blocked: support may well have confirmed that the first
     * code never landed, and an operator who has done the right thing must not be stuck.
     */
    val warnNewCodeAfterUnknown: Boolean
        get() = report != null &&
            !report.mayTryNewCode &&
            report.mayRetrySameCode &&
            code.trim() != lastAttemptedCode
}

/** How an outcome reads to an operator, and what they are allowed to do next. */
data class ActivationReport(
    val tone: ActivationTone,
    val headline: String,
    val detail: String,
    /**
     * Whether re-sending *this same code* is safe.
     *
     * Separate from whether a *different* code may be used, because the two diverge on exactly the
     * outcome that matters: after [ActivationOutcome.Unreachable] the same code is safe to resend
     * (if it never landed it still works; if it did, the server refuses it) while a second code
     * would silently burn a spare on a device that may already be activated.
     */
    val mayRetrySameCode: Boolean,
    /** Whether a freshly issued code is the right next move. */
    val mayTryNewCode: Boolean,
)

enum class ActivationTone { Success, Caution, Failure }

@HiltViewModel
class ActivationViewModel @Inject constructor(
    private val repo: PumpActivationRepository,
    deviceIds: DeviceIdProvider,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        ActivationUiState(
            deviceId = deviceIds.deviceId(),
            pumpId = repo.pumpId,
            activated = repo.isActivated,
        ),
    )
    val ui: StateFlow<ActivationUiState> = _ui.asStateFlow()

    fun setCode(value: String) {
        // Codes are quoted uppercase in the Reference and dashes are the only punctuation seen.
        // Strip everything else so a paste carrying a stray space or quote still sends cleanly.
        val cleaned = value.uppercase()
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(MAX_CODE_LENGTH)
        _ui.update { it.copy(code = cleaned) }
    }

    /**
     * Redeem the typed code. Single-flight: [ActivationUiState.canSubmit] gates the button and this
     * re-check gates a double tap that beats recomposition. A double submit is not merely wasteful
     * here — the second call would race the first into the credential store.
     */
    fun submit() {
        val snapshot = _ui.value
        if (!snapshot.canSubmit) return
        _ui.update { it.copy(submitting = true, report = null) }

        val sent = snapshot.code.trim()
        viewModelScope.launch {
            val outcome = repo.activate(sent)
            _ui.update {
                it.copy(
                    submitting = false,
                    activated = repo.isActivated,
                    pumpId = repo.pumpId,
                    report = outcome.toReport(sentDeviceId = it.deviceId),
                    lastAttemptedCode = sent,
                    // Clear the field only once the code is spent, so a refused one stays on screen
                    // to be compared against the paperwork character by character.
                    code = if (outcome is ActivationOutcome.Activated) "" else it.code,
                )
            }
        }
    }

    /** Dismiss the last report, leaving the field as it is. */
    fun clearReport() {
        _ui.update { it.copy(report = null) }
    }
}

/**
 * Outcome to operator-facing words.
 *
 * Unlike the customer copy settled in OQ #17, every line below is read by someone who can act on
 * it: an installer at first boot, or an attendant behind the PIN. So the detail says what actually
 * happened and names the next move, rather than deferring to "see attendant".
 */
internal fun ActivationOutcome.toReport(sentDeviceId: String): ActivationReport = when (this) {
    is ActivationOutcome.Activated -> ActivationReport(
        tone = ActivationTone.Success,
        headline = "Activated.",
        detail = "This tablet is registered as pump $pumpId and can now talk to Balanceè.",
        mayRetrySameCode = false,
        mayTryNewCode = false,
    )

    is ActivationOutcome.IdentityMismatch -> ActivationReport(
        tone = ActivationTone.Caution,
        headline = "Activated, but the device ID does not match.",
        detail = "Registered as pump $pumpId. This tablet sent $sent and the server recorded " +
            "$returned. The keys were kept and the pump will work, but report the mismatch to " +
            "Balanceè support before this pump goes live.",
        mayRetrySameCode = false,
        mayTryNewCode = false,
    )

    ActivationOutcome.AlreadyActivated -> ActivationReport(
        tone = ActivationTone.Caution,
        headline = "This tablet is already activated.",
        detail = "It already holds keys, and a second code would overwrite them — abandoning the " +
            "pump the backend has on record for this unit. Ask Balanceè support to revoke this " +
            "device first if it really does need activating again.",
        mayRetrySameCode = false,
        mayTryNewCode = false,
    )

    is ActivationOutcome.Refused -> ActivationReport(
        tone = ActivationTone.Failure,
        headline = "The code was not accepted.",
        detail = buildString {
            append(message?.takeIf { it.isNotBlank() } ?: "The server refused the code.")
            code?.let { append(" [").append(it).append("]") }
            httpCode?.let { append(" (HTTP ").append(it).append(")") }
            append(" Nothing has been used up — check the code and try again, or ask for a new one.")
        },
        mayRetrySameCode = true,
        mayTryNewCode = true,
    )

    is ActivationOutcome.Unreachable -> ActivationReport(
        tone = ActivationTone.Caution,
        headline = "No answer from the server.",
        detail = "We do not know whether the code was used (" + detail + "). Check the connection " +
            "and send the same code again — that is safe. Do not enter a different code: ask " +
            "Balanceè support whether device " + sentDeviceId + " has already activated.",
        mayRetrySameCode = true,
        mayTryNewCode = false,
    )

    is ActivationOutcome.CredentialsLost -> ActivationReport(
        tone = ActivationTone.Failure,
        headline = "The code is spent and the keys were lost.",
        detail = detail,
        mayRetrySameCode = false,
        mayTryNewCode = true,
    )
}
