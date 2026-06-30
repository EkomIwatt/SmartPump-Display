# SmartPump Display — Claude instructions

## At the start of every session

Before responding to the user's first request, read these in order to ground yourself in the current project state:

1. `docs/journal/PROJECT_LOG.md` — completed-phase log; the "Current status" header at the top is the fastest way to see where things stand. (Older entries live in `PROJECT_LOG_ARCHIVE.md` — history only, no need to read at session start.)
2. `docs/flows.md` — the five transaction flows: customer screen sequence, state list, payload examples.
3. `docs/state-machine.md` — sealed-class hierarchy + transition table per flow.
4. `docs/design-system.md` — visual language (colors, typography, components, layout rules).
5. `docs/journal/OPEN_QUESTIONS.md` — open decisions.

Skim the screenshots in `docs/Strict design screens/` when the work touches a specific flow.

Do not summarise these docs back to the user unsolicited — just load them as context.

## Authority order

When sources disagree, this is the priority:

1. **`docs/Strict design screens/*.png`** — authoritative for visual layout, copy, and state labels. Build exactly what is shown. If a constraint makes it impossible to match, flag it before deviating.
2. `docs/flows.md` and `docs/state-machine.md` — authoritative for state transitions and business logic.
3. `docs/design-system.md` — authoritative for tokens (color, type, spacing).
4. Any other doc — secondary.

Superseded design materials (old screen-spec PDFs, the original scaffolding prompt) were removed during cleanup — git history retains them if ever needed. There are no `_archive/` folders to consult.

## After every completed phase / stage / run / milestone

Append a new entry to `docs/journal/PROJECT_LOG.md` following the template and rules at the top of that file. The log is append-only — never rewrite or delete prior entries (older entries are split into `PROJECT_LOG_ARCHIVE.md`; leave that file alone).

## House rules

- Wait for an explicit "go" before starting a new phase. Each phase should be self-contained and leave the build green.
- The user works in well-defined stages; do not roll multiple phases into one without permission.
- Commit per logical sub-deliverable. Phase 3 in particular is committed flow-by-flow (3a, 3b, ...).
- Feature/phase work happens on a short-lived branch off `main`; merge to `main` only when the phase is verified and the build is green. (The original `rebuild/strict-design` rebuild is long since merged.)
