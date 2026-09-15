# Dev API probe — 2026-09-12

Raw, unedited captures from the **first requests this project has ever made to a real Balancee
server** (`https://api.dev.balancee.app`). Every prior test in the repo runs against MockWebServer
fed by fixtures we wrote ourselves from the Reference PDF.

**No credentials were sent and none exist.** Every value in the header probe is obvious filler
(`probe_not_a_real_key`, an all-zero device id, `deadbeef` as the signature). The activation probe
carries an empty body `{}`, so it cannot redeem a code. Nothing here spends the one-shot activation
step.

Files are `body-*.json` (response body, verbatim bytes) and `headers-*.txt` (full response headers).
Fixtures built from these must copy the bytes, not a restatement of them — restating the shape is
exactly how the response-envelope defect (TODO #11) survived a green suite.

| capture | request | status |
|---|---|---|
| `config-no-auth` | `GET /api/pump/config`, no headers | 401 |
| `transactions-no-auth` | `GET /api/pump/transactions/probe-not-a-real-id`, no headers | 401 |
| `config-fake-creds` | `GET /api/pump/config`, our four signing headers with filler values | 401 |
| `activate-empty-body` | `POST /api/pump/activate`, body `{}` | 400 |
| `unknown-route-control` | `GET /api/pump/definitely-not-an-endpoint` | 404, HTML |

The control is what makes the rest mean anything: a route the backend never built returns a
Vercel/Next.js HTML 404 with `X-Matched-Path: /404`. A JSON envelope is therefore a real handler
answering, not a framework fallback. The body of that control is not kept (21 KB of HTML); its
headers are.

## What these prove

1. **Both endpoints asked for in `BOSS_CONFIRMATIONS_DRAFT.md` items 1 and 2 are deployed on dev.**
   `X-Matched-Path` settles it beyond the status code: `/api/pump/config` and
   `/api/pump/transactions/[id]` — the second is a parameterised dynamic route, as it should be.
2. **Our four header names are correct.** Sending `X-Api-Key` / `X-Device-Id` / `X-Timestamp` /
   `X-Signature` moves the server off `Missing pump authentication headers` and onto
   `Invalid API key`. Until now the names were only our reading of Reference §3.
3. **The literal failure envelope, which the Reference never prints.** `{"status":false,"message":…}`
   with `data` absent, exactly as §1 describes but never demonstrates. TODO #14 could only guess at
   this before; it is now observed bytes.
4. **A top-level `code` field exists — on one path.** `activate-empty-body` carries
   `"code":"INVALID_REQUEST"`, a sibling of `message`, **not** nested inside `data`. This is the
   stable error code asked for in draft item 3, so that ask was at least partly built.

## What they do NOT prove, and cannot

- **Nothing about any success payload.** A 401 proves a route exists and is guarded. The `/config`
  response shape — the entire point of item 1 — is still unverified.
- **The `code` field is inconsistent.** Present on the 400; absent from all three 401s. Parsing must
  treat it as optional, and the backend should be asked to fill the gap rather than asked afresh.
- **GET signing and clock skew are unreachable from outside.** The server validates the API key
  *first*: a deliberately stale `X-Timestamp` (2 h old) and a request with `X-Signature` removed
  entirely both return the same `Invalid API key`. So neither "what do we sign for a GET" nor the
  5-minute freshness window (TODO #15) can be tested without a real key. Both now sit behind
  activation.

## Reproducing

`probe.sh` in this directory re-runs all five. It sends no secrets and is safe to run at any time.
