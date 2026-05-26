# SmartPump Display — Design System

Visual language: **industrial brutalism with warmth** — a fuel-pump display crossed with a modern fintech app. Dark theme only. No Material defaults.

The screenshots under `docs/Strict design screens/` are authoritative. This file defines the tokens.

---

## Colors

Phase 5d (2026-05-23) palette refresh — gold pivoted from fintech orange to muted brass; brand blue deepened; background warmed; text dropped to a warm off-white. Token names changed (`PrimaryAmber` → `PrimaryGold`).

### Canvas
- **Background:** `#0B0B0A` (near-black, warm)
- **Surface:** `#13131A` (cards, elevated elements)
- **Surface variant:** `#1C1C26` (subtle elevation)
- **Code-panel surface:** `#0F0F16` (the dark code/log blocks)
- **Border subtle:** `#2A2A38` (default 1dp card border)

### Accents
- **Primary / action gold:** `#C8A84B` (`PrimaryGold`) — AUTHORISE buttons, hero italic display ("Every state. Every flow."), gold borders on cash-mode cards. Muted brass — not vibrant amber.
- **Primary on-color:** `#0B0B0A` (`OnPrimary`)
- **Active cyan (dispensing / filling):** `#4FD1C5` — fill-up dispensing cards, hero numbers in fill mode
- **Success green:** `#3AAA6A` — CONFIRMED / PAID / COMPLETE states, green-bordered completion cards
- **Warning red:** `#F56565` — error banners, "V1 REQUIRED" badges
- **Brand blue:** `#1034A6` — Balanceè brand surface. Used on the **Idle screen** (card border, "Start Transaction" CTA, station-name fallback) and the Balanceè-app context. Also on the spec cover. The Idle screen is the only place the brand-blue dominates — once a transaction starts, the canvas hands over to the per-state accents (gold / cyan / green). Pair with `OnBrand` (`#E8E4DC`) for text on blue.
- **Accent orange:** `#D4622A` (`AccentOrange`) — reserved per Phase 5d spec; no current callsite. Use only when a future state explicitly demands an orange accent.

### Text
- **Primary:** `#E8E4DC` (warm off-white)
- **Secondary:** `#A09C94` (warm grey)
- **Tertiary:** `#5A5A6B`
- **On gold:** `#0B0B0A`
- **On cyan:** `#0B0B0A`
- **On green:** `#0B0B0A`

### State-color → border mapping (critical)
Cards carry a 1dp border in the state color:

| State                                 | Border color   | Token        |
|---------------------------------------|----------------|--------------|
| WAITING (pre-pay QR shown)            | gold           | `#C8A84B`    |
| AWAITING SMS (USSD)                   | gold           | `#C8A84B`    |
| DISPENSING — fixed/pre-pay            | green          | `#3AAA6A`    |
| DISPENSING — fill-up                  | cyan           | `#4FD1C5`    |
| DISPENSING — cash fixed               | gold           | `#C8A84B`    |
| TANK FULL (verified count)            | gold           | `#C8A84B`    |
| CONFIRMED / PAID                      | green          | `#3AAA6A`    |
| COMPLETE — Flow 1 receipt (digital pre-pay) | gold     | `#C8A84B`    |
| COMPLETE — cash / fill-up / USSD      | green          | `#3AAA6A`    |
| IDLE / default                        | border-subtle  | `#2A2A38`    |

Rule of thumb: the **gold** completion is the "receipt" feeling — the customer paid up front and now sees their itemised receipt. The **green** completion is the "dispense succeeded" feeling — the action just finished. Flow 1 is the only flow that pays before fuelling, so it's the only flow whose Complete is gold.

The state's chip pill (top-left of card) uses the same color, filled with ~15% alpha on the canvas.

---

## Typography

All three families load via Google Fonts downloadable provider (`androidx.compose.ui:ui-text-google-fonts`). No `.ttf` shipped in `res/font`. Cert array lives in `res/values/font_certs.xml` — standard Google Play Services certs, do not edit.

- **Display serif italic** (hero phrases — "Every state. Every flow.", "Fixed amount, pay before fuel flows."): **Playfair Display Italic**, weight 500. Used at 32sp+ on headings, gold color by default.
- **Display monospace** (litre counts, naira amounts on dispensing/complete screens): **JetBrains Mono**, weight 400 or 600. **120sp+** for the live litre count — this is non-negotiable.
- **Headings:** **Outfit**, weight 600.
- **Body:** **Outfit**, weight 400.
- **Labels (small all-caps):** Outfit, weight 500, letter-spacing 0.1em, all uppercase, color = text-secondary or text-tertiary.
- **Code / monospace panel:** JetBrains Mono, weight 400, color text-secondary on the code-panel surface. Syntax highlighting tokens: keywords cyan-ish (`#4FD1C5`), strings green-ish (`#3AAA6A`), numbers gold (`#C8A84B`), comments tertiary.

---

## Layout

- **Full-bleed, no app bar, no nav bar** — kiosk mode. Rotatable: the activity follows the device orientation (`fullSensor`) and reflows in place (handled via `configChanges`, no recreation). Screens are designed landscape-first; in portrait the side-by-side panes stack vertically (QR/ledger, dial/waiting, amount/keypad, dispensing ledger/figure) via the `ui/util/isPortrait()` helper. Stacked panes stay height-bounded (equal weights) — never combine the stacked column with `verticalScroll` while panes still use `weight`/`fillMaxHeight`, or Compose measures against an infinite height constraint and crashes.
- **Screen padding:** 32dp default.
- **Card padding:** 24dp internal.
- **Card border:** 1dp solid in the state color (see table above). **No shadows** — shadows look soft; we want crisp/industrial.
- **Corner radius:** 12dp on cards, 8dp on buttons, 6dp on chips.
- **Button height:** minimum 64dp (gloved hands, daylight, kiosk).
- **High contrast everywhere** — direct sunlight viewing.
- **Three-card-row pattern:** most flow screens show the customer journey as a horizontal row of 3 phase cards (e.g. WAITING → CONFIRMED → COMPLETE). Cards are equal-width, separated by 16dp gaps.

---

## Component primitives

- **`BalanceeCard`** — surface background, 1dp border (color from state), 12dp radius, 24dp internal padding. The border-color parameter is required.
- **`BalanceeButton`** — primary variant: gold bg, dark text, 64dp tall, all-caps label (AUTHORISE-style actions inside flows). Secondary variant: transparent bg, border-subtle border, text-primary label. Brand variant: brand-blue bg, light text — used by the Idle-screen "Start Transaction" CTA only. Disabled: text-tertiary, border-subtle, no fill.
- **`LitresDisplay`** — giant monospace number (e.g. "3.42") with "L" appended in text-secondary at ~40% of the number size. Color follows state (cyan for fill-up, green for confirmed, gold for cash-fixed).
- **`AmountDisplay`** — same scale as `LitresDisplay` but with the ₦ prefix. Used for the cash-fixed and post-fill QR screens.
- **`StateChip`** — 6dp radius pill, padding 4dp/8dp, all-caps label in label type. Filled color from the state table at ~15% alpha. Outline 1dp at full color. Includes a 6dp leading dot at full color (e.g. `• WAITING`).
- **`LabelText`** — small all-caps label (see typography).
- **`LedgerRow`** — left label (text-secondary, all-caps small), right value (text-primary, mono or sans depending on field). Used on completion screens (LITRES, PAID, PRICE/L, TXN rows).
- **`NumericKeypad`** — 3×4 grid, large buttons (64dp+), monospace digits. Used on Cash Fixed entry and amount entry.
- **`QrCodeView`** — black bg, white modules, 24dp padding around the QR. Sits on the cyan/gold bordered card matching the screen state.
- **`CodePanel`** — code-panel surface, 8dp radius, monospace text, padding 16dp. Used in spec docs and debug screens; not customer-facing in production but used on the spec pages.
- **`HeroSerifText`** — the serif-italic hero phrases. Gold color, italic.
- **`PumpHeader`** — top strip showing pump number, mode (PUMP 1 · FILL-UP), and the state chip. Minimal — see the screens.

---

## Motion

- Attendant overlay: `translateY` slide-up, 250ms ease-out. Dismiss same duration ease-in.
- State transitions on the customer screen: fade-cross 200ms.
- Pulse-tick on the live litre count: no animation — let the number flicker naturally as it updates.

---

## Things NOT to use

- No `TopAppBar`, no `BottomNavigation`, no Material `Scaffold` defaults. Build raw.
- No shadows. No elevation tonal overlays.
- No Lorem Ipsum — use realistic Nigerian fuel-station copy ("Tap to pay", "Total Lekki Ph2", "₦5,000 auth", etc.).
- No light theme.
- No softening — no rounded corners > 12dp, no gradients other than the cover.
