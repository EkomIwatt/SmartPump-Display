# SmartPump Display — Project Log

## Prompt for Claude

> **After completing any project phase (or any meaningful unit of work the user labels a "phase", "stage", "run", or "milestone"), append a new entry to this file before ending the turn.**
>
> Each entry must follow the template under "Entry template" below. Keep the tone plain and non-technical in the **Summary** section so a non-engineer can follow along; put technical specifics in **Technical notes**.
>
> Rules:
> - Add new entries at the **bottom** of the log, under the "Entries" section. Never rewrite or delete previous entries — the log is append-only history.
> - Use the real date from the environment context (do not guess).
> - Reference commit hashes when the phase was committed; write "uncommitted" if it was not.
> - If a phase was abandoned or rolled back, still log it and say so — do not silently drop it.
> - Do not log routine tweaks, single-file fixes, or in-progress work. Only completed phases/milestones.
> - If the user explicitly says "don't log this," skip the entry for that turn.

---

## Entry template

```
### Phase N — <short title>
**Date:** YYYY-MM-DD
**Status:** done | partial | rolled back
**Commit(s):** <hash> or "uncommitted"

**Summary (plain language):**
<2–4 sentences a non-engineer can follow. What can the app now do that it couldn't before?>

**Technical notes:**
- <key change 1>
- <key change 2>
- <gotchas, dependencies added, decisions made>

**Next:**
<what the next phase is expected to cover, if known>
```

---

## Entries

### Week 1 — Phases 1–5: App shell end-to-end on mock stack
**Date:** 2026-05-08
**Status:** done
**Commit(s):** 63f5f46 (Phase 1), e9537d4 (Phases 1–5 squashed message); Phases 2–5 code uncommitted at time of log creation

**Summary (plain language):**
We stood up the skeleton of the SmartPump Display app end-to-end. The app builds and runs, and you can walk through the full customer journey at the pump — idle, payment, fueling, completion — against simulated hardware and payments. No real pump or payment backend is connected yet, but the whole experience is playable and an attendant can authorise fill-ups, confirm cash, or cancel from a swipe-up panel.

**Technical notes:**
- Phase 1: project setup, theme (colors/typography/dimensions), Hilt application wiring. AGP 9.2.0 + Kotlin 2.2.10 + Compose + Material3; Hilt 2.59.2 via KSP.
- Phase 2: Room data layer — entities in `data.db.entities.*`, DAOs in `data.db.*`, repositories.
- Phase 3: mock hardware driver + mock payment client + Hilt DI modules.
- Phase 4: customer-facing Compose screens and the state-machine host.
- Phase 5: attendant overlay (swipe-up bottom sheet: FILL UP AUTHORISE / CASH RECEIVED / CANCEL / DEBUG), debug screen (pulse rate, auto-approve, inject disconnect/parse-error, DeviceConfig form), MainActivity wiring.
- CustomerViewModel drives state: relay control, payment lifecycle, pulse→litres @ 100 pulses/L, FILL_UP 3s shutoff watchdog, state persistence/restore.
- Added `androidx.hilt:hilt-navigation-compose:1.2.0`.

**Next:**
Phase 6+ (not yet planned): real USB-serial Arduino driver, real Balanceè payment backend, WorkManager sync, SMS/USSD listener.

---

### Phase 0 (rebuild) — Docs reset & spec re-grounding
**Date:** 2026-05-11
**Status:** done
**Commit(s):** (this commit)

**Summary (plain language):**
A strict design spec arrived with five fully-specified transaction flows (pre-pay digital, fill-up cash, fill-up digital, cash fixed, USSD offline) plus a defined attendant interface. The previously built app shell was structured around a single generic flow, so we are restarting the UI layer and the state machine to match the new spec exactly. This phase did no code work — it reset all the project documentation so any future session loads the right context. Old design materials are archived (kept in git, just out of the working path). The rebuild itself happens on a new branch.

**Technical notes:**
- New branch: `rebuild/strict-design` (off `main` at b44bddd). All rebuild phases land here; merge to `main` only when each phase is verified.
- Archived: 8 old `Screen spec *.pdf` files → `docs/specs/_archive/`. Archived `docs/scaffolding-prompt.md` → `docs/_archive/`. Kept in git history; out of the read path for future sessions.
- `CLAUDE.md` rewritten: new pre-read order (`PROJECT_LOG.md` → `flows.md` → `state-machine.md` → `design-system.md` → `OPEN_QUESTIONS.md`), strict-design-screens authority rule, drop old PDF refs.
- `docs/design-system.md` rewritten: added serif-italic display type, state-color border mapping (cyan=fill-up dispensing, green=confirmed/complete, gold=cash/waiting), code-panel surface, three-card-row layout pattern, `StateChip` / `LedgerRow` / `CodePanel` primitives.
- New `docs/flows.md`: all 5 flows documented with screen sequence, webhook payload, nozzle-shutoff logic, dynamic-QR generation, cash-fixed cutoff calculation, USSD SMS-parser shape, attendant-action state-enable rules, V1 vs V2 scope.
- New `docs/state-machine.md`: full sealed-class hierarchy with `TransactionFlow` enum, per-flow transition tables, persistence rules, invariants.
- `docs/OPEN_QUESTIONS.md` rewritten (was a stale duplicate of the old scaffolding prompt) — now 20 real open questions covering hardware contract, payment integration, USSD/SMS, operator config, UX, V1 scope.
- Source of truth for visuals is now `docs/Strict design screens/*.png` (10 screenshots covering cover, idle+mode, all 5 flows, attendant UI, and updated component spec).

**Next:**
Phase 1 — Theme + domain-model rebuild. Delete the UI subtree, rewrite the theme tokens against the new palette, rewrite `TransactionState` as the new sealed hierarchy, update `Transaction` entity and repository signatures. Build must stay green; no customer-facing UI yet.

---

### Phase 1 (rebuild) — Theme + domain-model rebuild
**Date:** 2026-05-12
**Status:** done
**Commit(s):** uncommitted

**Summary (plain language):**
We stripped out the entire old UI and rebuilt the foundations the new design will sit on. The app now knows about all five transaction types (pre-pay digital, fill-up cash, fill-up digital, cash fixed, USSD offline) at the data and state-machine level — every flow has its own named state, and a completed transaction records which flow it took. The theme picked up the small bits that were missing from the new spec (the dark code-panel surface, the gold serif-italic hero style, the three-card-row gap). On screen, the app shows a single placeholder ("SmartPump Display — rebuild in progress") because the actual screens are being rebuilt in the next phase. The project compiles cleanly.

**Technical notes:**
- Deleted `ui/screens/` and `ui/components/` wholesale. Theme (`Color`, `Type`, `Dimensions`, `Theme`) kept and extended.
- Added theme tokens: `CodePanelSurface` (#0F0F16), `BrandBlue` (cover-only), `HeroSerifItalic` text style, `Dimensions.threeCardGap`, `cornerChip`, `cornerCodePanel`, chip padding + dot size.
- New `TransactionFlow` enum with all five flows. Removed `TransactionMode` (subsumed).
- Rewrote `TransactionState` per `docs/state-machine.md` — `Idle`, `ModeSelect`, `PrepayAmountSelect`, `PrepayMethodSelect`, `PrepayAwaitingPayment`, `UssdAwaitingSms`, `FillupAwaitingAttendantAuth`, `FillupDispensing`, `FillupTankFull`, `FillupDigitalAwaitingPayment`, `FillupAwaitingCashConfirm`, `CashFixedAmountEntry`, `CashFixedDispensing`, `FixedDispensing`, `Complete`, `Error`. Every variant `@Serializable @SerialName(...)` so `PulseRepositoryImpl` keeps round-tripping state through Room.
- `PaymentMethod` renamed to spec values: `BALANCEE_APP`, `BANK_QR_TRANSFER`, `NFC_CARD`, `USSD`, `CASH_SEE_ATTENDANT`.
- `Transaction` model + `TransactionEntity`: replaced `mode: TransactionMode` with `flow: TransactionFlow`, made `paymentMethod` nullable (cash-only flows), added `attendantId: String?` (null in V1 — no roles).
- `TransactionRepositoryImpl` toEntity/toDomain mappings updated accordingly. `TransactionDao` unchanged.
- Deleted the three orphan use cases (`HandlePaymentResultUseCase`, `StartTransactionUseCase`, `ObserveLitresUseCase`) — they were scaffolds against the old state shape; Phase 3 recreates them per the new transitions.
- `MainActivity` reset to a single-Text placeholder so the build stays green without any customer/attendant UI.
- Verified with `gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL (KSP regenerated Room + Hilt against the new schemas).
- Room schema changed (transactions table columns: `flow`, nullable `paymentMethod`, `attendantId`). DB module already uses `fallbackToDestructiveMigration(dropAllTables = true)`, no migration written — acceptable while no devices carry persisted data.

**Next:**
Phase 2 — shared UI components per strict design. Implement primitives from `docs/design-system.md`: `BalanceeCard` (state-coloured border), `BalanceeButton` (primary amber / secondary), `LitresDisplay`, `AmountDisplay`, `StateChip` (dot + alpha-fill), `LabelText`, `LedgerRow`, `NumericKeypad`, `QrCodeView`, `CodePanel`, `HeroSerifText`, `PumpHeader`, plus the new `ThreeCardRow` layout primitive. Every component gets an @Preview. Still no screens — just the kit.

---

### Phase 2 (rebuild) — Shared UI component kit
**Date:** 2026-05-12
**Status:** done
**Commit(s):** uncommitted

**Summary (plain language):**
Built the visual building blocks that every screen will be made of — cards with the right coloured borders, the big amber buttons, the live litre and naira counters, the state pills, the receipt rows, the keypad, the QR view, and the three-card row that lays out the customer journey. Each block has its own preview so the look can be verified inside Android Studio without running the app. No actual screens yet — just the kit. The project still launches the placeholder; the components are wired in next phase when screens get assembled.

**Technical notes:**
- New files under `app/src/main/java/app/balancee/smartpump/display/ui/components/`:
  - `LabelText` — small all-caps tracked label (defaults to text-secondary).
  - `StateChip` — leading 6dp dot, label uppercase, 15% alpha fill, 1dp outline, 6dp radius.
  - `LedgerRow` — left label / right value with optional monospace toggle.
  - `BalanceeCard` — state-coloured 1dp border, surface bg, 12dp radius, 24dp pad; `borderColor` is required.
  - `BalanceeButton` — `Primary` (amber, 64dp) / `Secondary` (outline) / disabled (text-tertiary + border-subtle); all-caps label.
  - `LitresDisplay` — giant mono number with "L" suffix at ~40% size in text-secondary; figure colour passed in.
  - `AmountDisplay` — same scale with "₦" prefix at ~50% size; UK locale thousand-grouping by default.
  - `HeroSerifText` — gold serif italic phrase; uses `HeroSerifItalic` style from Type.kt.
  - `ThreeCardRow` — three equal-width slots with `Dimensions.threeCardGap` (16dp).
  - `PumpHeader` — left-aligned "PUMP 1 · FILL-UP" label, right-aligned `StateChip`.
  - `CodePanel` — code-panel surface, 8dp radius, 13sp monospace, 16dp pad.
  - `NumericKeypad` — 3×4 grid (1–9 / ⌫ / 0 / ✓), per-row `weight(1f)` cells, 64dp minimum height; callbacks for `onDigit`, `onBackspace`, `onConfirm`; confirm key disabled-state supported.
  - `QrCodeView` — ZXing `MultiFormatWriter` → `BitMatrix` → `IntArray` → `Bitmap`; `remember` re-keyed by content + pixel size + colours; defaults to spec's inverted styling (white modules on black).
- Every component file ships a `@Preview` rendered against `Background` so the previews work in Android Studio without launching the app.
- Verified with `gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL.
- ZXing dependency (`com.journeyapps:zxing-android-embedded`) already wired in `app/build.gradle.kts`; no Gradle changes this phase.
- No state-color→border helper introduced yet — primitives take `Color` directly. A `StateColors` mapper lands in Phase 3 alongside the first screens.

**Next:**
Phase 3 — Customer flows + Idle/ModeSelect. One phase committed flow-by-flow:
- **3a** — `IdleScreen`, `ModeSelectScreen`, and the customer state host (renders the right screen for each `TransactionState`). Wire `MainActivity` to host it.
- **3b** — Flow 1 (Fixed Pre-pay Digital): amount tiles, payment method select, QR waiting, fixed dispensing, complete.
- **3c** — Flow 4 (Cash Fixed): keypad entry → cash-fixed dispensing → complete.
- **3d** — Flow 2 (Fill-up Cash): attendant-auth wait, open-ended dispensing with shutoff detection, awaiting cash confirm, complete.
- **3e** — Flow 3 (Fill-up Digital): tank-full QR generation, digital awaiting, complete.
- **3f** — Flow 5 (USSD Offline): USSD code display, awaiting SMS, dispensing on parsed SMS.

Each sub-deliverable leaves the build green, ends with a commit, and matches the corresponding strict-design screenshot.

---

### Phase 3a (rebuild) — Idle + ModeSelect + customer state host
**Date:** 2026-05-12
**Status:** done
**Commit(s):** uncommitted

**Summary (plain language):**
The app now actually shows something other than a placeholder. On launch you land on the Idle screen with the "Start transaction" button. Tap it and you go to the mode-select screen, where two cards offer PRE-PAY (gold) and FILL UP (cyan) — each with a short explainer. Picking either one moves the app forward to a state whose screen will be built in the next sub-phases (3b–3f); for now those states show a "wiring in progress" card with a "Back to idle" button so the kiosk never gets stuck. The whole flow is driven by a single state machine in a view-model — the screens are just dispatched by it.

**Technical notes:**
- New `ui/theme/StateColors.kt` — `TransactionState.borderColor()` extension mapping every variant to its design-system border colour (idle/menu → `BorderSubtle`, waiting/cash → `PrimaryAmber`, fill-up → `ActiveCyan`, complete/confirmed/digital-paid → `SuccessGreen`, error → `WarningRed`). `FixedDispensing` branches on `flow` so the cash-fixed flavour stays gold while pre-pay/USSD goes green.
- New `ui/customer/CustomerViewModel.kt` — `@HiltViewModel`, in-memory `MutableStateFlow<TransactionState>(Idle)`. Methods `onStartTransaction`, `onSelectPrePay`, `onSelectFillUp`, `onCancel`. Each transition guards on the expected source state. Persistence (PulseRepository) is intentionally not wired yet — that lands in Phase 5.
- New `ui/customer/IdleScreen.kt` — full-bleed dark canvas, `PumpHeader` at top, centered `BalanceeCard(BorderSubtle)` with `HeroSerifText "balanceè"`, tagline, `LabelText "Tap to fuel"`, primary `BalanceeButton`.
- New `ui/customer/ModeSelectScreen.kt` — `PumpHeader` → headline → two `BalanceeCard` mode tiles in a `Row(weight 1f)` with `Dimensions.threeCardGap`. PRE-PAY card uses gold accent + serif italic "Fixed amount, pay before fuel flows.", FILL UP uses cyan + "Open-ended fill. Pay after the nozzle shuts." Each card is wholly clickable and also hosts a "Choose …" button. Cancel sits at the bottom as a secondary button.
- New `ui/customer/CustomerStateHost.kt` — single `when` on `state`. `Idle` → `IdleScreen`, `ModeSelect` → `ModeSelectScreen`, every other variant → an inline `NotYetImplementedScreen` (state-coloured card naming the variant + "Back to idle"). Three previews: idle, mode select, placeholder (against `PrepayAmountSelect`).
- `MainActivity` rewritten: `SmartPumpRoot()` injects `CustomerViewModel` via `hiltViewModel()` and forwards `state` + four callbacks to `CustomerStateHost`. The Phase 1 placeholder is gone.
- Verified with `gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL. KSP re-ran for the new `@HiltViewModel`.

**Next:**
Phase 3b — Flow 1 (Fixed Pre-pay Digital). Wire the customer side end-to-end:
`PrepayAmountSelect` (₦2k/₦5k/₦10k/₦20k/₦50k/Custom tiles + numeric keypad on Custom),
`PrepayMethodSelect`, `PrepayAwaitingPayment` (gold QR card, 5-min expiry → Idle),
`FixedDispensing` (green border, live `LitresDisplay`), `Complete` (green border, ledger rows, share-receipt). Add the use-case the VM consumes for "is price-per-litre set?" (block transactions if not), wire a mocked webhook trigger via the existing `PaymentProcessor` so the QR state advances on the timed mock success. Keep persistence out for now.

---

### Phase 3b (rebuild) — Flow 1 (Fixed Pre-pay Digital), end-to-end
**Date:** 2026-05-12
**Status:** done
**Commit(s):** uncommitted

**Summary (plain language):**
A customer can now walk the entire pre-pay journey on the pump. They tap PRE-PAY, pick an amount (one of five presets or any custom value on the keypad), pick a payment method, see a QR card with a 5-minute countdown, watch the litres count up in green once payment confirms, and land on a "Done." receipt screen showing how much they paid, how many litres they got, and the transaction ID. If the operator hasn't pushed a price, the app refuses to start a transaction and tells the customer to find the operator — but for now a default price (₦870/L) is seeded on first launch so the flow is playable. Cash is acknowledged but routes back to idle because the attendant overlay isn't built yet (Phase 4). The four other flows still show the "wiring in progress" placeholder.

**Technical notes:**
- New screens under `ui/customer/`:
  - `PrepayAmountSelectScreen` — five preset tiles (₦2k/5k/10k/20k/50k) + a Custom tile that swaps the grid for a numeric keypad (min ₦200, max ₦200,000).
  - `PrepayMethodSelectScreen` — five method tiles (Balanceè app, Bank QR, NFC, USSD, Cash) coloured by accent; each fully clickable.
  - `PrepayAwaitingPaymentScreen` — two-card row, gold border. Left card = `QrCodeView` + method caption. Right card = amount, ledger rows, live `mm:ss` countdown sourced from the VM, plus a "Cancel transaction" secondary button.
  - `FixedDispensingScreen` — green border, giant `LitresDisplay` of `litresSoFar`, sub-line "of X.XX L authorised", running cost + paid line, ledger column + paid column at the bottom. Borrows amber styling when reached via `CASH_FIXED` (Phase 3c will use it from there).
  - `CompleteScreen` — green border ✓ card with serif "Done.", ledger (Litres, Paid, Price/L, Method, Txn), "Receipt sent to WhatsApp" hint, Share-receipt + Done buttons.
- New use case `domain/usecase/CanStartTransactionUseCase` — returns `Allowed(config)` or `PriceNotSet`. Wired into the VM's `onStartTransaction` so MODE_SELECT only renders when a price is live; otherwise we route to `TransactionState.Error("Price not set — contact operator.")`.
- `CustomerViewModel` rewritten:
  - Now `@Inject`s `CanStartTransactionUseCase`, `DeviceConfigRepository`, `PaymentProcessor`, `PulseSource`, `RelayController`. UI is exposed as `CustomerUiState` wrapping the canonical `TransactionState` plus a view-only `prepayExpiresInSeconds` countdown the QR card reads.
  - Pre-pay payment uses `PaymentProcessor.process(method, amountKobo).collect`. Pending → `PrepayAwaitingPayment` + 5-min countdown coroutine; Success → `FixedDispensing` + open relay + start a pulse-count coroutine that converts pulses → litres at 100 pulses/L and emits `Complete` when `litresSoFar >= litresAuthorised`; Failed → `Error(recoverable = true)`.
  - Expiry coroutine ticks once per second, auto-cancels back to Idle on zero. All in-flight jobs cancelled on `onCancel`, and the relay is force-closed.
  - `init` seeds a default `DeviceConfig(koboPerLitre = 87_000)` if none exists — stop-gap until the operator-push channel lands in Phase 6.
  - Cash selection on the method screen short-circuits to `onCancel()` (attendant overlay is Phase 4).
- `CustomerStateHost` extended to dispatch all Phase 3b states; everything else still falls through to `NotYetImplementedScreen`. Error state gets its own red-bordered card.
- `MainActivity` switched from passing raw state to passing the new `CustomerUiState` wrapper; nine callbacks now route to VM methods.
- `gradle/libs.versions.toml` bumped during the session (AGP 9.2.0 → 9.2.1, KSP 2.2.10-2.0.2 → 2.3.2) to keep KSP + AGP compatible after a clean run. Not part of Phase 3b proper but kept in the commit since it touches the same green build.
- Verified with `gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL.
- Persistence (PulseRepository round-trip) intentionally still **not** wired. Phase 5 handles boot-time resume.

**Next:**
Phase 3c — Flow 4 (Cash Fixed). Attendant enters a Naira amount on a gold-bordered keypad card; the system computes a litre cutoff against the live `koboPerLitre`, transitions to `CashFixedDispensing` (which can render through the existing `FixedDispensingScreen` in cash colours), and lands on `Complete` with `method = null` on the receipt. No customer overlay yet — the keypad screen stands in for the attendant action; the swipe-up overlay arrives in Phase 4.
