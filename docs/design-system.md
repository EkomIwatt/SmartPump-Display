## Design system — IMPORTANT, follow this exactly

The visual language is "industrial brutalism with warmth" — like a fuel pump display crossed with a modern fintech app. NOT Material Design defaults.

**Color palette (dark theme only — no light theme needed):**
- Background: `#0A0A0F` (near-black with slight blue tint)
- Surface: `#13131A` (cards, elevated elements)
- Surface variant: `#1C1C26` (subtle elevation)
- Border subtle: `#2A2A38`
- Primary (action amber): `#F5A623` (the AUTHORISE button color)
- Primary on-color: `#0A0A0F`
- Active cyan (dispensing/filling): `#4FD1C5`
- Success green: `#48BB78`
- Warning red: `#F56565`
- Text primary: `#F7F7F8`
- Text secondary: `#A0A0AB`
- Text tertiary: `#5A5A6B`

**Typography:**
- Display (litre numbers, naira amounts): JetBrains Mono or Space Mono — monospace, this is critical for the industrial feel
- Headings: Inter or system default sans-serif, weight 600
- Body: Inter, weight 400
- Labels (small all-caps): Inter, weight 500, letter-spacing 0.1em, all uppercase
- Numeric displays should be EXTREMELY LARGE on the main screens — think 120sp+ for the live litre count

**Layout rules:**
- Full-bleed, no app bar, no nav bar (kiosk)
- Generous padding (32dp default screen padding)
- Cards have 1dp borders in border-subtle color, NOT shadows (shadows look soft; we want crisp/industrial)
- Corner radius: 12dp on cards, 8dp on buttons
- Buttons are LARGE (minimum 64dp height) — fingers, gloves, daylight
- Use ALL CAPS for action button labels
- High contrast everywhere — this screen will be viewed in direct Nigerian sunlight

**Component examples to build:**
- `LitreDisplay` — giant monospace number, e.g. "11.49 L" with the "L" in smaller text-secondary
- `PrimaryButton` — amber background, dark text, 64dp tall, all-caps label
- `BalancedCard` — surface background, 1dp border, 12dp radius, 24dp internal padding

## State machine — the core domain logic

```kotlin
sealed class TransactionState {
    object Idle : TransactionState()
    object ModeSelect : TransactionState()
    data class AmountSelect(val mode: TransactionMode) : TransactionState()
    data class PaymentMethodSelect(val mode: TransactionMode, val amountKobo: Long) : TransactionState()
    data class AwaitingPayment(val mode: TransactionMode, val amountKobo: Long, val method: PaymentMethod) : TransactionState()
    data class FillUpConfirm(val authorisedByAttendant: Boolean) : TransactionState()
    data class Dispensing(val mode: TransactionMode, val amountKobo: Long?, val litresAuthorised: Double?) : TransactionState()
    data class AwaitingCashConfirm(val litresDispensed: Double, val amountDueKobo: Long) : TransactionState()
    data class Complete(val transactionId: String, val litres: Double, val amountKobo: Long) : TransactionState()
    data class Error(val message: String, val recoverable: Boolean) : TransactionState()
}
```

Transitions:
- IDLE → tap "Start Transaction" → MODE_SELECT
- MODE_SELECT → "Pre-Pay" → AMOUNT_SELECT
- MODE_SELECT → "Fill Up" → FILL_UP_CONFIRM (waits for attendant)
- AMOUNT_SELECT → enter amount + confirm → PAYMENT_METHOD_SELECT
- PAYMENT_METHOD_SELECT → pick NFC/QR/USSD → AWAITING_PAYMENT
- AWAITING_PAYMENT → payment success → DISPENSING
- DISPENSING → litres reached OR nozzle shutoff (3s no pulses) → COMPLETE (or AWAITING_CASH_CONFIRM for fill-up)
- Any state → power cut → state restored from Room on reboot

Persist current state to Room on every transition. On app start, read last state and resume.

## Attendant overlay rules

- Hidden by default
- Revealed by swipe-up gesture from bottom 20% of screen
- Three buttons:
  - FILL_UP_AUTHORISE: enabled ONLY when state is IDLE
  - AUTHORISE_CASH: enabled ONLY when state is IDLE (legacy attendant cash flow)
  - CASH_RECEIVED: enabled ONLY when state is AWAITING_CASH_CONFIRM
- Swipe down or tap outside dismisses
- TranslateY animation, 250ms ease-out

## What I want generated in this first pass

1. **Full project structure** as listed above with every file created (some can be empty stubs with `// TODO` comments — but the file should exist)
2. **Working `MockPulseSource`** that generates fake pulse messages on a Flow with controllable rate
3. **`PulseSource` interface** + Hilt module that provides `MockPulseSource` when `BuildConfig.MOCK_HARDWARE = true`
4. **Room database** with the three entities, set up with Hilt
5. **Theme files** with the exact colors and typography above
6. **`MainActivity`** in kiosk mode (no system bars, screen-on flag, locked orientation to landscape)
7. **`CustomerScreen`** that switches between state Composables based on a `TransactionState` from `CustomerViewModel`
8. **All 7 customer state screens** as functional Compose stubs — they don't need real logic yet, but each should render the appropriate placeholder UI per the design system
9. **`AttendantOverlay`** with the swipe-up gesture and the three buttons with state-dependent enabling
10. **`DebugScreen`** with buttons to: start mock flow, stop mock flow, simulate disconnect, force a state transition for testing
11. **`LitreDisplay` component** — the giant monospace number rendering
12. **`build.gradle.kts`** files (project + app level) with all dependencies above

## Code quality requirements

- Every file has a brief header comment explaining its purpose
- Use `sealed class` over `enum` for state where data needs to be carried
- Public functions get KDoc comments
- ViewModels expose `StateFlow`, never `MutableStateFlow`, to the UI
- All database operations are `suspend` or return `Flow`
- Use `@Stable` and `@Immutable` annotations on Compose state classes where appropriate
- No `lateinit` outside of test code
- No `!!` operators — use `?:`, `requireNotNull`, or sealed class exhaustiveness

## What I do NOT want

- Don't implement real USB serial yet — leave `UsbSerialPulseSource` as a TODO stub
- Don't implement real payment SDKs — `MockPaymentProcessor` only
- Don't implement cloud sync yet — `TransactionRepositoryImpl` should just write to Room
- Don't add unit tests yet (I'll add them after I see the structure)
- Don't use Material Design components like `TopAppBar`, `BottomNavigation`, `Scaffold` defaults — this is kiosk mode, build raw
- Don't generate placeholder Lorem Ipsum text — use realistic Nigerian fuel-station copy ("Tap card to pay", "Enter amount in Naira", "Pump locked", etc.)

## Deliverable

A working Android Studio project I can open, sync, and run on an emulator. Tapping the screen should walk me through the full mock transaction flow. Swiping up from the bottom should reveal the attendant overlay. Pulses should tick up live in the dispensing state.

Build it.