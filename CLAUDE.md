# SmartPump Display — Claude instructions

## At the start of every session

Before responding to the user's first request, read the contents of `docs/specs/` to ground yourself in the current project state. This folder contains:

- `PROJECT_LOG.md` — append-only log of completed phases. Read this first; it is the fastest way to understand what has been built and what is next.
- `Screen spec *.pdf` — UI/screen specifications for the app.
- Any other design docs (`design-system.md`, `OPEN_QUESTIONS.md`, `scaffolding-prompt.md` live in `docs/`).

Do not summarise the specs back to the user unsolicited — just load them as context.

## After every completed phase / stage / run / milestone

Append a new entry to `docs/specs/PROJECT_LOG.md` following the template and rules at the top of that file. The log is append-only — never rewrite or delete prior entries.
