// Unified mode + amount + method selection — STATE 1 of the customer journey.
//
// Phase 6b restructure (2026-05-23): collapses three previously-separate screens
// (ModeSelect / PrepayAmountSelect / PrepayMethodSelect) into one progressive-reveal
// screen matching `docs/Strict design screens/...` and `docs/compare/required.png`.
//
// Boss edits (2026-05-26):
//   - "By amount (₦) / By litres (L)" segmented toggle atop the SELECT AMOUNT section.
//   - ₦/L price shown beside the SELECT AMOUNT header (was a suffix on the preview line).
//   - Live conversion preview in a gold tinted panel (dispensing-ledger style).
//   - Custom keypad has a decimal key instead of ✓ — the bottom CONFIRM button is the
//     single commit, so the custom value commits LIVE as it's typed (when valid).
//   - NFC dropped from the pre-pay method list for V1.
// The toggle, the litre selection, and the decimal entry are all LOCAL UI state — every
// path commits naira into ModeSelect.amountNaira (litres × ₦/L, rounded), so the state
// machine, persistence, and dispensing are untouched.
//
// Reveal logic:
//   - Mode tiles always visible (PRE-PAY / FILL UP).
//   - SELECT AMOUNT section shows only when mode == PRE_PAY.
//   - PAY WITH section shows only when a valid amount is set.
//   - Bottom CONFIRM button enables when:
//       PRE_PAY → mode + amount + method all set and the custom entry isn't mid-invalid
//       FILL_UP → mode set
package app.balancee.smartpump.display.ui.customer

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.model.TransactionState
import app.balancee.smartpump.display.ui.components.BalanceeButton
import app.balancee.smartpump.display.ui.components.BalanceeButtonVariant
import app.balancee.smartpump.display.ui.components.HeroSerifText
import app.balancee.smartpump.display.ui.components.LabelText
import app.balancee.smartpump.display.ui.components.NumericKeypad
import app.balancee.smartpump.display.ui.components.SelectableTile
import app.balancee.smartpump.display.ui.theme.ActiveCyan
import app.balancee.smartpump.display.ui.theme.Background
import app.balancee.smartpump.display.ui.theme.BorderSubtle
import app.balancee.smartpump.display.ui.theme.BrandBlue
import app.balancee.smartpump.display.ui.theme.Dimensions
import app.balancee.smartpump.display.ui.theme.heroSerifFamily
import app.balancee.smartpump.display.ui.theme.OnBrand
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.Surface
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary
import app.balancee.smartpump.display.ui.theme.TextTertiary
import app.balancee.smartpump.display.ui.util.appendDecimal
import app.balancee.smartpump.display.ui.util.appendDigit
import app.balancee.smartpump.display.ui.util.formatNaira
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val PRESET_AMOUNTS_NAIRA: List<Int> = listOf(2_000, 5_000, 10_000, 20_000, 50_000)
private const val CUSTOM_MIN_NAIRA = 200
private const val CUSTOM_MAX_NAIRA = 200_000

private val PRESET_LITRES: List<Int> = listOf(5, 10, 20, 30, 50)
private const val CUSTOM_MIN_LITRES = 1
private const val CUSTOM_MAX_LITRES = 200

/** How the customer is entering the pre-pay amount. Local UI state only — both paths
 *  commit naira into [TransactionState.ModeSelect.amountNaira]. */
private enum class AmountEntryMode { AMOUNT, LITRES }

/**
 * Methods offered in the PAY WITH section. The CASH option is informational — confirming
 * it short-circuits to onCancel() and the attendant takes over via the swipe-up overlay's
 * AUTHORISE CASH action. Phase 6c will replace the short-circuit with a proper
 * "see attendant" state so the customer screen acknowledges the choice instead of
 * silently returning to Idle.
 *
 * NFC dropped for V1 (boss edit 2026-05-26) — PaymentMethod.NFC_CARD is kept in the enum
 * (persisted txns + exhaustive `when`s reference it) and can be re-added here trivially.
 */
private val PRE_PAY_METHODS: List<PaymentMethod> = listOf(
    PaymentMethod.BALANCEE_APP,
    PaymentMethod.BANK_QR_TRANSFER,
    PaymentMethod.USSD,
    PaymentMethod.CASH_SEE_ATTENDANT,
)

@Composable
fun ModeSelectScreen(
    state: TransactionState.ModeSelect,
    displayName: String,
    logoBytes: ByteArray?,
    priceKoboPerLitre: Long,
    onModeTileTap: (TransactionMode) -> Unit,
    onAmountTileTap: (Int) -> Unit,
    onMethodTileTap: (PaymentMethod) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Local-only state — not persisted. If the pump reboots mid-selection, resume lands on
    // plain ModeSelect and the customer re-enters; the toggle defaults back to "By amount".
    var entryMode by remember { mutableStateOf(AmountEntryMode.AMOUNT) }
    var customKeypadOpen by remember { mutableStateOf(false) }
    // The custom value as typed — a string so it can hold a decimal point mid-entry.
    var customTyped by remember { mutableStateOf("") }
    // Which litre value is selected (preset only) — drives the litre-tile highlight + the
    // live preview when the keypad is closed. amountNaira remains the single committed value.
    var selectedLitres by remember { mutableStateOf<Int?>(null) }

    val customTypedValue: Double? = customTyped.toDoubleOrNull()
    val customValid = when (entryMode) {
        AmountEntryMode.AMOUNT ->
            customTypedValue != null && customTypedValue >= CUSTOM_MIN_NAIRA && customTypedValue <= CUSTOM_MAX_NAIRA
        AmountEntryMode.LITRES ->
            customTypedValue != null && customTypedValue >= CUSTOM_MIN_LITRES && customTypedValue <= CUSTOM_MAX_LITRES
    }
    // The custom entry is "ok" when the keypad is closed, or open with a valid value. Used
    // to gate the bottom CONFIRM and the PAY WITH reveal so a mid-typed invalid value can't
    // be confirmed (no keypad ✓ to commit a discrete value any more — commit is live).
    val customEntryOk = !customKeypadOpen || customValid

    // Live commit: whenever the typed value is valid, push the equivalent naira into the
    // state so the preview, the PAY WITH section and CONFIRM stay in lockstep with the keys.
    fun commitCustom(typed: String) {
        val v = typed.toDoubleOrNull() ?: return
        when (entryMode) {
            AmountEntryMode.AMOUNT ->
                if (v >= CUSTOM_MIN_NAIRA && v <= CUSTOM_MAX_NAIRA) onAmountTileTap(v.roundToInt())
            AmountEntryMode.LITRES ->
                if (v >= CUSTOM_MIN_LITRES && v <= CUSTOM_MAX_LITRES) {
                    onAmountTileTap((v * priceKoboPerLitre / 100.0).roundToInt())
                }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        HeaderRow(
            displayName = displayName,
            logoBytes = logoBytes,
        )
        Text(
            text = "STATE 1 — CHOOSE MODE",
            style = MaterialTheme.typography.labelLarge.copy(
                color = TextTertiary,
                letterSpacing = 2.sp,
            ),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ModeSection(
                selectedMode = state.mode,
                onModeTileTap = { mode ->
                    customKeypadOpen = false
                    customTyped = ""
                    selectedLitres = null
                    onModeTileTap(mode)
                },
            )

            if (state.mode == TransactionMode.PRE_PAY) {
                AmountSection(
                    entryMode = entryMode,
                    priceKoboPerLitre = priceKoboPerLitre,
                    selectedAmount = state.amountKobo?.let { (it / 100).toInt() },
                    selectedLitres = selectedLitres,
                    customKeypadOpen = customKeypadOpen,
                    customTyped = customTyped,
                    customValid = customValid,
                    onEntryModeChange = { newMode ->
                        if (newMode != entryMode) {
                            entryMode = newMode
                            customKeypadOpen = false
                            customTyped = ""
                            selectedLitres = null
                        }
                    },
                    onAmountPresetTap = { naira ->
                        customKeypadOpen = false
                        customTyped = ""
                        selectedLitres = null
                        onAmountTileTap(naira)
                    },
                    onLitrePresetTap = { litres ->
                        customKeypadOpen = false
                        customTyped = ""
                        selectedLitres = litres
                        onAmountTileTap(litresToNaira(litres, priceKoboPerLitre))
                    },
                    onCustomTap = {
                        customKeypadOpen = true
                        customTyped = ""
                        selectedLitres = null
                    },
                    onCustomDigit = { digit ->
                        val next = appendDigit(customTyped, digit)
                        if (next != customTyped) {
                            customTyped = next
                            commitCustom(next)
                        }
                    },
                    onCustomDecimal = {
                        val next = appendDecimal(customTyped)
                        if (next != customTyped) {
                            customTyped = next
                            commitCustom(next)
                        }
                    },
                    onCustomBackspace = {
                        // Don't re-commit on delete — amountNaira keeps its last valid value
                        // but CONFIRM/PAY WITH are gated by customEntryOk until it's valid again.
                        if (customTyped.isNotEmpty()) customTyped = customTyped.dropLast(1)
                    },
                )

                if (state.amountKobo != null && customEntryOk) {
                    MethodSection(
                        selectedMethod = state.method,
                        onMethodTileTap = onMethodTileTap,
                    )
                }
            }
        }

        BottomBar(
            state = state,
            customEntryOk = customEntryOk,
            onConfirm = onConfirm,
            onCancel = onCancel,
        )
    }
}

@Composable
private fun HeaderRow(
    displayName: String,
    logoBytes: ByteArray?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Left: station identity (logo if uploaded, else name in Playfair).
        val bitmap = remember(logoBytes) {
            logoBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "$displayName logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(36.dp),
            )
        } else {
            HeroSerifText(
                text = displayName.ifBlank { "Smart pump" },
                color = BrandBlue,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    fontFamily = heroSerifFamily(),
                ),
            )
        }
        // Right: PUMP 1 chip — brand-blue per Phase 6b feedback (was gold; updated to match
        // the rest of the brand-blue chrome on the customer-side header).
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Dimensions.cornerChip))
                .background(BrandBlue.copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = "PUMP 1",
                style = MaterialTheme.typography.labelLarge.copy(
                    color = BrandBlue,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                ),
            )
        }
    }
}

/** Section header. With [trailingText] set, the value sits directly beside the label
 *  (amber) rather than spanning to the far edge — used to surface the ₦/L price where it's
 *  easy to spot next to SELECT AMOUNT. */
@Composable
private fun SectionHeader(text: String, trailingText: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelLarge.copy(
                color = TextSecondary,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        if (trailingText != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "· $trailingText",
                style = MaterialTheme.typography.labelLarge.copy(
                    color = PrimaryGold,
                    letterSpacing = 0.5.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}

@Composable
private fun ModeSection(
    selectedMode: TransactionMode?,
    onModeTileTap: (TransactionMode) -> Unit,
) {
    Column {
        SectionHeader("How do you want to fuel?")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.threeCardGap),
        ) {
            ModeTile(
                selected = selectedMode == TransactionMode.PRE_PAY,
                glyph = "⚡",
                glyphColor = PrimaryGold,
                title = "PRE-PAY",
                subtitle = "Fixed amount",
                onClick = { onModeTileTap(TransactionMode.PRE_PAY) },
                modifier = Modifier.weight(1f),
            )
            ModeTile(
                selected = selectedMode == TransactionMode.FILL_UP,
                glyph = "⛽",  // ⛽ fuel pump
                glyphColor = ActiveCyan,
                title = "FILL UP",
                subtitle = "Pay after",
                onClick = { onModeTileTap(TransactionMode.FILL_UP) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ModeTile(
    selected: Boolean,
    glyph: String,
    glyphColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SelectableTile(
        selected = selected,
        onClick = onClick,
        modifier = modifier.height(120.dp),
        contentPadding = 16.dp,
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = glyph,
                fontSize = 26.sp,
                color = if (selected) OnBrand else glyphColor,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    color = if (selected) OnBrand else TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = if (selected) OnBrand.copy(alpha = 0.75f) else TextSecondary,
                ),
            )
        }
    }
}

@Composable
private fun AmountSection(
    entryMode: AmountEntryMode,
    priceKoboPerLitre: Long,
    selectedAmount: Int?,
    selectedLitres: Int?,
    customKeypadOpen: Boolean,
    customTyped: String,
    customValid: Boolean,
    onEntryModeChange: (AmountEntryMode) -> Unit,
    onAmountPresetTap: (Int) -> Unit,
    onLitrePresetTap: (Int) -> Unit,
    onCustomTap: () -> Unit,
    onCustomDigit: (Int) -> Unit,
    onCustomDecimal: () -> Unit,
    onCustomBackspace: () -> Unit,
) {
    Column {
        SectionHeader(
            text = "Select amount",
            trailingText = if (priceKoboPerLitre > 0L) "${formatNaira(priceKoboPerLitre)}/L" else null,
        )
        EntryModeToggle(entryMode = entryMode, onChange = onEntryModeChange)
        Spacer(Modifier.height(12.dp))

        when (entryMode) {
            AmountEntryMode.AMOUNT -> {
                val selIdx = if (customKeypadOpen) {
                    null
                } else {
                    PRESET_AMOUNTS_NAIRA.indexOf(selectedAmount).takeIf { it >= 0 }
                }
                PresetGrid(
                    presetLabels = PRESET_AMOUNTS_NAIRA.map { formatAmountShort(it) },
                    selectedIndex = selIdx,
                    customSelected = customKeypadOpen,
                    onPresetTap = { i -> onAmountPresetTap(PRESET_AMOUNTS_NAIRA[i]) },
                    onCustomTap = onCustomTap,
                )
            }

            AmountEntryMode.LITRES -> {
                val selIdx = if (customKeypadOpen) {
                    null
                } else {
                    PRESET_LITRES.indexOf(selectedLitres).takeIf { it >= 0 }
                }
                PresetGrid(
                    presetLabels = PRESET_LITRES.map { "$it L" },
                    selectedIndex = selIdx,
                    customSelected = customKeypadOpen,
                    onPresetTap = { i -> onLitrePresetTap(PRESET_LITRES[i]) },
                    onCustomTap = onCustomTap,
                )
            }
        }

        // Live conversion preview in a gold tinted panel (dispensing-ledger styling).
        // Amount mode → "≈ X.XX L"; litres mode → "= ₦X,XXX". The ₦/L sits in the header.
        val previewText = amountPreview(
            entryMode = entryMode,
            priceKoboPerLitre = priceKoboPerLitre,
            selectedAmount = selectedAmount,
            selectedLitres = selectedLitres,
            customKeypadOpen = customKeypadOpen,
            customTyped = customTyped,
            customValid = customValid,
        )
        if (previewText != null) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dimensions.cornerCodePanel))
                    .background(PrimaryGold.copy(alpha = 0.07f))
                    .border(
                        Dimensions.borderWidth,
                        PrimaryGold.copy(alpha = 0.30f),
                        RoundedCornerShape(Dimensions.cornerCodePanel),
                    )
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        color = PrimaryGold,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (customKeypadOpen) {
            Spacer(Modifier.height(12.dp))
            // Inline keypad — typed value displayed above, NumericKeypad below. The keypad's
            // ✓ is replaced by a decimal key; commit is live (see commitCustom in the parent).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dimensions.cornerCard))
                    .background(Surface)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val display = when (entryMode) {
                    AmountEntryMode.AMOUNT -> formatTypedAmount(customTyped)
                    AmountEntryMode.LITRES ->
                        if (customTyped.isEmpty()) "__ L" else "$customTyped L"
                }
                Text(
                    text = display,
                    style = MaterialTheme.typography.displaySmall.copy(
                        color = if (customValid) PrimaryGold else TextSecondary,
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                val limits = when (entryMode) {
                    AmountEntryMode.AMOUNT ->
                        "Min ₦$CUSTOM_MIN_NAIRA · Max ₦${formatAmountShort(CUSTOM_MAX_NAIRA)}"
                    AmountEntryMode.LITRES ->
                        "Min $CUSTOM_MIN_LITRES L · Max $CUSTOM_MAX_LITRES L"
                }
                Text(
                    text = limits,
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                NumericKeypad(
                    onDigit = onCustomDigit,
                    onBackspace = onCustomBackspace,
                    onDecimal = onCustomDecimal,
                    showConfirmKey = false,
                    showDecimalKey = true,
                )
            }
        }
    }
}

@Composable
private fun EntryModeToggle(
    entryMode: AmountEntryMode,
    onChange: (AmountEntryMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimensions.cornerCard))
            .background(Surface)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToggleSegment(
            label = "By amount (₦)",
            selected = entryMode == AmountEntryMode.AMOUNT,
            onClick = { onChange(AmountEntryMode.AMOUNT) },
            modifier = Modifier.weight(1f),
        )
        ToggleSegment(
            label = "By litres (L)",
            selected = entryMode == AmountEntryMode.LITRES,
            onClick = { onChange(AmountEntryMode.LITRES) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ToggleSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Dimensions.cornerChip))
            .background(if (selected) BrandBlue else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                color = if (selected) OnBrand else TextSecondary,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            ),
        )
    }
}

/**
 * 2×3 tile grid: five presets + a trailing Custom tile. Shared by the amount and litres
 * entry modes — only the labels and selection index differ.
 */
@Composable
private fun PresetGrid(
    presetLabels: List<String>,
    selectedIndex: Int?,
    customSelected: Boolean,
    onPresetTap: (Int) -> Unit,
    onCustomTap: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (0..2).forEach { i ->
                AmountTile(
                    selected = selectedIndex == i,
                    label = presetLabels[i],
                    onClick = { onPresetTap(i) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (3..4).forEach { i ->
                AmountTile(
                    selected = selectedIndex == i,
                    label = presetLabels[i],
                    onClick = { onPresetTap(i) },
                    modifier = Modifier.weight(1f),
                )
            }
            AmountTile(
                selected = customSelected,
                label = "Custom",
                onClick = onCustomTap,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AmountTile(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SelectableTile(
        selected = selected,
        onClick = onClick,
        modifier = modifier.height(56.dp),
        contentPadding = 8.dp,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge.copy(
                    color = if (selected) OnBrand else TextPrimary,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

@Composable
private fun MethodSection(
    selectedMethod: PaymentMethod?,
    onMethodTileTap: (PaymentMethod) -> Unit,
) {
    Column {
        SectionHeader("Pay with")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PRE_PAY_METHODS.forEach { method ->
                MethodTile(
                    selected = selectedMethod == method,
                    method = method,
                    onClick = { onMethodTileTap(method) },
                )
            }
        }
    }
}

@Composable
private fun MethodTile(
    selected: Boolean,
    method: PaymentMethod,
    onClick: () -> Unit,
) {
    SelectableTile(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        contentPadding = 16.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Leading dot — open ring when unselected, filled when selected.
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (selected) OnBrand else BorderSubtle),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = methodLabel(method),
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = if (selected) OnBrand else TextPrimary,
                    ),
                )
            }
            methodBadge(method)?.let { badge ->
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = if (selected) OnBrand else PrimaryGold,
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
    }
}

private fun methodLabel(method: PaymentMethod): String = when (method) {
    PaymentMethod.BALANCEE_APP -> "Balanceè App"
    PaymentMethod.BANK_QR_TRANSFER -> "Bank QR / Transfer"
    PaymentMethod.NFC_CARD -> "NFC card"
    PaymentMethod.USSD -> "USSD · *737#"
    PaymentMethod.CASH_SEE_ATTENDANT -> "Cash — see attendant"
}

/** Optional badge displayed at the right end of a method tile (e.g. "FASTEST"). */
private fun methodBadge(method: PaymentMethod): String? = when (method) {
    PaymentMethod.BALANCEE_APP -> "FASTEST"
    PaymentMethod.USSD -> "WORKS ON 2G"
    else -> null
}

@Composable
private fun BottomBar(
    state: TransactionState.ModeSelect,
    customEntryOk: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val canConfirm = when (state.mode) {
        TransactionMode.FILL_UP -> true
        TransactionMode.PRE_PAY -> state.amountKobo != null && state.method != null && customEntryOk
        null -> false
    }
    val confirmLabel = when {
        state.mode == TransactionMode.FILL_UP -> "Confirm FILL UP"
        state.mode == TransactionMode.PRE_PAY && state.amountKobo != null ->
            "Confirm ${formatNaira(state.amountKobo)}"
        else -> "Confirm"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BalanceeButton(
            label = "Cancel",
            onClick = onCancel,
            variant = BalanceeButtonVariant.Secondary,
            modifier = Modifier.weight(1f),
        )
        BalanceeButton(
            label = confirmLabel,
            onClick = onConfirm,
            variant = BalanceeButtonVariant.Brand,
            enabled = canConfirm,
            modifier = Modifier.weight(2f),
        )
    }
}

/** litres × ₦/L, rounded to the nearest whole naira (the pre-pay amount is whole-naira).
 *  The single conversion point for litres mode. */
private fun litresToNaira(litres: Int, priceKoboPerLitre: Long): Int =
    (litres * priceKoboPerLitre / 100.0).roundToInt()

/**
 * The live preview string under the preset grid, or null when there's no active value yet.
 * Amount mode shows the litres the money buys; litres mode shows the naira the litres cost.
 * The ₦/L price is shown in the section header, not repeated here.
 */
private fun amountPreview(
    entryMode: AmountEntryMode,
    priceKoboPerLitre: Long,
    selectedAmount: Int?,
    selectedLitres: Int?,
    customKeypadOpen: Boolean,
    customTyped: String,
    customValid: Boolean,
): String? {
    if (priceKoboPerLitre <= 0L) return null
    return when (entryMode) {
        AmountEntryMode.AMOUNT -> {
            val naira = if (customKeypadOpen) {
                customTyped.toDoubleOrNull()?.takeIf { customValid }
            } else {
                selectedAmount?.toDouble()
            } ?: return null
            val litres = naira * 100.0 / priceKoboPerLitre
            "≈ ${String.format(Locale.UK, "%.2f", litres)} L"
        }

        AmountEntryMode.LITRES -> {
            val litres = if (customKeypadOpen) {
                customTyped.toDoubleOrNull()?.takeIf { customValid }
            } else {
                selectedLitres?.toDouble()
            } ?: return null
            "= ${formatNaira((litres * priceKoboPerLitre).roundToLong())}"
        }
    }
}

private fun formatTypedAmount(typed: String): String {
    if (typed.isEmpty()) return "₦____"
    val dot = typed.indexOf('.')
    val intPart = if (dot >= 0) typed.substring(0, dot) else typed
    val decPart = if (dot >= 0) typed.substring(dot) else ""
    val groupedInt = intPart.toLongOrNull()
        ?.let { NumberFormat.getInstance(Locale.UK).format(it) }
        ?: intPart.ifEmpty { "0" }
    return "₦$groupedInt$decPart"
}

private fun formatAmountShort(naira: Int): String = when {
    naira >= 1_000 && naira % 1_000 == 0 -> "₦${naira / 1_000}k"
    else -> "₦" + formatGrouped(naira.toString())
}

private fun formatGrouped(digits: String): String {
    val n = digits.toLongOrNull() ?: return digits
    return NumberFormat.getInstance(Locale.UK).format(n)
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 720)
@Composable
private fun ModeSelectScreenPrepayPreview() {
    SmartPumpDisplayTheme {
        ModeSelectScreen(
            state = TransactionState.ModeSelect(
                mode = TransactionMode.PRE_PAY,
                amountKobo = 200_000,
                method = PaymentMethod.BALANCEE_APP,
            ),
            displayName = "Total Lekki Ph2",
            logoBytes = null,
            priceKoboPerLitre = 87_000,
            onModeTileTap = {},
            onAmountTileTap = {},
            onMethodTileTap = {},
            onConfirm = {},
            onCancel = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0A, widthDp = 1024, heightDp = 720)
@Composable
private fun ModeSelectScreenInitialPreview() {
    SmartPumpDisplayTheme {
        ModeSelectScreen(
            state = TransactionState.ModeSelect(),
            displayName = "Total Lekki Ph2",
            logoBytes = null,
            priceKoboPerLitre = 87_000,
            onModeTileTap = {},
            onAmountTileTap = {},
            onMethodTileTap = {},
            onConfirm = {},
            onCancel = {},
        )
    }
}
