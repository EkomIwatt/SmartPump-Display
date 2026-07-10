# Boss confirmations — draft message (TODO #6)

**Purpose:** the 7 items from `phase7_blocker_resolution.md` → "Pending boss confirmation", written up
to send. Ordered by how much each blocks. Items 1 & 2 are the real unblockers for the payment feature
flows (TODO #8); the rest can trail slightly.

**Status:** draft, not yet sent. When answers come back → reconcile into `OPEN_QUESTIONS.md`, update
`phase7_blocker_resolution.md`, and unblock #8. Edit freely before sending.

_Drafted 2026-07-04._

---

**Subject: Pump app — 7 confirmations needed before we build the payment flows**

Hi [Boss],

We've built the pump's network foundation against the **Pump API Reference** — request signing, the
API client, encrypted credential storage, all tested. Before we build the money-touching flows
(activation, authorise/QR, payment confirmation, dispense upload), I need to lock down a few things so
we don't build against guesses. Ordered by priority:

**1. Is the Pump API Reference the canonical contract? _(blocks everything)_**
Everything below assumes the Reference is final. If it's still moving, tell me what's unsettled — this
is the one answer that gates the whole payment phase.

**2. Does the ordered tablet have Google Play Services? _(unlocks 3 features at once)_**
This single answer decides our push channel: **yes → FCM**, **no → a persistent WebSocket** the pump
maintains. It drives payment-PAID notifications, live price pushes, and future credential rotation. I
can't start the push work without it.

**3. Confirm these two GET endpoints exist (or are planned) backend-side:**
   - `GET /api/pump/transactions/{id}` — we poll this every 10s during the payment window as the
     fallback if the PAID push misses.
   - `GET /api/pump/config` — the pump fetches current price on boot and before every sale (this is
     our correctness guard; the server rejects any sale where `amount ≠ expectedLitres × price`).

**4. Two small contract details on the above:**
   - **Money unit on `amount`** (and the config price) — naira or **kobo**? Our app carries money as
     kobo end-to-end; I need to know what unit to send/expect so the `amount === expectedLitres ×
     price` check passes.
   - The exact **`/config` payload shape** (price per fuel type) once it's settled.

**5. ~~Staging environment~~ + a test activation code.**
_URLs received 2026-07-04 — prod `api.balancee.app`, dev `api.dev.balancee.app` (wired into the build)._
Still need a **test activation code** we can redeem against the dev backend to exercise the
activation flow end-to-end.

**6. Late-payment policy _(doesn't block our code, but decide before field test)_.**
If a customer's money lands *after* the QR window closed, what happens — auto-refund, wallet credit,
or a manual review queue? The pump is stateless about late arrivals (idempotent, only honours what
it's actively waiting on), so this is purely a backend policy call.

**7. Confirming our understanding — offline USSD is deferred to a future update.**
V1 ships **online-only** (Paystack QR, which needs connectivity). The genuinely-offline USSD/SMS mode
(Flow 5) is parked for a later update, not cut — just confirming that's still the plan.

For context: none of this needs to rush toward live money — I understand live customer payments stay
gated behind the 14-day parallel-run accuracy check regardless. We just want to build and sandbox-test
correctly in the meantime.

Thanks,
[You]
