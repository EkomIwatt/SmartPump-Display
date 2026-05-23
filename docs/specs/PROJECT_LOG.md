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
Phase 7+ (not yet planned): real USB-serial Arduino driver, real Balanceè payment backend, WorkManager sync, SMS/USSD listener.

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
  - `init` seeds a default `DeviceConfig(koboPerLitre = 87_000)` if none exists — stop-gap until the operator-push channel lands in Phase 7.
  - Cash selection on the method screen short-circuits to `onCancel()` (attendant overlay is Phase 4).
- `CustomerStateHost` extended to dispatch all Phase 3b states; everything else still falls through to `NotYetImplementedScreen`. Error state gets its own red-bordered card.
- `MainActivity` switched from passing raw state to passing the new `CustomerUiState` wrapper; nine callbacks now route to VM methods.
- `gradle/libs.versions.toml` bumped during the session (AGP 9.2.0 → 9.2.1, KSP 2.2.10-2.0.2 → 2.3.2) to keep KSP + AGP compatible after a clean run. Not part of Phase 3b proper but kept in the commit since it touches the same green build.
- Verified with `gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL.
- Persistence (PulseRepository round-trip) intentionally still **not** wired. Phase 5 handles boot-time resume.

**Next:**
Phase 3c — Flow 4 (Cash Fixed). Attendant enters a Naira amount on a gold-bordered keypad card; the system computes a litre cutoff against the live `koboPerLitre`, transitions to `CashFixedDispensing` (which can render through the existing `FixedDispensingScreen` in cash colours), and lands on `Complete` with `method = null` on the receipt. No customer overlay yet — the keypad screen stands in for the attendant action; the swipe-up overlay arrives in Phase 4.

---

### Phase 3c (rebuild) — Flow 4 (Cash Fixed), end-to-end
**Date:** 2026-05-12
**Status:** done
**Commit(s):** uncommitted

**Summary (plain language):**
The attendant can now run a cash-fixed transaction. From idle, an "Attendant · cash fixed" button drops onto a gold keypad screen where they type the Naira amount the customer handed over; the screen shows the resulting litre cutoff live and the price per litre in use. Tapping the big amber "Authorise cash ₦X,XXX" (or the keypad ✓) opens the pump, the pump dispenses in gold up to exactly that cutoff (rounded down, so never more than was paid), and the receipt comes up green with no payment method — cash leaves no digital trace. The customer experience is unchanged. The attendant entry point is temporary until Phase 4 brings the swipe-up overlay; the keypad screen itself is permanent.

**Technical notes:**
- New screen `ui/customer/CashFixedAmountEntryScreen.kt` — gold-bordered two-column layout. Left card: live `AmountDisplay` of the typed amount, `LitresDisplay` of the floor-rounded cutoff, ledger rows for Price/L + min/max, Cancel secondary. Right: `NumericKeypad` whose ✓ also authorises. Bottom: a full-width primary `BalanceeButton` labelled "Authorise cash ₦X,XXX" that mirrors the ✓ action. Min ₦200, max ₦200,000.
- Re-used the existing `FixedDispensingScreen` for `CashFixedDispensing` — it already gold-styles when `flow == CASH_FIXED`. Host maps `cashAmountNaira → amountNaira` and `litresCutoff → litresAuthorised`.
- `CustomerViewModel`:
  - `CustomerUiState` gained `pricePerLitre: Int` so the keypad screen can render the current ₦/L before the price guard re-runs.
  - `init` now also pulls the seeded `DeviceConfig` into `pricePerLitre` + the UI state.
  - New `onAttendantCashFixed()` — price-guarded `Idle → CashFixedAmountEntry`. Fires `Error("Price not set — contact operator.")` when no config.
  - New `onCashFixedAuthorise(cashAmountNaira)` — computes cutoff via `DeviceConfig.litresCutoff(amountKobo)` (floor to 0.01L per state-machine invariant #3), transitions to `CashFixedDispensing`, opens the relay, starts a pulse coroutine that converts pulses → litres at 100 pulses/L and emits `Complete(flow = CASH_FIXED, method = null)` when `litresSoFar ≥ litresCutoff`. Sub-minimum amounts (cutoff ≤ 0L) route to a recoverable error.
  - `generateCashTxnId()` produces a local `"BLC-NNNNN"` ID — cash flows don't go through `PaymentProcessor` so they don't get a backend ref. Real backend correlation lands in Phase 7.
- `IdleScreen` gained a small secondary "Attendant · cash fixed" button under the primary action — temp affordance until Phase 4 ships the swipe-up overlay. The button is the same width as "Start transaction" so the customer-facing layout still feels symmetrical.
- `CustomerStateHost` dispatches `CashFixedAmountEntry → CashFixedAmountEntryScreen(uiState.pricePerLitre, ...)` and `CashFixedDispensing → FixedDispensingScreen(flow = CASH_FIXED, ...)`. Two new callbacks added: `onAttendantCashFixed`, `onCashFixedAuthorise(Int)`.
- `MainActivity` forwards both callbacks to the VM. Previews updated.
- Verified with `gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL.
- Persistence (PulseRepository) still deferred to Phase 5; debug screen overrides for price/cutoff still deferred to Phase 4.

**Next:**
Phase 3d — Flow 2 (Fill-up Cash). Customer-side: `FillupAwaitingAttendantAuth` screen (cyan, "ask the attendant to authorise"), `FillupDispensing` screen with live cyan litre count and a running 3-second pulse-timeout watchdog → `FillupTankFull`, then `FillupAwaitingCashConfirm`. The attendant actions (FILL UP AUTHORISE and CASH RECEIVED) get a second temporary idle-screen button each — the real swipe-up overlay still arrives in Phase 4. Persistence stays deferred.

---

### Phase 3 hardening — spec-compliance + audit fixes
**Date:** 2026-05-12
**Status:** done
**Commit(s):** uncommitted

**Summary (plain language):**
A pass over Phases 1–3 turned up four issues that needed fixing before Phase 4 builds on top of them. (1) The "Done." receipt at the end of a digital pre-pay transaction was green — the actual design says it should be gold (it's a receipt; gold is the "money" colour), and the cash/fill-up "Done." screens stay green because they're "dispense succeeded" feedback. (2) Completed transactions were never being written to the local audit log — the wiring existed but nothing called it. Now every completion lands a row in the transactions table so the backend sync (Phase 7) will have something to upload. (3) A leftover model file from before the rebuild (`FuelPrice.kt`) was deleted — its duplicate already lives on `DeviceConfig`. (4) The relay control verbs were renamed (`open()` → `startFuelFlow()`, `close()` → `stopFuelFlow()`) because the old names collided with the spec's electrical "OPEN/CLOSED" terminology in the opposite direction, which was a trap for future contributors.

**Technical notes:**
- `ui/theme/StateColors.kt`: `Complete` now branches on `flow`. `FIXED_PREPAY_DIGITAL → PrimaryAmber`, all others → `SuccessGreen`. Matches strict-design screen 224956 (Flow 1) vs 225053 (Flow 4).
- `ui/customer/CompleteScreen.kt`: derives the card border, the ✓ glyph, and the `PumpHeader` state chip from a single `accent` computed from `flow`. The serif "Done." stays `PrimaryAmber` either way.
- `docs/design-system.md`: state→border table split. Was `CONFIRMED / COMPLETE / PAID → green`; now `CONFIRMED / PAID → green`, plus two new rows — `COMPLETE — Flow 1 receipt → gold` and `COMPLETE — cash / fill-up / USSD → green`. Added a rule-of-thumb sentence so future contributors don't re-collapse the rows.
- `ui/customer/CustomerViewModel.kt`: now `@Inject`s `TransactionRepository`. New private suspend `completeAndRecord(complete: TransactionState.Complete)` helper that calls `setState` first, then best-effort `transactions.saveTransaction(...)` inside a try/catch (failures get `Log.e`'d but don't block the UI — the customer already got fuel; Phase 7 backend reconciliation handles drift). Both `startDispensing` (Flow 1) and `startCashFixedDispensing` (Flow 4) now go through it. Mapping: `amountKobo = amountNaira * 100`, `priceKoboPerLitre = pricePerLitre * 100`, `transactionRef = txnId` (separate field reserved for the short ref the backend issues), `attendantId = null` in V1.
- Deleted `domain/model/FuelPrice.kt`. Grep confirmed zero references; `DeviceConfig.litresCutoff(amountKobo)` is the single source of truth.
- Relay rename:
  - `RelayController.isOpen: StateFlow<Boolean>` → `isDispensing: StateFlow<Boolean>` (still `true` ⇒ fuel flowing).
  - `open()` → `startFuelFlow()`, `close()` → `stopFuelFlow()`.
  - File header updated to spell out the spec mapping: spec "RELAY OPEN" (no fuel) ≡ `!isDispensing`; spec "RELAY CLOSED" (fuel flows) ≡ `isDispensing`. Behaviour unchanged.
  - Callers updated: `MockRelayController` (log strings now say "ENERGISED / DE-ENERGISED"), `MockPulseSource` (uses `isDispensing` for the pulse gate + reset trigger), `CustomerViewModel` (six callsites across Flow 1 + Flow 4 + cancel).
- Verified with `gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL.

**Next:**
Phase 3d as previously scoped — Flow 2 (Fill-up Cash) customer-side, plus a temp idle-screen attendant entry for FILL UP AUTHORISE / CASH RECEIVED until Phase 4 ships the swipe-up overlay.

---

### Phase 3d (rebuild) — Flow 2 (Fill-up Cash), end-to-end
**Date:** 2026-05-12
**Status:** done
**Commit(s):** uncommitted

**Summary (plain language):**
The most common Nigerian scenario now runs in the app. The customer says "fill am", the attendant taps a temporary "Attendant · fill up" button on the idle screen, and the pump opens open-ended — the litres tick up in cyan with no target. Once the (simulated) tank fills and the flow stops, the screen flips to a gold "Amount due" card showing exactly what the customer owes. The customer taps "Pay cash" (the "Pay digitally" branch is greyed out until Phase 3e), the screen goes to a gold "Hand cash to attendant" hold, and once the attendant taps "Cash received" the receipt comes up green with the verified litres + cash logged in the audit table. The 3-second nozzle-shutoff watchdog from the spec is the real thing — the only way to test it without real hardware is to let the mock fill its 60L "tank" and stop emitting pulses, which is exactly what the new tank-capacity knob on `MockPulseSource` does.

**Technical notes:**
- New screens under `ui/customer/`:
  - `FillupAwaitingAttendantAuthScreen` — cyan card, hero-serif "Ask the attendant." with explainer + Cancel. Used when the customer picks FILL UP from ModeSelect.
  - `FillupDispensingScreen` — cyan border, giant `LitresDisplay` (no target line — open-ended), running `AmountDisplay` total, "filling… nozzle shuts automatically" hint, no customer-side stop. Spec hint: "Do not remove the nozzle until done." printed below.
  - `FillupTankFullScreen` — gold border, big `AmountDisplay` for verified amount-due, verified `LitresDisplay` in the right column. Two action buttons: primary "Pay cash" → `FillupAwaitingCashConfirm`; secondary "Pay digitally · phase 3e" disabled (host passes `digitalEnabled = false`).
  - `FillupAwaitingCashConfirmScreen` — gold border, same ledger as TankFull, primary "Cash received (attendant)" + Cancel. Banner explains the button stands in for the Phase 4 swipe-up overlay.
- `data/hardware/MockPulseSource`: added a `tankCapacityLitres` (`StateFlow<Double>`, default 60.0, capped at 0.5–500). Once `count >= capacityPulses` the mock stops emitting `Pulse` messages — heartbeats keep ticking. This is what lets the VM's 3s pulse-timeout watchdog actually fire during testing. Phase 4 debug screen will expose the knob to testers.
- `CustomerViewModel`:
  - File header updated; new private constants `FILLUP_SHUTOFF_TIMEOUT_MS = 3_000L`, `FILLUP_WATCHDOG_POLL_MS = 500L`.
  - New `fillupWatchdogJob: Job?` field, included in `cancelInFlightJobs()`.
  - `onAttendantFillUpAuthorise()` — price-guarded entry from `Idle` or `FillupAwaitingAttendantAuth`. Generates a local `BLC-NNNNN` txn id (same `generateCashTxnId()` used by cash-fixed), sets `FillupDispensing(litresSoFar = 0)`, and calls `startFillupDispensing(txnId)`.
  - `startFillupDispensing(txnId)`:
    - Sibling coroutines under `viewModelScope`: a pulse collector and a 500ms watchdog. Both close over a single `var lastPulseMs: Long` — safe because `viewModelScope` is Main-confined.
    - Pulse collector calls `relay.startFuelFlow()`, then on each `PulseMessage.Pulse` updates `lastPulseMs` and `litresSoFar`. In `finally`, force `relay.stopFuelFlow()` for safety.
    - Watchdog ticks every 500ms; once `lastPulseMs > 0` (i.e. fuel has started) and `now - lastPulseMs > 3000`, calls `relay.stopFuelFlow()`, computes `amountDueNaira = (verifiedLitres * pricePerLitre).toInt()`, transitions to `FillupTankFull`, and cancels the now-redundant pulse collector. Pulse collector's `finally` keeps the relay closed.
  - `onFillupPayCash()` — `FillupTankFull → FillupAwaitingCashConfirm` carrying through txnId / verifiedLitres / amountDueNaira.
  - `onFillupPayDigital()` — if called from FillupTankFull, routes to a recoverable `Error("Digital fill-up payment lands in Phase 3e.")`. The host disables the button so this should never fire today; the route is just defensive.
  - `onAttendantCashReceived()` — `FillupAwaitingCashConfirm → Complete(flow = FILLUP_CASH, method = null)` via the existing `completeAndRecord(...)` helper from the hardening pass; the transaction row lands in the `transactions` table.
- `IdleScreen` now has three buttons stacked: primary "Start transaction", secondary "Attendant · cash fixed", secondary "Attendant · fill up". Phase 4 swipe-up overlay replaces all the attendant buttons.
- `CustomerStateHost` dispatches the four Flow-2 states; four new callbacks (`onAttendantFillUp`, `onFillupPayCash`, `onFillupPayDigital`, `onAttendantCashReceived`) added. Previews updated.
- `MainActivity` forwards the new callbacks to the VM (`onAttendantFillUp → onAttendantFillUpAuthorise`).
- Verified with `gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL.

**Next:**
Phase 3e — Flow 3 (Fill-up Digital). From `FillupTankFull` the "Pay digitally" branch enables: transition to `FillupDigitalAwaitingPayment(qrContent)` showing a dynamic NIP QR encoding the exact verified amount against the station's `virtualAccountNumber` from `DeviceConfig`, gold border + 5-min expiry → fallback to `FillupAwaitingCashConfirm` (cash collected anyway). On webhook (mock `PaymentProcessor`) success the state goes to `Complete(flow = FILLUP_DIGITAL, method = BANK_QR_TRANSFER)`.

---

### Phase 3e (rebuild) — Flow 3 (Fill-up Digital), end-to-end
**Date:** 2026-05-12
**Status:** done
**Commit(s):** uncommitted

**Summary (plain language):**
The fill-up journey now has a digital payment option that works without the attendant ever touching cash. After the nozzle shuts and the gold "Amount due" screen comes up, the "Pay digitally" button — which was greyed out until this phase — is now live. Tapping it generates a fresh QR code encoding a bank transfer to the station's account for the exact verified amount, with the transaction reference baked in. The customer scans with any bank app (GTBank, Opay, PalmPay, etc.), and when the (simulated) payment confirms the screen flips to a green "Done." receipt logged as a digital fill-up. If the customer wanders off and the 5-minute timer runs out, the screen falls back to the cash-collection hold so the attendant can still collect — the fuel already flowed and the audit row must reflect what actually came in. There's also a "Cancel · collect cash instead" button for explicit fallback. The four other flows are unchanged; the USSD flow (3f) is the last one left.

**Technical notes:**
- New screen `ui/customer/FillupDigitalAwaitingPaymentScreen.kt` — gold-bordered two-card row. Left card: centred `QrCodeView(220dp)` of the dynamic NIP payload + "Open any bank app · scan · confirm" caption + bank-list subline ("GTBank · Opay · PalmPay · any bank"). Right card: `AmountDisplay` for the exact amount due in gold, verified-litres line ("38.10 L · verified"), ledger rows (Method = Bank QR · NIP, Price / L, Txn, Expires-in `mm:ss`), explainer paragraph. Bottom: "Cancel · collect cash instead" secondary button.
- `ui/theme/StateColors.kt` — fixed an existing miscolour: `FillupDigitalAwaitingPayment` was returning `SuccessGreen` from before this phase landed. Per `docs/design-system.md` (and the strict-design screen 225038) the waiting QR card is gold; green is the *Paid* card that lives on `Complete(FILLUP_DIGITAL)` instead. Moved the variant into the existing amber branch alongside the other "waiting" states.
- `ui/customer/CustomerViewModel.kt`:
  - `CustomerUiState` gained `fillupDigitalExpiresInSeconds: Int` so the QR card can render the 5-min countdown without leaking the timer into the canonical state.
  - New constant `FILLUP_DIGITAL_EXPIRY_SECONDS = 5 * 60`.
  - New companion constant `DEFAULT_VIRTUAL_ACCOUNT = "0123456789"` — fallback NIP destination if `DeviceConfig.virtualAccountNumber` is null. Real provisioning still tracked in `docs/OPEN_QUESTIONS.md` #6.
  - `onFillupPayDigital()` rewritten — no longer routes to an error. Reads the live `DeviceConfig.virtualAccountNumber` (with the fallback), composes `nip://transfer?account=…&amount=…&ref=…` for the QR, transitions to `FillupDigitalAwaitingPayment`, then launches sibling jobs: a `PaymentProcessor` collector and the 5-min expiry countdown.
  - `startFillupDigitalPayment(source)` — calls `paymentProcessor.process(BANK_QR_TRANSFER, amountKobo).collect`. Ignores `Pending` (the QR is already on-screen with our own txnId; the backend ref only matters once a webhook actually fires). On `Success`, guards `currentState() is FillupDigitalAwaitingPayment` (a late webhook after cancel or expiry must not retrigger), cancels the expiry job, then `completeAndRecord(Complete(flow = FILLUP_DIGITAL, method = BANK_QR_TRANSFER))` — same audit helper from the Phase-3 hardening pass writes the transactions row. On `Failed`, drops back to `FillupAwaitingCashConfirm` because the litres already flowed and cash must still be collectable; the failure reason is logged.
  - `startFillupDigitalExpiry(source)` — 1Hz countdown updating `fillupDigitalExpiresInSeconds`. On hitting zero (while still in the digital-awaiting state), cancels the payment collector and transitions to `FillupAwaitingCashConfirm` — matches the `docs/state-machine.md` fallback rule.
  - `onCancel()` now also resets `fillupDigitalExpiresInSeconds` so a fresh transaction starts with a clean countdown.
- `ui/customer/CustomerStateHost.kt` — added `FillupDigitalAwaitingPayment → FillupDigitalAwaitingPaymentScreen(...)` branch passing through the QR content + ui-state expiry seconds. Header comment updated; `FillupTankFullScreen` now wired with `digitalEnabled = true`.
- `ui/customer/FillupTankFullScreen.kt` — relabelled the digital button to "Pay digitally · scan QR" unconditionally (the host controls `enabled`).
- Verified with `gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL.
- Persistence (PulseRepository round-trip) still deferred to Phase 5; the in-memory state is enough until then.

**Next:**
Phase 3f — Flow 5 (USSD Offline). From `PrepayMethodSelect` with method = USSD: transition to `UssdAwaitingSms(amount, txnRef, txnId, pricePerLitre)` showing the per-bank USSD codes (`*737*amount*ref#` for GTBank, plus Access / Zenith / UBA) and a "waiting for SMS" hold. The mock SMS path uses a debug-screen injector (lands in Phase 4) so testers can trigger the parser. On parse success → `FixedDispensing(flow = USSD_OFFLINE, …)` then `Complete(flow = USSD_OFFLINE, method = USSD)`. Default 5-min SMS timeout returns to `Idle`.

---

### Phase 3f (rebuild) — Flow 5 (USSD Offline), end-to-end
**Date:** 2026-05-12
**Status:** done
**Commit(s):** uncommitted

**Summary (plain language):**
The last of the five flows runs in the app now. From the pre-pay method picker, choosing USSD shows the customer a gold two-card screen — the left card lists per-bank dial codes (GTBank as the primary, plus Access, Zenith and UBA in a monospace block); the right card shows the amount due, the short reference number to look for in the SMS, a placeholder SIM status, and a 5-minute countdown until the transaction auto-cancels. When the simulated SMS "arrives" (the same mock payment processor we already use for bank QR, just on the USSD channel), the screen flips green and the pump dispenses to the litre target. The receipt at the end records method = USSD and flow = USSD offline. If 5 minutes pass with no SMS, the screen returns to idle and the transaction is dropped. Result: all five customer flows are now wired end-to-end. The next gap is the attendant overlay — Phase 4.

**Technical notes:**
- New screen `ui/customer/UssdAwaitingSmsScreen.kt` — two gold-bordered cards in a `weight(1f)` row.
  - **Dial-code card (left):** label "Dial this code", primary GTBank code rendered big in `DisplayMono` + amber (`*737*$amount*$ref#`), `CodePanel` listing the four bank codes (GTBank / Access / Zenith / UBA) with their dial strings aligned, and a "Works on any phone — including 2G" footer.
  - **Waiting card (right):** label "Waiting for SMS confirmation", `AmountDisplay` of the naira amount in gold, ledger rows (Ref, Price / L, Txn, Sim status, Expires-in `mm:ss`), and a paragraph explaining the 10–30s bank SMS latency.
  - Bottom: full-width secondary "Cancel transaction" button.
- `ui/customer/CustomerViewModel.kt`:
  - File header updated; new constant `USSD_SMS_TIMEOUT_SECONDS = 5 * 60`; new ui-state field `ussdExpiresInSeconds: Int`.
  - `onPrepayMethodChosen` rewritten as a 3-arm `when` — cash routes to `onCancel()` (Phase 4 overlay still owns cash), USSD routes to `startUssdFlow(amountNaira)`, every other method falls through to the existing `startPrepayPayment(...)` path.
  - New `startUssdFlow(amountNaira)` — cancels in-flight jobs, generates a 3-digit `txnRef` (see OPEN_QUESTIONS #11) plus a `BLC-NNNNN` `txnId` via the existing `generateCashTxnId()`, transitions to `UssdAwaitingSms`, kicks off both the expiry coroutine and the SMS listener.
  - `startUssdSmsListener(amountNaira, amountKobo, txnId)` — uses the same `PaymentProcessor.process(USSD, amountKobo)` flow already in DI. Pending is ignored (we already showed the USSD code with our own ref); Success means the mock SMS landed → `onUssdSmsConfirmed(...)`; Failed → recoverable `Error`. Real production path swaps in a SIM-side `BroadcastReceiver`-driven adapter behind the same `PaymentProcessor` interface (Phase 7).
  - `onUssdSmsConfirmed` — guards `currentState() is UssdAwaitingSms` (a late Success after cancel/timeout must not re-arm the pump), cancels expiry, computes `litresAuthorised` from the device-config `litresCutoff(amountKobo)` (floor to 0.01L per state-machine invariant), transitions to `FixedDispensing(flow = USSD_OFFLINE, …)`, then reuses the existing `startDispensing(litresAuthorised, USSD)`. The existing helper produces `Complete(flow = USSD_OFFLINE, method = USSD)` through `completeAndRecord(...)` so the audit row lands in the transactions table on completion.
  - `startUssdExpiry` — 1Hz countdown updating `ussdExpiresInSeconds`. On hitting zero (while still on the USSD screen), cancels the SMS listener and returns to `Idle` — matches the `docs/state-machine.md` USSD-timeout rule.
  - `onCancel()` resets `ussdExpiresInSeconds` alongside the existing prepay/fillup countdowns.
- `ui/customer/CustomerStateHost.kt`:
  - Added `UssdAwaitingSms → UssdAwaitingSmsScreen(...)` dispatch passing through the `ussdExpiresInSeconds` view-state.
  - Removed the `else → NotYetImplementedScreen` fallback (and the screen itself) — the `when` is now exhaustive across the sealed hierarchy and the compiler flagged the redundant branch. Stale imports (`borderColor`, `BalanceeButtonVariant`, `TextSecondary`) dropped with it.
  - Header comment updated; the next gap is the attendant overlay (Phase 4).
- Verified with `gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL with no warnings.
- Persistence (PulseRepository round-trip) still deferred to Phase 5. The real SMS parser (GTBank format) lands in Phase 7 — see OPEN_QUESTIONS #9.

**Next:**
Phase 4 — attendant swipe-up overlay + debug-screen rebuild. The overlay holds exactly three actions per `docs/flows.md`: FILL UP AUTHORISE (enabled in Idle), AUTHORISE CASH ₦… (enabled in Idle, inline amount entry → Flow 4), CASH RECEIVED (enabled in `FillupAwaitingCashConfirm`). State-gated enable/disable; `translateY` slide-up animation; swipe-down / tap-outside dismiss. Once the overlay is live, the three temporary "Attendant · …" buttons on `IdleScreen` come off. Debug screen covers: live price override, pulse rate + tank-capacity knobs on `MockPulseSource`, mock SMS injector for Flow 5, mock-payment auto-approve / failure-reason toggles, device-config form.

---

### Phase 4a (rebuild) — Attendant swipe-up overlay
**Date:** 2026-05-13
**Status:** done
**Commit(s):** 749483a

**Summary (plain language):**
The attendant's interface is no longer three loose buttons stapled to the idle screen — it's the proper swipe-up bottom panel the spec called for. A subtle pill handle sits at the bottom edge of every screen; tapping it (or swiping up from it) slides a dark panel up over the customer view with exactly three action cards: FILL UP AUTHORISE in cyan, AUTHORISE CASH ₦… in gold, CASH RECEIVED in green. Each card lights up only when the current state actually allows that action — for example, CASH RECEIVED only becomes tappable once the fill-up has finished and the customer is in the cash-collection hold; the other two only work when the pump is idle. Tap the scrim, drag the panel back down, or tap "Dismiss" to close it. The three temporary attendant buttons on the idle screen (and the stand-in "Cash received" button on the awaiting-cash screen) are gone — the overlay is the only path now.

**Technical notes:**
- New package `ui/attendant/`:
  - `AttendantOverlay.kt` — the panel composable. `BalanceeCard` per action with the spec border colour (cyan/amber/green) when enabled and `BorderSubtle` + text-tertiary when disabled. Header reads "Three actions. *Swipe down to dismiss.*" mirroring the strict-design copy.
  - `AttendantOverlayHost.kt` — a `Box`-scoped wrapper around the customer state host. Owns the `visible` flag (`rememberSaveable` so it survives rotation), the bottom-edge swipe-up handle, the scrim, and the slide-up/down animation. Two gesture surfaces:
    - **Open:** 28dp bottom strip with a 72×4dp pill + faint amber tick. Tap opens. Vertical drag accumulating > 32dp upwards also opens.
    - **Dismiss:** the panel itself accepts a downward `draggable` — net delta > 48dp dismisses. Scrim taps + the in-panel "Dismiss" pill also dismiss. Threshold conversion via `LocalDensity.toPx()`.
  - 250ms ease-out `slideInVertically` + `fadeIn` for the open animation; mirrored ease-in for close. Matches `docs/design-system.md` motion spec.
- State-gated enable map (single source of truth in `AttendantPanel`):
  - `FILL UP AUTHORISE` → `state is Idle || state is FillupAwaitingAttendantAuth`
  - `AUTHORISE CASH ₦…` → `state is Idle` (routes via `onAttendantCashFixed → CashFixedAmountEntry`; the strict-design "inline ₦___" placeholder is rendered as a teaser on the card — actual amount entry stays on the dedicated `CashFixedAmountEntryScreen` since fitting a numeric keypad inside a 320dp panel would crowd it.)
  - `CASH RECEIVED` → `state is FillupAwaitingCashConfirm`
  Each card auto-dismisses the overlay after firing its action.
- `MainActivity` rewritten to wrap `CustomerStateHost` in `AttendantOverlayHost`. The three attendant callbacks now route from the overlay directly to the existing VM methods (`onAttendantFillUpAuthorise`, `onAttendantCashFixed`, `onAttendantCashReceived`) — the customer-side host no longer carries them.
- `CustomerStateHost` drops three callback parameters (`onAttendantCashFixed`, `onAttendantFillUp`, `onAttendantCashReceived`) — its API is now customer-only.
- `IdleScreen` — three "Attendant · …" temp buttons removed; replaced with a single "Attendant? Swipe up from the bottom edge." hint under the primary "Start transaction" button.
- `FillupAwaitingCashConfirmScreen` — the stand-in "Cash received (attendant)" primary button and the Phase 4 banner are gone. The screen is now purely informational on the customer side; the close-out lives in the overlay.
- Verified with `gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL. (One transient compile fix: missing `androidx.compose.foundation.layout.width` import in `AttendantOverlay.kt` for the tick affordance.)

**Next:**
Phase 4b — the engineering debug screen: pulse-rate + tank-capacity sliders on `MockPulseSource`, mock-payment auto-approve / pending-delay / failure-reason knobs on `MockPaymentProcessor`, the Flow 5 "SMS arrived" injector that bypasses the pending delay on the in-flight payment, and a `DeviceConfig` form (pumpId / stationName / koboPerLitre / virtualAccountNumber). Reachable from an undocumented top-left long-press hotspot — testers only.

---

### Phase 4b (rebuild) — Debug screen + mock-payment force-resolve
**Date:** 2026-05-13
**Status:** done
**Commit(s):** 494278f

**Summary (plain language):**
A hidden engineering screen joins the build for testers. A long-press on the top-left corner of any screen (an invisible 40dp hit-target) opens it. From there you can change the fuel price live and the customer-side price guard picks it up immediately, slide the mock pump's pulse rate and "tank capacity" to test the nozzle-shutoff watchdog, toggle whether the next mock payment will succeed or fail, change how long it takes the mock to "resolve", and press a single "SMS arrived · force resolve" button to short-circuit the wait — which is the only way to test the USSD flow in seconds rather than minutes. The device-config form also lets you set the station name, pump number, and the NIP virtual account that the fill-up-digital QR encodes. Tap "Done" to return. Customers and attendants never see this screen.

**Technical notes:**
- New package `ui/debug/`:
  - `DebugViewModel.kt` — Hilt VM that injects the concrete `MockPulseSource` + `MockPaymentProcessor` (Hilt provides them as themselves since both carry `@Singleton @Inject constructor`; the same singletons sit behind the `PulseSource` / `PaymentProcessor` interface bindings, so debug-side changes affect the live `CustomerViewModel`). 5-flow `combine` over `pulsesPerSecond`, `tankCapacityLitres`, `autoApprove`, `pendingDelayMs`, `failureReason` collapsed into a `DebugUiState` via a small local `Quint` holder (stdlib stops at `Triple`). `DeviceConfigRepository.observeConfig()` is a separate collector that updates the same UI state.
  - `DebugScreen.kt` — three vertically-stacked `BalanceeCard`s on a scrollable column:
    1. **Mock hardware** (cyan border) — pulse-rate `Slider` 0–200 pps, tank-capacity `Slider` 0.5–200 L, plus two secondary buttons for the existing `injectDisconnect()` / `injectParseError()` knobs.
    2. **Mock payment** (gold border) — auto-approve `Switch` with a helper line that flips copy based on the toggle, pending-delay `Slider` 0–30 000 ms, failure-reason `OutlinedTextField` (enabled only when auto-approve is off), and the primary "SMS arrived · force resolve" button.
    3. **Device config** (green border) — live "Price/L" + "Updated at" readout plus an editable form (`pumpId`, `stationName`, naira-per-litre, virtual account). `Save config` writes through `DeviceConfigRepository.saveConfig(...)`. A status line under the button reports "Saved at …" in green or "Save failed: …" in red.
- `MockPaymentProcessor`:
  - New `triggerInstantResolve()` backed by a `Channel<Unit>(capacity = CONFLATED)` — collapses repeated presses into a single pending signal.
  - `process(...)` now races the pending delay against the channel via `withTimeoutOrNull(delayMs) { instantResolve.receive() }`, then drains any stale signal at the start of the next call so a press while no payment is in flight doesn't get carried over to the next transaction.
  - This is the actual interrupt point the customer VM was already wired for — `startUssdSmsListener` and the pre-pay digital collectors are unchanged; they just receive `Success` (or `Failed`, per `autoApprove`) sooner.
- `MainActivity`:
  - Adds a top-level `debugVisible` flag (`rememberSaveable`) and an early return that hands the whole screen to `DebugScreen` when the flag is set. `DebugScreen` exits via `onClose = { debugVisible = false }`.
  - Adds a `DebugLongPressHotspot` — a 40dp `Box` at `Alignment.TopStart`, `pointerInput` running `detectTapGestures(onLongPress = { ... })`. `rememberUpdatedState` captures the latest `onOpenDebug` so the gesture detector always calls into the current composition.
  - The hotspot sits *above* `AttendantOverlayHost` in the Z-order so debug is reachable in any state, including while the attendant panel is open.
- Verified with `gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL.

**Next:**
Phase 5 — persistence & resume verification. Wire `CustomerViewModel` to `PulseRepository` (state writes on every `setState`, restore on `init`), run the reboot test through each non-terminal state per the `docs/state-machine.md` persistence rules, force relay open on boot before re-deriving from state, and re-confirm `Complete` / `Error(recoverable=false)` reset to `Idle`. After Phase 5, merge `rebuild/strict-design` → `main`.

---

### Phase 5 (rebuild) — Persistence + boot resume
**Date:** 2026-05-13
**Status:** done
**Commit(s):** f77700a

**Summary (plain language):**
The pump now remembers what it was doing across a power cut. Every move the state machine makes is written to local storage, and during a long fill-up the pulse count is saved every quarter-litre. When the app starts, the very first thing it does is force the fuel flow off — no matter what was happening before. Then it reads the last saved state and picks up where it left off: a customer who was waiting at the QR screen still sees their QR; a fill-up that was mid-dispense resumes counting from the saved pulse total (no double-pour, no lost litres); the 5-minute countdowns restart. The only states the app refuses to resume are "done" and "fatal error" — both get reset to idle on boot since they're already terminal. The audit row that a successful transaction wrote when it completed is already on disk too, so backend reconciliation in a later phase will see exactly what was dispensed.

**Technical notes:**
- `domain/model/TransactionState.kt`:
  - `FixedDispensing` gained a nullable `method: PaymentMethod? = null`. The default value keeps older persisted JSON blobs deserialisable (`Json { ignoreUnknownKeys = true }` already covered the *adding fields* case; the kotlinx defaulting handles the *missing fields on old blobs* case). Carrying the channel on the state lets a power-cut resume rebuild the `Complete` audit row with the right `method` rather than re-deriving from `flow` (which collapses BALANCEE_APP / BANK_QR_TRANSFER / NFC_CARD into "some Flow 1 channel").
- `ui/customer/CustomerViewModel.kt` — rewritten end-to-end for persistence:
  - New injected dep `pulseRepository: PulseRepository`.
  - `setState(s)` now also `trySend`s `s` into a CONFLATED `stateWriteChannel: Channel<TransactionState>`. A single writer coroutine (launched in `init`) drains the channel and calls `pulseRepository.saveTransactionState(s, txnRefFor(s))`. CONFLATED is the right shape for state-machine recovery — only the latest pending state matters; intermediate states being dropped is fine because resume only ever reads the freshest write.
  - `txnRefFor(state)` extracts `BLC-NNNNN` from whichever state carries it (Prepay/Ussd/Fixed/CashFixed/Fillup* + Complete) so the entity's `currentTransactionRef` column stays useful even when the user reboots mid-flow.
  - New private `pulseBaseline: Int` field. Reset to 0 on every fresh dispense entry point; restored from `pulseRepository.restorePulseCount()` on boot when resuming a dispensing state. Each of the three dispensing collectors (`startDispensing`, `startCashFixedDispensing`, `startFillupDispensing`) computes `cumulativePulses = pulseBaseline + msg.count` and uses that for both the live `litresSoFar` and the litre-target check. On the mock this means a resumed dispense doesn't restart at 0L — the mock's internal counter resets on each `relay.startFuelFlow()` but the VM adds the baseline back. On real hardware (Phase 7) the Arduino is authoritative for cumulative count and the baseline becomes a no-op.
  - Pulse persistence is throttled to every 25 pulses (`PULSE_PERSIST_EVERY_N` — at 100 ppl that's one disk write per 0.25 L). Frequent enough that a power-cut resume reconstructs `litresSoFar` within ±0.25 L; cheap enough not to thrash the kiosk's flash. Wrapped in `runCatching` — a disk failure mustn't break the dispense loop.
  - `init` is now a two-coroutine boot:
    1. The state-writer coroutine (launched first so it's ready to receive when boot resume starts firing `setState` calls).
    2. The boot-sequence coroutine: `relay.stopFuelFlow()` → `seedDefaultConfigIfMissing()` → load price into `pricePerLitre` → `bootResume()`. The relay invariant runs **before** state restore so even a state that says "we were dispensing" can't accidentally keep the relay open across a reboot.
  - `bootResume()` dispatches per restored state:
    - **Pure-UI states** (`Idle`, `ModeSelect`, `PrepayAmountSelect`, `PrepayMethodSelect`, `FillupAwaitingAttendantAuth`, `FillupTankFull`, `FillupAwaitingCashConfirm`, `CashFixedAmountEntry`) → `setState(restored)`. No side-effect jobs to restart.
    - **`Error(recoverable=true)`** → resume so the user can dismiss. **`Error(recoverable=false)`** → reset to Idle + clear pulses (terminal per `state-machine.md` invariants).
    - **`Complete`** → reset to Idle + clear pulses. The audit row was already written by `completeAndRecord` at the original completion; the customer just never tapped "Done". Treating Complete as terminal on boot is per spec.
    - **`PrepayAwaitingPayment`** → restart expiry countdown + a fresh `paymentProcessor.process(method, amountKobo)` collector via the new `resumePrepayPaymentListener(...)` helper. The transactionRef on the resumed state stays the one the customer is looking at; the new Pending event's fresh backend ref is ignored.
    - **`UssdAwaitingSms`** → restart `startUssdExpiry()` + `startUssdSmsListener(...)` with the persisted amount/txnId.
    - **`FillupDigitalAwaitingPayment`** → reconstruct the `FillupTankFull` source the digital handlers close over. `pricePerLitre` is derived from `amountDueNaira / verifiedLitres` rather than re-read from DeviceConfig — that way a price change between cut and reboot doesn't retroactively edit the customer's receipt.
    - **`FixedDispensing` / `CashFixedDispensing` / `FillupDispensing`** → `pulseBaseline = restoredPulses`, `setState(restored)`, kick the corresponding `startDispensing(...)` / `startCashFixedDispensing(...)` / `startFillupDispensing(...)` helper. The collector's first read of `currentState()` finds the just-set restored state, so the resume is seamless to the UI.
  - New `deriveMethodForFlow(flow)` fallback — only fires for older persisted blobs without the new `FixedDispensing.method` field. `FIXED_PREPAY_DIGITAL → BANK_QR_TRANSFER`, `USSD_OFFLINE → USSD`, cash flows → null. Audit fidelity is best-effort here; Phase 7 backend reconciliation corrects via the webhook trail.
  - `onCancel()` now resets pulse baseline + clears persisted pulses via `resetToIdle(clearPulses = true)`. `startDispensing` / `startCashFixedDispensing` / `startFillupDispensing` reset `pulseBaseline = 0` at the start of each fresh transaction so a previous transaction's count doesn't leak into the next.
  - `startDispensing(method: PaymentMethod?)` signature relaxed (was non-null) so the boot-resume `deriveMethodForFlow(flow)` fallback can pass through cash flows.
- Verified with `gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL. One transient compile fix during the change (the relaxed `method` parameter type).
- **Not** verified on a real device kill-and-relaunch test for every state — flagged for a manual reboot sweep before merging to `main` (each non-terminal state at least once: idle, mode-select, prepay-amount-select, prepay-method-select, prepay-awaiting, fillup-attendant-waiting, fillup-dispensing-mid-fill, fillup-tank-full, fillup-awaiting-cash, fillup-digital-awaiting, cash-fixed-entry, cash-fixed-dispensing-mid-fill, ussd-awaiting-sms, complete, error-recoverable, error-non-recoverable).

**Next:**
Phase 7 — production wiring. Real USB-serial Arduino driver behind `PulseSource`, real Balanceè payment SDK behind `PaymentProcessor`, real SMS `BroadcastReceiver` for Flow 5 GTBank parsing, WorkManager backend sync for the audit table, FCM channel for operator price/config push (or polled HTTP — see OPEN_QUESTIONS #8). Phase 5's `MOCK_HARDWARE`-style flag in `HardwareModule` / `PaymentModule` switches between mocks and prod bindings. Before Phase 7 starts: merge `rebuild/strict-design` → `main` once the manual reboot sweep is clean.

---

### Phase 5 hardening — pre-deployment design polish & UX
**Date:** 2026-05-18
**Status:** done
**Commit(s):** 649ae20 (brand-blue on Idle), cb72110 (design polish)

**Summary (plain language):**
A pass through every customer-facing screen tightening the visual design and UX before the rebuild branch can merge to `main`. The Idle screen now wears the proper Balanceè blue brand treatment — previously it had drifted to a generic dark card with gold serif text, masking the brand. During dispensing the customer can now see a coloured progress bar fill up as their litres count, not just a percentage in a ledger row. The "Done" receipt at the end gets two clear paired buttons — "Share receipt" and "Return to Idle", both coloured to match the receipt's theme — and the screen auto-returns to idle after a minute so the pump is ready for the next customer if they walk away. A misleading line that claimed the receipt had been sent to WhatsApp was removed (no WhatsApp integration exists yet; that's Phase 7). The app also now runs in proper kiosk mode — no Android status bar or navigation bar showing, the screen stays on, and the hardware back button is disabled so a customer can't accidentally exit the app. No state-machine or persistence changes — UI and lifecycle only.

**Technical notes:**
- **`ui/theme/Color.kt`** — corrected `BrandBlue` comment (was wrongly marked "spec cover only — not used at runtime"); added `OnBrand = #F7F7F8` for light text on brand-blue surfaces.
- **`ui/components/BalanceeButton.kt`** — new `Brand` variant (blue bg, light text). Optional `accentColor: Color?` param overrides both the Primary fill *and* the Secondary outline, so receipt buttons can track the flow accent (gold for Flow 1, green elsewhere).
- **`ui/customer/IdleScreen.kt`** — card border, "Balanceè" wordmark, and "Start transaction" CTA all switched to `BrandBlue`. Wordmark capitalisation fixed (was "balanceè").
- **`ui/components/AmountDisplay.kt`** — figure text now sets `PlatformTextStyle(includeFontPadding = true)` + `LineHeightStyle(alignment = Center, trim = None)`. Without these, the comma in "2,000" was painted outside the line box and read as a blank gap on tight tiles. Component-level fix — every caller inherits it.
- **`ui/customer/FixedDispensingScreen.kt`** — new thin horizontal `ProgressBar` (Box-based, no Material `LinearProgressIndicator` so the styling stays industrial-brutalist). Tracks `litresSoFar / litresAuthorised`, tinted in the state colour.
- **`ui/customer/CompleteScreen.kt`** — serif "Done." + ✓ glyph both render in the flow accent. Bottom action area replaced: now a `Row { Share receipt | Return to Idle }` with `weight(1f)` per button + 12dp gap; both styled with `accentColor = accent`. Screen-local 60-second auto-return countdown via `LaunchedEffect` (no VM/persistence touch — `Complete` is terminal on boot already); pressing Share Receipt resets the countdown so multiple shares don't time out. Inner column wraps in `verticalScroll(rememberScrollState())` + tighter `spacedBy(12.dp)` so the 5-row pre-pay receipt no longer clips its buttons on phone-landscape viewports. Misleading "Receipt sent to WhatsApp" copy removed — the share channel is a Phase 7 / OPEN_QUESTIONS #14 decision.
- **`ui/customer/PrepayMethodSelectScreen.kt`** — chrome moved to brand-blue (header state-chip + "Pre-pay amount" label and display). Per-method tile accents stay so each payment channel keeps its identity colour. Amount column gains bottom padding so the displaySmall descender doesn't clip.
- **`ui/customer/PrepayAmountSelectScreen.kt`** — preset tile figure dropped from `displaySmall` (36sp) to `headlineLarge` (32sp); the AmountDisplay fix above handles the comma either way, but `displaySmall` was always tight in a three-column tile grid.
- **`ui/customer/CashFixedAmountEntryScreen.kt`** — bottom full-width "Authorise cash ₦X,XXX" button switched to the Brand variant (was Primary/amber). Aligns with the keypad's brand-blue ✓ key.
- **`ui/attendant/AttendantOverlay.kt`** — `AUTHORISE CASH ₦…` action card accent switched from `PrimaryAmber` to `BrandBlue` when enabled. Was the only attendant action still carrying the legacy gold.
- **`MainActivity.kt`** — full immersive kiosk mode: `WindowCompat.setDecorFitsSystemWindows(false)`, hide `systemBars` via `WindowInsetsControllerCompat`, `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, `FLAG_KEEP_SCREEN_ON`, and an `OnBackPressedCallback` that swallows the hardware back so a customer can't accidentally exit. `AndroidManifest.xml` already had `screenOrientation="landscape"` and `WAKE_LOCK`; Lock Task Mode (device-owner pinning) remains a deployment step, not code.
- **`docs/design-system.md`** updated alongside `Color.kt`: `BrandBlue` accent line corrected, `BalanceeButton` primitive description gained the Brand variant.
- Verified clean via `gradlew :app:compileDebugKotlin` (full recompile via `--rerun-tasks` after the brand-blue change).

**Next:**
Manual on-device reboot sweep through every non-terminal state (the same 16-state list flagged in the Phase 5 entry above) before `rebuild/strict-design` can merge to `main`. Two follow-up items raised by the user remain open and intentionally not in this commit — both pending a conversation with the operator:
- **Fill-up STOP DISPENSE action.** Add a fourth attendant overlay action enabled only in `FillupDispensing`, for defence-in-depth on top of the mechanical nozzle shutoff + 3s software watchdog. Report written for the operator (`reports/Fillup-Terminate-Note.pdf`).
- **Attendant / debug-screen PIN gate.** Today anyone can swipe up and authorise a free fill-up, or long-press top-left to re-price the pump. Tracked in OPEN_QUESTIONS #19; user is raising with the operator.

After the reboot sweep and those two decisions land, `rebuild/strict-design` → `main`, then Phase 7 (production hardware/payment wiring).

---

### Phase 5c (rebuild) — Station onboarding + PIN gate
**Date:** 2026-05-23
**Status:** done
**Commit(s):** e15073d

**Summary (plain language):**
Two big changes go in together. First: the pump now refuses to do anything until the station has been set up. On first boot the operator walks a three-step install flow — type the station ID, pick a logo from the gallery (or skip), set a 4-digit attendant PIN — and only after that is the customer screen reachable. The Idle screen used to show "Balanceè" in big blue serif; it now shows the station's logo (or, if they skipped that step, their own name). "Pay via Balanceè" inside the payment-method picker is the only place Balanceè still appears. Second: every attendant action — FILL UP AUTHORISE, AUTHORISE CASH, CASH RECEIVED — now opens a PIN keypad modal before firing. Wrong PIN silently shakes and clears; no action runs without a correct PIN. The PIN never leaves the device — it's hashed with a per-device salt and only the hash is stored. For developer demos a debug-build toggle on the engineering screen skips the PIN modal and auto-provisions a "Demo Station" with PIN 0000 on first boot, so demos don't get bogged down typing. Production builds have neither escape hatch.

**Technical notes:**
- New data layer under `data.db.*` / `data.repository.*` / `domain.model` / `domain.repository`:
  - `StationIdentity` (domain) + `StationIdentityEntity` (Room, single-row id=1) + `StationIdentityDao` (`save`/`get`/`observe`/`exists`/`delete`). Schema bumped 1→2; the existing `fallbackToDestructiveMigration(dropAllTables = true)` wipes the install on the next launch of an old build. Both data classes override `equals` / `hashCode` so `logoBytes: ByteArray?` compares structurally — Compose's `@Immutable` recompose-skipping depends on it.
  - `StationIdentityRepository(Impl)` exposes `observeIdentity`, `isProvisioned`, `getIdentity`, `provision(stationId, displayName, logoBytes, rawPin)`, `verifyPin`, `updatePin`, `reset`. The repository is the only place that touches `PinHasher` — viewmodels never see the raw PIN after submission.
- New `domain.security` package:
  - `PinHasher` — PBKDF2-HMAC-SHA256, 100 000 iterations, 32-byte derived key, fresh 16-byte salt per device. Verify uses `MessageDigest.isEqual` for constant-time comparison so a timing side-channel can't sniff which digit is wrong. Both hash and salt are persisted as Base64-NO_WRAP strings.
  - `SecurityPreferences` (`@Singleton @Inject`) — single source of truth for the PIN-bypass flag. Seeded from `BuildConfig.DEBUG` at construction; `setPinBypassEnabled(...)` is a hard no-op in release builds so a bad release config can never flip it on. Not persisted: the flag must not survive a reinstall.
- `data.db.SmartPumpDatabase` + `di.DatabaseModule` — added `StationIdentityEntity` to the `@Database` entities array, bumped `version = 2`, exposed `stationIdentityDao()`, provided the DAO, and bound the repository implementation behind the `StationIdentityRepository` interface.
- New `ui.onboarding` package:
  - `IdentityGateViewModel` — collects `repo.observeIdentity()` into a `GateState` sealed flow (`Loading` → `NotProvisioned` / `Provisioned(identity)`). On init, in `BuildConfig.DEBUG` only, auto-provisions `"DEMO-001"` / `"Demo Station"` / no logo / PIN `"0000"` when `!isProvisioned()`. Also exposes `pinBypassEnabled: StateFlow<Boolean>` (mirrors `SecurityPreferences`) and `suspend fun verifyPin(...)` so `AttendantOverlayHost` can wire the PIN gate without injecting the repo directly into Compose.
  - `OnboardingViewModel` — three-step flow (`Identity` → `Logo` → `Pin`) with internal `PinSubStep.Entering` / `Confirming`. Auto-advances when the active PIN row hits 4 digits; PIN mismatch clears both rows and shows a one-shot `pinMismatchFlash` banner. `loadLogoFromUri(uri)` runs the decode + scale on `Dispatchers.IO` — bounds probe via `inJustDecodeBounds`, then a power-of-two `inSampleSize`, then a final scale so the longer side ≤ 512 px, compressed back to PNG bytes. On finish, calls `repo.provision(...)` which writes the hash + salt; the gate VM then flips to `Provisioned` and MainActivity drops into the customer host.
  - `OnboardingScreen` — full-screen Compose flow with `PumpHeader` + a brand-blue card per step. Step 1 uses two `OutlinedTextField`s (station ID auto-uppercased, alphanumeric + `-` only, capped at 32 chars; display name capped at 48). Step 2 uses `ActivityResultContracts.PickVisualMedia` (`PickVisualMediaRequest(ImageOnly)`) — no `READ_EXTERNAL_STORAGE` permission needed at any API level. Step 3 reuses the existing `NumericKeypad` so the visuals match the cash-fixed entry screen.
- `ui.attendant.PinEntryModal` — reusable PIN keypad modal. 4-dot indicator + `NumericKeypad`. Auto-submits on the 4th digit via a `LaunchedEffect(typed)`; wrong PIN does a 5-segment `Animatable<Float>` shake (≈300 ms total) and clears the entry. `inFlight` flag blocks digits during the suspending verify call so a double-tap can't race the auto-submit. Cancel pill leaves the panel up; the scrim tap also dismisses. No lockout in V1 — OPEN_QUESTIONS resolution captured this intentionally.
- `ui.attendant.AttendantOverlay` — the three `ActionCard`s now just signal intent (`onClick = { if (enabled) onFillUpAuthorise() }`). The previous in-card `onDismiss()` was removed because the host now decides when to tear the panel down (after PIN verification, not action tap).
- `ui.attendant.AttendantOverlayHost` — gained `pinBypassEnabled: Boolean` + `verifyPin: suspend (String) -> Boolean` parameters and an internal `pendingAction: AttendantAction?` (private enum). Tapping an action calls `requestAction(...)`: if bypass is on, fire-and-dismiss immediately; otherwise park the action and render `PinEntryModal` on top of the panel. The modal's `onSuccess` fires the parked action and tears down both modal and panel; `onCancel` just clears the parked action and leaves the panel up so the attendant can retry.
- `ui.customer.IdleScreen` — signature change: now takes `displayName: String` + `logoBytes: ByteArray?`. New private `StationBranding` composable decodes `logoBytes` once per change (via `remember(logoBytes)`) and renders the `Image` with `ContentScale.Fit` if present, otherwise `HeroSerifText(displayName)` in brand blue. The hard-coded "Balanceè" wordmark is gone; the brand-blue card border + brand-blue "Start transaction" CTA stay — that's product chrome, not station chrome.
- `ui.customer.CustomerStateHost` — gained `identity: StationIdentity?` parameter; forwards `identity.displayName` / `identity.logoBytes` into `IdleScreen`. Nullable for previews; in production it's non-null because `MainActivity` only renders the host once the gate is `Provisioned`.
- `MainActivity` — `SmartPumpRoot` now hilt-injects both `CustomerViewModel` and `IdentityGateViewModel`. The root `when` over `GateState` renders `OnboardingScreen` for `NotProvisioned`, the customer/attendant host for `Provisioned(identity)`, and a blank `Background` box for the one-tick `Loading` window. The debug long-press hotspot sits in the outermost `Box` (above the gate switch) so a tester can reset onboarding without finishing a half-broken install.
- `ui.debug.DebugViewModel` + `DebugScreen` — added a brand-blue `Security` card visible only when `BuildConfig.DEBUG`: a `Switch` bound to `SecurityPreferences.pinBypassEnabled`, a `LedgerRow` readout of the current `stationId` / `displayName`, and a `Re-run onboarding` secondary button that calls `identityRepo.reset()`. Reset feedback line goes green on success, red on failure. In debug builds, the `IdentityGateViewModel` will re-seed the demo identity on the next launch — so "re-run onboarding" effectively cycles the gate once for testers. To walk the real install flow in a debug build, flip the bypass toggle off and reset; the demo seed only fires when no identity row exists at VM init time.
- Verified with `gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL. One Kotlin language-level warning surfaced (`@ApplicationContext` annotation target — KT-73255 transition); not a code issue and not addressed in this phase.
- Documentation: resolved OPEN_QUESTIONS #19 (V1 PIN gate, no roles); moved to a new "Resolved" section at the bottom of the file so future sessions don't re-ask.

**Out of scope (intentionally deferred):**
- Cashier-tablet → pump PIN-push channel — Phase 7 (production wiring).
- Backend station-ID validation during onboarding — Phase 7.
- Role-based PINs (manager vs attendant) — V2.
- PIN-attempt lockout / throttling — V2.
- The new color palette (muted gold, deeper brand blue) and Playfair Display / Outfit fonts — **Phase 5d**, the next deliverable after this one.

**Next:**
Phase 5d — typography & palette refresh. Add `res/font/` xml resources for Playfair Display (headings + hero-serif), Outfit (body / UI), JetBrains Mono (already in use). Swap `ui/theme/Color.kt` tokens — `Background #0A0A0F → #0B0B0A`, `BrandBlue #1B3FB8 → #1034A6`, `PrimaryAmber #F5A623 → #C8A84B` (rename to `PrimaryGold` since "amber" no longer fits the muted brass hex), `SuccessGreen #48BB78 → #3AAA6A`, `TextPrimary #F7F7F8 → #E8E4DC`, `TextSecondary #A0A0AB → #A09C94`. `WarningRed` stays as-is unless the boss revisits. `OnPrimary` / `OnBrand` re-derived against the new fills. Orange `#D4622A` deferred — no use yet per the user's 2026-05-23 direction. Update `docs/design-system.md` palette table in lockstep.

---

### Phase 5d (rebuild) — Typography & palette refresh
**Date:** 2026-05-23
**Status:** done
**Commit(s):** d2e8344

**Summary (plain language):**
The pump app now wears the brand fonts and the new colour palette the boss specified. Headings and body text use Outfit (a clean modern sans), hero phrases use Playfair Display Italic (a high-contrast serif), and every number on the screen — litres, naira amounts, transaction IDs — uses JetBrains Mono. None of the .ttf files are shipped in the app; the fonts download once per device from Google Fonts via Play Services and stay cached. The gold accent shifts from a vibrant fintech orange to a more muted brass (`#C8A84B`), the brand blue deepens (`#1034A6`), the background warms slightly (`#0B0B0A`), and the body text drops to a warmer off-white. The orange the boss mentioned (`#D4622A`) is reserved in the palette but not used anywhere — it'll get a callsite when there's a screen that needs it.

**Technical notes:**
- **Fonts via Google Fonts downloadable provider:**
  - New dependency: `androidx.compose.ui:ui-text-google-fonts` (BOM-resolved version; declared in `libs.versions.toml` as `androidx-compose-ui-text-google-fonts` and added to `app/build.gradle.kts`).
  - New file `app/src/main/res/values/font_certs.xml` — the canonical Google Play Services downloadable-fonts certificate array (`com_google_android_gms_fonts_certs` → `_dev` + `_prod` `string-array`s). Pulled verbatim from `android/user-interface-samples` upstream; don't edit unless Google rotates the cert.
  - `ui/theme/Type.kt` rewritten — `GoogleFont.Provider(providerAuthority = "com.google.android.gms.fonts", providerPackage = "com.google.android.gms", certificates = R.array.com_google_android_gms_fonts_certs)`. Three `FontFamily`s via the `androidx.compose.ui.text.googlefonts.Font(...)` factory: `BodyFamily` (Outfit, weights 400/500/600), `HeroSerif` (Playfair Display, italic 500 + roman 600), `DisplayMono` (JetBrains Mono, weights 400/600). The Compose `displayLarge` / `displayMedium` / `displaySmall` styles point at `DisplayMono`; everything else (`headlineLarge` → `labelSmall`) at `BodyFamily`. `HeroSerifItalic` style points at `HeroSerif` with `FontStyle.Italic`.
  - Compile hiccup during the change: had to import `androidx.compose.ui.text.googlefonts.Font` instead of the shadowed `androidx.compose.ui.text.font.Font` so the `(googleFont, fontProvider, weight)` overload resolved. The two `Font` symbols collide; the body-text family needs the googlefonts version.
- **Palette swap (Color.kt):**
  - `Background #0A0A0F → #0B0B0A` (warmer near-black)
  - `BrandBlue #1B3FB8 → #1034A6` (deeper)
  - `PrimaryAmber #F5A623 → PrimaryGold #C8A84B` — token RENAMED. The hex shifts from vibrant amber to muted brass, and the old name no longer describes the colour. Every reference (23 files) updated via a single PowerShell sweep over `app/src/main/java/**/*.kt` (`PrimaryAmber → PrimaryGold` and `0xFF0A0A0F → 0xFF0B0B0A` in the same pass — 35 files touched). Two "amber" mentions in human-readable comments cleaned up manually (`BalanceeButton.kt` header, `AttendantOverlayHost.kt` swipe-handle comment).
  - `SuccessGreen #48BB78 → #3AAA6A` (deeper)
  - `TextPrimary #F7F7F8 → #E8E4DC` (warm off-white)
  - `TextSecondary #A0A0AB → #A09C94` (warm grey)
  - `OnPrimary` re-derived to `#0B0B0A` (tracks new Background); `OnBrand` to `#E8E4DC` (tracks new TextPrimary).
  - New token `AccentOrange #D4622A` — declared but not referenced anywhere. Boss-specified but undirected. Wire on demand.
  - The previously hardcoded `Color(0xFF0A0A0F)` in `NumericKeypad.kt` (the "text-on-filled-key" colour) was updated to `0xFF0B0B0A` by the bulk sweep so the foreground matches the new on-color token semantically.
- **`Theme.kt`** — `darkColorScheme.primary` now `PrimaryGold`; no other change. Material 3 Compose components inherit the new gold for FAB-style affordances we don't currently use, but the token rename keeps the linkage correct.
- **`docs/design-system.md`** — palette table, accents list, text tokens, typography section, and the `BalanceeButton` primitive description all updated in lockstep. State-color → border mapping table refreshed with the new hexes; rule-of-thumb paragraph kept (still gold = "receipt", green = "dispense succeeded").
- Verified with `gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL (after the Font import fix). One Kotlin language-level warning still present from Phase 5c (KT-73255, `@ApplicationContext` annotation target) — not addressed here.
- Memory: updated `project_color_roles.md` with the new hexes + the `PrimaryAmber → PrimaryGold` rename, plus the new typography stack.

**Runtime behaviour note:**
Google Fonts downloadable provider fetches each font on first use per device and caches indefinitely (the Play Services font cache survives reboots). On a fresh install with no network, the request fails silently and Compose falls back to the system serif/sans/mono — visually degraded but not broken. The kiosk install flow connects to WiFi/4G before onboarding finishes, so the cache will be warm well before the customer sees any of these fonts on the screen. No font-loading UX (placeholder text) is needed.

**Out of scope (intentionally deferred):**
- Orange `#D4622A` usage — declared in `Color.kt` as `AccentOrange` but no callsite. Boss said "leave out for now."
- Bundled `.ttf` fallback — if Play Services is ever absent on a target device, we'd want offline-resilient `res/font/` entries. Not in V1.
- Font weight/size review against the new families — Playfair and Outfit have slightly different x-heights vs. system serif/sans, so the existing sp values may need a fine-tune pass once the boss reviews real renders. Tracked as a Phase 5e candidate.

**Next:**
Phase 6 — strict-design screen-matching polish series. A side-by-side review of every customer + attendant screen against `docs/Strict design screens/*.png` surfaced real divergences: per-screen state chip placement (currently in `PumpHeader`, spec wants it inside the card), captions under each card mockup, ledger-row ordering, the pre-pay QR screen splitting into two cards where the spec shows one, and `PumpHeader` mode labels that should be flow-aware (e.g. `PUMP 1 · DIGITAL PRE-PAY` rather than `PUMP 1 · IDLE`). Tackled flow-by-flow as sub-phases — each leaves the build green and ships as its own commit:

- **Phase 6a** — Idle + Mode Select (`docs/Strict design screens/Screenshot 2026-05-11 224941.png`)
- **Phase 6b** — Flow 1 (Fixed Pre-pay Digital), V1-required + most visible (`224956.png`)
- **Phase 6c** — Flow 4 (Cash Fixed) — simpler refactor, attendant-driven (`225053.png`)
- **Phase 6d** — Flow 2 (Fill-up Cash) (`225010.png`)
- **Phase 6e** — Flow 3 (Fill-up Digital) (`225038.png`)
- **Phase 6f** — Flow 5 (USSD Offline) (`225113.png`)
- **Phase 6g** — Attendant overlay polish (`225136.png`)

After Phase 6, manual visual review on a real device, boss sign-off, then merge `rebuild/strict-design` → `main`. Then Phase 7 (production wiring — USB serial Arduino driver, real Balanceè payment SDK, SMS `BroadcastReceiver` for Flow 5 GTBank parsing, WorkManager backend sync for the audit table, FCM/HTTP channel for operator price/config push, including the cashier-tablet → pump PIN-push channel deferred from Phase 5c). Phase 6 was previously slated as the production-wiring phase; renumbered 2026-05-23 to make room for the strict-design polish series.
