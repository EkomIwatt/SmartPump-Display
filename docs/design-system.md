# SmartPump Display — Design System

Visual language: **industrial brutalism with warmth** — a fuel-pump display crossed with a modern fintech app. Dark theme only. No Material defaults.

The screenshots under `docs/Strict design screens/` are authoritative. This file defines the tokens.

---

## Colors

### Canvas
- **Background:** `#0A0A0F` (near-black, slight blue tint)
- **Surface:** `#13131A` (cards, elevated elements)
- **Surface variant:** `#1C1C26` (subtle elevation)
- **Code-panel surface:** `#0F0F16` (the dark code/log blocks)
- **Border subtle:** `#2A2A38` (default 1dp card border)

### Accents
- **Primary / action amber (gold):** `#F5A623` — AUTHORISE buttons, hero italic display ("Every state. Every flow."), gold borders on cash-mode cards
- **Primary on-color:** `#0A0A0F`
- **Active cyan (dispensing / filling):** `#4FD1C5` — fill-up dispensing cards, hero numbers in fill mode
- **Success green:** `#48BB78` — CONFIRMED / PAID / COMPLETE states, green-bordered completion cards
- **Warning red:** `#F56565` — error banners, "V1 REQUIRED" badges
- **Brand blue (cover only):** `#1B3FB8` — used on the spec cover; not used in the runtime app

### Text
- **Primary:** `#F7F7F8`
- **Secondary:** `#A0A0AB`
- **Tertiary:** `#5A5A6B`
- **On amber:** `#0A0A0F`
- **On cyan:** `#0A0A0F`
- **On green:** `#0A0A0F`

### State-color → border mapping (critical)
Cards carry a 1dp border in the state color:

| State                        | Border color   | Token        |
|------------------------------|----------------|--------------|
| WAITING (pre-pay QR shown)   | gold           | `#F5A623`    |
| AWAITING SMS (USSD)          | blue/gold mix  | `#F5A623`    |
| DISPENSING — fixed/pre-pay   | green          | `#48BB78`    |
| DISPENSING — fill-up         | cyan           | `#4FD1C5`    |
| DISPENSING — cash fixed      | gold           | `#F5A623`    |
| TANK FULL (verified count)   | gold           | `#F5A623`    |
| CONFIRMED / COMPLETE / PAID  | green          | `#48BB78`    |
| IDLE / default               | border-subtle  | `#2A2A38`    |

The state's chip pill (top-left of card) uses the same color, filled with ~15% alpha on the canvas.

---

## Typography

- **Display serif italic** (hero phrases — "Every state. Every flow.", "Fixed amount, pay before fuel flows."): a high-contrast serif italic. Use `PlayfairDisplay-Italic` or system serif italic. Weight 400–500. Used at 32sp+ on headings, gold color.
- **Display monospace** (litre counts, naira amounts on dispensing/complete screens): JetBrains Mono or Space Mono. **120sp+** for the live litre count — this is non-negotiable.
- **Headings:** Inter / system sans-serif, weight 600.
- **Body:** Inter, weight 400.
- **Labels (small all-caps):** Inter, weight 500, letter-spacing 0.1em, all uppercase, color = text-secondary or text-tertiary.
- **Code / monospace panel:** JetBrains Mono, weight 400, color text-secondary on the code-panel surface. Syntax highlighting tokens: keywords cyan-ish (`#4FD1C5`), strings green-ish (`#48BB78`), numbers gold (`#F5A623`), comments tertiary.

---

## Layout

- **Full-bleed, no app bar, no nav bar** — kiosk mode. Landscape locked.
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
- **`BalanceeButton`** — primary variant: amber bg, dark text, 64dp tall, all-caps label. Secondary variant: transparent bg, border-subtle border, text-primary label. Disabled: text-tertiary, border-subtle, no fill.
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
