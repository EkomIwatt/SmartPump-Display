# Boss confirmations — draft message (TODO #6 + #18)

**Purpose:** the still-open items from `phase7_blocker_resolution.md` → "Pending boss confirmation",
**folded together with the #18 backend/spec asks** from the API conformance audit (TODO #18). One
message, because they go to overlapping audiences and several of the #18 asks are sharper versions of
questions the original draft already carried.

**Status:** draft, not yet sent. When answers come back → reconcile into `OPEN_QUESTIONS.md`, update
`phase7_blocker_resolution.md`, and unblock #8 (payment feature flows).

_Originally drafted 2026-07-04. Rewritten 2026-09-02 after the API conformance audit — see
`API_CONFORMANCE_AUDIT.md` §6._

---

## Since the first draft — what no longer needs asking

Do **not** re-send the 2026-07-04 version; two of its seven items are settled and asking again wastes
the reply.

- **Item 2 (Play Services / push channel) — ANSWERED 2026-08-04: FCM.** Tablets will ship with Play
  Services. Now a ratification, not a question; kept below only as a one-line confirmation.
- **Item 5 (staging URLs) — RECEIVED 2026-07-04.** Prod `api.balancee.app`, dev `api.dev.balancee.app`,
  both wired into the build. A code finally arrived 2026-09-16 — but minted against **production**, not
  dev, so the ask narrows rather than closes. See item 4.
- **Money unit on `amount` — we decided it ourselves (naira) rather than blocking on it.** Still worth
  one line of confirmation, but it is no longer a blocker: the server's exact
  `amount === expectedLitres × pricePerUnit` check makes a wrong unit fail closed at `/authorise`
  before money moves or fuel flows.

**What changed the picture:** the Pump API Reference PDF landed on 2026-08-04 — our first sight of the
*primary* document. Auditing the built network layer against it found nine issues. We have fixed the
four that were ours (merged 2026-09-02). **The remaining ones are not ours to fix** — they are gaps in
the contract itself, and they are why this message is now mostly a backend conversation.

---

## The cover note — this is the part he reads

Short by design. Everything under it is for the backend team, not for him.

---

**Subject: Pump app — two things needed from the backend team**

Hi [Boss],

The pump's payment side is built and tested. We audited it against the Pump API Reference, found nine
issues, and have **fixed the four that were ours** — verified on the tablet, done on Wednesday.

The rest we can't fix in the app, because the information isn't there to fix it with. The main one:
**nothing in the API ever tells a pump which fuel it sells or what to charge for it** — and ringing up
a sale needs both. We've worked around it for now, so we're not stuck: a manager types the fuel type
and price into each tablet by hand. But that means **every price change becomes a physical visit to
every pump**, and a pump that gets missed doesn't sell slightly wrong — it stops selling until someone
walks to it. That's workable for a pilot and not for a fleet. There's a second, smaller gap — if a
payment confirmation doesn't reach the pump, it has no way to check whether the customer actually
paid, so someone who has paid could be left standing at a pump that won't dispense.

Both need small additions on the backend. **Their turnaround sets our date, not our work** — which is
why I'm raising it now rather than when we get to that stage.

Full detail is below, written so you can forward it straight on. You only need the bolded first line
of each numbered item; the rest is for whoever picks it up.

Thanks,
[You]

---

## The detail — for forwarding to the backend team

Context for whoever picks this up: the pump app's network layer is built and tested — request
signing, the API client, encrypted credential storage, activation identity. We audited it
line-by-line against the Pump API Reference, found nine issues, and **fixed the four that were on our
side** (verified on the tablet). The five below aren't fixable in the app — they're gaps in the
contract itself, and the first one is currently held together by a manual workaround that will not
survive a fleet. Ordered by how much each blocks us.

---

### 1. Nothing in the API tells a pump what it sells or what to charge. _(highest — this sets the date)_

`POST /authorise` **requires** a `fuelType`. But `POST /activate` returns only `deviceId`, `pumpId`,
`apiKey` and `signingSecret` — nothing about the station or the pump's assignment. The Reference
documents exactly three endpoints (its own §5 cheat sheet confirms this), so **there is no endpoint
that tells a pump what it's selling, or for how much.**

**We are not blocked on this — we built around it.** Rather than wait, we shipped a device-local
config screen: a manager sets fuel type and price on the tablet itself, behind the attendant PIN, and
the pump refuses to sell until both are set. That work isn't throwaway either — it doubles as the
fallback for when the backend is unreachable, so it stays useful once `/config` ships.

**But it can't be the answer, for three reasons:**

- **Price changes weekly, and this turns every change into a physical visit to every pump.** This is
  the one that will actually hurt. Your server rejects any sale where `amount ≠ expectedLitres ×
  pricePerUnit` — so a pump whose price wasn't updated doesn't sell slightly wrong, it **stops selling
  entirely** until someone walks to it. One missed pump is a dead pump, and nothing tells you which.
- **It doesn't scale past a handful of units.** Per-pump manual entry is fine for a pilot and
  unmanageable for a fleet.
- **It puts the price behind an attendant's PIN.** V1 ships a single shared PIN, so anyone who can
  authorise a sale can also change the price per litre. We accepted that as a temporary risk
  specifically because it was meant to be temporary.

**The ask: add `GET /api/pump/config`.** (We'd assumed this endpoint existed — it was **our proposal,
not yours**, and my mistake for carrying it as though it were real.) Deliberately minimal, because
small asks get built:

```json
{ "pumpId":       "7f108b57-…",
  "stationName":  "Total Lekki Ph2",
  "fuelType":     "PETROL",
  "pricePerUnit": 700,
  "updatedAt":    "2026-08-04T09:00:00Z" }
```

A single `fuelType` rather than a map, matching `/authorise` taking exactly one (multi-nozzle is a V2
concern). `stationName` is there because receipts need it.

**Why this can't just ride along on `/activate`:** activation fires once, and its secrets are emitted
once. The static fields could live there — but **price changes weekly**, so it fundamentally cannot.
The pump needs to fetch price on boot and before every sale.

---

### 2. No way to check whether a payment landed. _(high)_

We detect payment via push (FCM) with a short poll as the fallback. The poll endpoint —
`GET /api/pump/transactions/{id}` — isn't in the Reference either.

`PAID` is clearly a real status on your side: §2's lifecycle diagram shows *"Customer Pays →
[Paystack Webhook] → Transaction status set to PAID"*, and §4.3 can error with *"Payment has not been
confirmed for this transaction."* The backend tracks it; nothing exposes it to the pump.

**Why the fallback matters:** push delivery is best-effort — FCM does not guarantee it. With no way
to ask, **a single dropped push strands a customer who has already paid**, standing at a pump that
won't dispense. That's the failure mode that ends up on the phone to you. A read-only endpoint
returning the current status is enough.

**The ask:** add `GET /api/pump/transactions/{id}`, and confirm the **full status set** — §5 lists
only `PENDING_PAYMENT` and `DISPENSED`, but `PAID` is plainly real, so the list looks incomplete and
we'd rather not guess at the others.

---

### 3. Three small contract details. _(quick answers, backend team)_

- **Does `amount` accept decimals, or integers only?** This is sharper than it looks, and it may be a
  **pricing decision rather than a technical one.** A 38.1 L fill-up at ₦870.50/L is ₦33,166.05.
  Integer-only can't express that — and because your check is *exact*, a rounded `33166` is
  **rejected outright**, not merely a naira off. So if `amount` is integer-only, **station pricing is
  constrained to whole naira per litre.** Worth a deliberate answer rather than a default.
  - *(Related, and we've assumed rather than blocked: we're reading `amount` as **naira**, from §4.2's
    example of `amount: 7000` / `expectedLitres: 10` → ₦700/L. Say if that's wrong.)*

- **Please return a stable error code alongside `message`.** Your business errors are the normal
  operating vocabulary of this API — *"Amount mismatch for PETROL…"*, *"PETROL is currently out of
  stock"*, *"Payment has not been confirmed…"* — and the pump has to turn each into something an
  attendant can act on. Right now the only way to tell them apart is matching on the human-readable
  string, several of which have values interpolated into them. **That breaks silently the day someone
  rewords a message** — no error, no alert, the pump just stops recognising a case it used to handle.
  A short stable code (`AMOUNT_MISMATCH`, `OUT_OF_STOCK`, `PAYMENT_NOT_CONFIRMED`) next to the
  existing `message` costs little now and saves a field bug later.

- **What should we sign for a GET request?** §3's signing formula assumes a request body. Neither
  endpoint above has one. We currently sign `timestamp + "." + ""` (empty-string body) — just confirm
  that's what your side will verify, since it only matters for the two endpoints being added.

---

### 4. How do we make one authenticated pump request against **dev**? _(rewritten 2026-09-16)_

**Where this stands.** A code arrived on 2026-09-16 from the operator dashboard
(`smartpump.balancee.app/dashboard/pumps`). That dashboard posts GraphQL to **`api.balancee.app`**, so
the pump it created is a **production** pump. Separately, we were told **dev does not require an
activation code**. Both of those may be true and neither unblocks us yet, so this item is no longer
"send us a code" — it is one question about dev, with the form of the answer left open.

**What we need, stated once:** the ability to make a **signed, authenticated request to
`api.dev.balancee.app/api/pump/*`**. Any of these does it:

- **a dev activation code**, if such a thing exists; or
- **a pre-issued dev credential set** — `apiKey`, `signingSecret`, `pumpId`, and whether it is bound to
  a particular `deviceId` (we send one on every request and the server echoes it at activation); or
- **the documented way dev skips authentication**, if that is what "does not require a code" means —
  which endpoints, and what should we send instead of the four headers.

Any one of the three is equally good. We do not need it to be a code.

**One observation, in case it changes the answer.** We probed dev on 2026-09-12 with no credentials at
all, and dev does appear to enforce authentication on the pump REST routes:

- `GET /api/pump/config` with no headers → `401 {"status":false,"message":"Missing pump authentication headers"}`
- the same request with filler values in the four headers → `401 {"status":false,"message":"Invalid API key"}`

So "dev does not require an activation code" probably means *credentials are handed over directly
rather than redeemed*, or *activation is a formality on dev* — or it was about the dashboard's GraphQL
API rather than the pump REST API, which is a different surface. We would rather ask which than assume
and build the wrong path. Captures: `docs/api-probes/2026-09-12/`.

**Why dev and not the production code we already hold.** Four separate reasons, in descending order:

1. **The run is mostly deliberate failures.** It is not one call. It is activation, `/config`, an
   `/authorise` happy path, a **deliberate amount mismatch**, a **decimal amount**, a status poll and a
   **`/transactions/upload` of a fabricated record** — we need to see how the server answers when we
   get it wrong, because that is the half of the contract the Reference does not print. On production
   those land in the station's real transaction history.
2. **It gets run more than once.** The first pass exists to find where our fixtures disagree with the
   server; we fix and re-run. Repeating that on production multiplies the junk.
3. **Our build cannot reach production anyway, and the build we would make for it is the wrong one.**
   Only the release build points at `api.balancee.app`, and no signed release build exists. A
   debuggable build pointed at production would be one that **seeds its own placeholder price and
   station config** on first run and carries a **hidden debug panel with live price editing and
   payment force-resolve**. Pointing that at the live system is a worse idea than the test
   transactions.
4. **A production pump is a real asset.** It has an identity on your side, and its credentials would
   then live on a bench tablet indefinitely. Nobody chose that.

**If the answer is that dev cannot authenticate us at all** — fine, and here is the ladder we would
take, in order, so you can just pick one:

- **(a)** A **production pump explicitly marked as a test pump**, which you delete afterwards. We run
  the whole sequence on it and you bin it. Tell us the marking convention and we will follow it.
- **(b)** A **read-only subset on production**: activate, `GET /config`, poll `/transactions/{id}`.
  Three calls; only the activation writes anything, and what it writes is one pump we would ask you to
  delete. This settles the `/config` payload shape, GET signing and the clock-skew window, and leaves
  every transaction-creating step unrun.
- **(c)** **Nothing, and we keep building against our own assumptions.** Worth being plain about the
  cost: that is exactly how the response-envelope defect got in — our fixtures were written from a
  summary rather than from real bytes, and a fully green test suite agreed with them for two months.
  We would rather not repeat it, but it is a survivable answer if the others are impossible.

**One thing on our side, whichever way this goes.** The app can only obtain credentials by redeeming a
code — `PumpActivationRepositoryImpl` is the single writer of the credential store. If dev issues a key
pair directly, we need a small debug-only path to load one (TODO #41). That is our work, not yours; it
is listed here only so the answer "here is a key and secret" does not look like it bounced.

---

#### Ready to send on its own

_The above is the full item for the bundled message. If this is going out as a single short message
today, this is the whole of it:_

> We finally have an activation code, but it came from the operator dashboard, which talks to
> `api.balancee.app` — so it is a production pump. Before spending it I want to check the dev path,
> because the end-to-end run we need to do is mostly deliberate failures: an amount mismatch, a decimal
> amount, and an upload of a fabricated transaction, so we can see how the API answers when we get it
> wrong. On production those become real rows in the station's records, and we would need to repeat the
> run each time we fix something.
>
> You mentioned dev does not require an activation code. What is the way to make an authenticated pump
> request against `api.dev.balancee.app` — is there a key and signing secret you can issue directly, or
> a documented way dev skips auth? Asking because when we probed dev with no credentials it did answer
> `401 Missing pump authentication headers`, and with filler values `401 Invalid API key`, so I want to
> make sure I am asking for the right thing rather than guessing.
>
> If dev cannot authenticate us at all, the alternative that works for us is a production pump marked
> as a test pump that you delete afterwards — or, at minimum, your okay to run just the three read-only
> calls (activate, `/config`, status poll) on the code we have.

### 5. Two confirmations, no action needed if we've got it right.

- **The Reference is the canonical contract.** Everything above assumes it's final. If parts are still
  moving, tell me which — we've now been burned once building against a summary instead of the document.
- **Offline USSD stays deferred**, and **push is FCM** with Play Services on the production tablets
  (as agreed on 2026-08-04). V1 ships online-only. Just confirming both still hold.

---

**On urgency:** none of this is a rush toward live money — customer payments stay gated behind the
14-day parallel-run accuracy check regardless. It's about building and sandbox-testing against the
real contract instead of against our assumptions, which is exactly the mistake that cost us rework
this month. **What sets the date is items 1 and 2, since those are yours to build, not ours.**

Happy to jump on a call if any of it is easier discussed than written.

Thanks,
[You]

---

## Notes for us — do not send

- **Lead time is the reason to send now.** Items 1 and 2 are new backend endpoints. Their build time
  is the critical path on TODO #8 (payment feature flows); every day unsent is a day added.
- **Item 1's interim plan SHIPPED 2026-09-02** — Phase 7b (first half), merged to `main` (`0cfba90`):
  device-local operator config behind the existing `DeviceConfigRepository` seam. It doubles as the
  backend-unreachable fallback, so it survives even once `/config` ships. **This is why item 1 is no
  longer worded as a hard blocker** (it was, until 7b landed): the ask now rests on price cadence,
  fleet scale and OQ #19's shared-PIN pricing surface, not on impossibility. Do not let it drift back
  to "we can't sell" — that is now checkably false and would cost us item 2's credibility.
- **The stable-error-codes ask (item 3) is what unblocks TODO #14** on the API side. #14 is *also*
  blocked on attendant-facing error copy, which is ours — no error screen exists in
  `docs/Strict design screens/` and OQ #17 is open. Getting codes back doesn't finish #14 by itself.
- **Pin the answers as tests.** When `/config` and the status set come back, build the fixtures from
  whatever literal JSON they send, not from our restatement of it — that is exactly how #11 (the
  response-envelope defect) got in. And per 7a's framing-doc precedent, treat any illustrative example
  as suspect until verified: that doc's worked checksum was simply wrong.
