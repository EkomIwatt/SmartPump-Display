// Unified mode + amount + method selection — STATE 1 of the customer journey.
//
// Phase 6b restructure (2026-05-23): collapses three previously-separate screens
// (ModeSelect / PrepayAmountSelect / PrepayMethodSelect) into one progressive-reveal
// screen matching `docs/Strict design screens/...` and `docs/compare/required.png`.
//
// Reveal logic:
//   - Mode tiles always visible (PRE-PAY / FILL UP).
//   - SELECT AMOUNT section shows only when mode == PRE_PAY.
//   - PAY WITH section shows only when an amount is set.
//   - Bottom CONFIRM button enables when:
//       PRE_PAY → mode + amount + method all set
//       FILL_UP → mode set
//
// Custom amount: tapping the Custom tile reveals an inline NumericKeypad; the typed value
// commits on the keypad's ✓ key, sets ModeSelect.amountNaira, hides the keypad, and
// reveals the PAY WITH section as usual.
package app.balancee.smartpump.display.ui.customer

import android.graphics.BitmapFactory
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
import app.balancee.smartpump.display.ui.theme.OnBrand
import app.balancee.smartpump.display.ui.theme.PrimaryGold
import app.balancee.smartpump.display.ui.theme.SmartPumpDisplayTheme
import app.balancee.smartpump.display.ui.theme.TextPrimary
import app.balancee.smartpump.display.ui.theme.TextSecondary
import app.balancee.smartpump.display.ui.theme.TextTertiary
import java.text.NumberFormat
import java.util.Locale

private val PRESET_AMOUNTS_NAIRA: List<Int> = listOf(2_000, 5_000, 10_000, 20_000, 50_000)
private const val CUSTOM_MIN_NAIRA = 200
private const val CUSTOM_MAX_NAIRA = 200_000

/** Methods offered in the PAY WITH section. Pre-pay flow only — cash routes via attendant. */
private val PRE_PAY_METHODS: List<PaymentMethod> = listOf(
    PaymentMethod.BALANCEE_APP,
    PaymentMethod.BANK_QR_TRANSFER,
    PaymentMethod.NFC_CARD,
    PaymentMethod.USSD,
)

@Composable
fun ModeSelectScreen(
    state: TransactionState.ModeSelect,
    displayName: String,
    logoBytes: ByteArray?,
    onModeTileTap: (TransactionMode) -> Unit,
    onAmountTileTap: (Int) -> Unit,
    onMethodTileTap: (PaymentMethod) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Local-only state — the customer is mid-typing a custom amount. Not persisted; if the
    // pump reboots while the keypad is open, resume lands on plain ModeSelect (mode=PRE_PAY,
    // amount=null) and the keypad reopens by tapping Custom again.
    var customKeypadOpen by remember { mutableStateOf(false) }
    var customTyped by remember { mutableStateOf("") }
    val customTypedInt = customTyped.toIntOrNull() ?: 0
    val customValid = customTypedInt in CUSTOM_MIN_NAIRA..CUSTOM_MAX_NAIRA

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
                    onModeTileTap(mode)
                },
            )

            if (state.mode == TransactionMode.PRE_PAY) {
                AmountSection(
                    selectedAmount = state.amountNaira,
                    customKeypadOpen = customKeypadOpen,
                    customTyped = customTyped,
                    customValid = customValid,
                    onPresetTap = { naira ->
                        customKeypadOpen = false
                        customTyped = ""
                        onAmountTileTap(naira)
                    },
                    onCustomTap = {
                        customKeypadOpen = true
                        customTyped = ""
                    },
                    onCustomDigit = { digit ->
                        if (customTyped.length < 6) customTyped += digit.toString()
                    },
                    onCustomBackspace = {
                        if (customTyped.isNotEmpty()) customTyped = customTyped.dropLast(1)
                    },
                    onCustomConfirm = {
                        if (customValid) {
                            onAmountTileTap(customTypedInt)
                            customKeypadOpen = false
                            customTyped = ""
                        }
                    },
                )

                if (state.amountNaira != null && !customKeypadOpen) {
                    MethodSection(
                        selectedMethod = state.method,
                        onMethodTileTap = onMethodTileTap,
                    )
                }
            }
        }

        BottomBar(
            state = state,
            customKeypadOpen = customKeypadOpen,
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
                    fontFamily = app.balancee.smartpump.display.ui.theme.HeroSerif,
                ),
            )
        }
        // Right: PUMP 1 chip (gold border, gold text).
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Dimensions.cornerChip))
                .background(PrimaryGold.copy(alpha = 0.10f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = "PUMP 1",
                style = MaterialTheme.typography.labelLarge.copy(
                    color = PrimaryGold,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                ),
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelLarge.copy(
            color = TextSecondary,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        modifier = Modifier.padding(bottom = 8.dp),
    )
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
    glyphColor: androidx.compose.ui.graphics.Color,
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
    selectedAmount: Int?,
    customKeypadOpen: Boolean,
    customTyped: String,
    customValid: Boolean,
    onPresetTap: (Int) -> Unit,
    onCustomTap: () -> Unit,
    onCustomDigit: (Int) -> Unit,
    onCustomBackspace: () -> Unit,
    onCustomConfirm: () -> Unit,
) {
    Column {
        SectionHeader("Select amount")
        // 2 rows × 3 cols.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PRESET_AMOUNTS_NAIRA.take(3).forEach { naira ->
                    AmountTile(
                        selected = !customKeypadOpen && selectedAmount == naira,
                        label = formatAmountShort(naira),
                        onClick = { onPresetTap(naira) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PRESET_AMOUNTS_NAIRA.drop(3).forEach { naira ->
                    AmountTile(
                        selected = !customKeypadOpen && selectedAmount == naira,
                        label = formatAmountShort(naira),
                        onClick = { onPresetTap(naira) },
                        modifier = Modifier.weight(1f),
                    )
                }
                AmountTile(
                    selected = customKeypadOpen,
                    label = "Custom",
                    onClick = onCustomTap,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (customKeypadOpen) {
            Spacer(Modifier.height(12.dp))
            // Inline keypad — typed value displayed above, NumericKeypad below.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dimensions.cornerCard))
                    .background(app.balancee.smartpump.display.ui.theme.Surface)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (customTyped.isEmpty()) "₦____" else "₦" + formatGrouped(customTyped),
                    style = MaterialTheme.typography.displaySmall.copy(
                        color = if (customValid) PrimaryGold else TextSecondary,
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Min ₦$CUSTOM_MIN_NAIRA · Max ₦${formatAmountShort(CUSTOM_MAX_NAIRA)}",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                NumericKeypad(
                    onDigit = onCustomDigit,
                    onBackspace = onCustomBackspace,
                    onConfirm = onCustomConfirm,
                    confirmEnabled = customValid,
                )
            }
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
    PaymentMethod.CASH_SEE_ATTENDANT -> "Cash"
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
    customKeypadOpen: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val canConfirm = when (state.mode) {
        TransactionMode.FILL_UP -> true
        TransactionMode.PRE_PAY -> state.amountNaira != null && state.method != null && !customKeypadOpen
        null -> false
    }
    val confirmLabel = when {
        state.mode == TransactionMode.FILL_UP -> "Confirm FILL UP"
        state.mode == TransactionMode.PRE_PAY && state.amountNaira != null ->
            "Confirm ₦${formatGrouped(state.amountNaira.toString())}"
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
                amountNaira = 2_000,
                method = PaymentMethod.BALANCEE_APP,
            ),
            displayName = "Total Lekki Ph2",
            logoBytes = null,
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
            onModeTileTap = {},
            onAmountTileTap = {},
            onMethodTileTap = {},
            onConfirm = {},
            onCancel = {},
        )
    }
}
