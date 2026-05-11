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
