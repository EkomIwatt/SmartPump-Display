# Production API probe — 2026-09-16

The same five unauthenticated requests as the [2026-09-12 dev probe](../2026-09-12/README.md), run
against **`https://api.balancee.app`** (production). Same script, different base URL:
`./probe.sh https://api.balancee.app`.

**No credentials were sent and none exist.** Filler key (`probe_not_a_real_key`), all-zero device id,
`deadbeef` signature; the activation probe carries an empty body `{}` so it cannot redeem a code.
Nothing here spends the activation code or creates a pump.

## Why production was probed at all

An activation code arrived on 2026-09-16 from the operator dashboard at
`https://smartpump.balancee.app/dashboard/pumps`. Devtools showed that dashboard posting **GraphQL to
`api.balancee.app`** — production, and a different API style from the REST pump surface our client
speaks. Two things needed settling before the code could be used: whether the `/api/pump/*` REST
routes exist on production at all, and whether they behave as dev does.

## Result: production is byte-identical to dev

All four response bodies match the dev captures exactly (`diff` clean), and every `X-Matched-Path`
resolves to the same handler:

| capture | request | status | `X-Matched-Path` |
|---|---|---|---|
| `config-no-auth` | `GET /api/pump/config`, no headers | 401 | `/api/pump/config` |
| `transactions-no-auth` | `GET /api/pump/transactions/probe-not-a-real-id` | 401 | `/api/pump/transactions/[id]` |
| `config-fake-creds` | `GET /api/pump/config`, four filler signing headers | 401 | `/api/pump/config` |
| `activate-empty-body` | `POST /api/pump/activate`, body `{}` | 400 | `/api/pump/activate` |
| `unknown-route-control` | `GET /api/pump/definitely-not-an-endpoint` | 404, HTML | `/404` |

## What this proves

1. **The REST pump surface is deployed on production**, including the two endpoints that were only
   confirmed on dev in September. The GraphQL dashboard sits alongside it; it does not replace it.
2. **Our four header names are right on production too** — sending them moves the server off
   `Missing pump authentication headers` and onto `Invalid API key`.
3. **The `code` field is inconsistent on production exactly as on dev** (#18f): present on the 400
   (`INVALID_REQUEST`), absent from all three 401s. The gap is in the contract, not in one deployment.

## What it does NOT prove

Everything the dev probe could not prove, for the same reason: a 401 shows a route exists and is
guarded, never what a success payload looks like. `/config`'s shape, GET signing and the clock-skew
window (#15) all still sit behind a redeemed activation code.

## The finding that matters more than any of the above

**The code we hold is minted against production, and #32's sequence cannot be run there as written.**
Steps 3, 4 and 7 (`/authorise` happy path, deliberate amount mismatch, decimal amount,
`/transactions/upload`) create real transaction records and push fabricated rows into production
reporting. On dev those are experiments; on production they are dirt in the real system. See TODO #31.
