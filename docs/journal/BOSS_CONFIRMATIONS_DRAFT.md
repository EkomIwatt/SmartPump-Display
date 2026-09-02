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
  both wired into the build. The **test activation code** is still outstanding and stays in the ask.
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
issues, and have **fixed the four that were ours** — verified on the tablet, done last week.

The rest we can't fix in the app, because the information isn't there to fix it with. The main one:
**the pump has no way to find out which fuel it's selling or what price to charge.** Ringing up a sale
requires the fuel type, but nothing in the API ever tells the pump what it is. As things stand, the
payment flow can't be completed. There's a second, smaller gap — if a payment confirmation doesn't
reach the pump, it has no way to check whether the customer actually paid, so someone who has paid
could be left standing at a pump that won't dispense.

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
contract itself, and the first one means **the payment flow cannot be completed as it currently
stands**. Ordered by how much each blocks us.

---

### 1. The pump has no way to learn which fuel it dispenses. _(hard blocker)_

This is the one that stops everything.

`POST /authorise` **requires** a `fuelType`. But `POST /activate` returns only `deviceId`, `pumpId`,
`apiKey` and `signingSecret` — nothing about the station or the pump's assignment. The Reference
documents exactly three endpoints (its own §5 cheat sheet confirms this), so **there is no endpoint
that tells the pump what it's selling.** As the contract stands we would have to hardcode the fuel
type into each tablet's build, which is not something you want to be doing per-pump in the field.

The same gap covers the price. We had assumed a `GET /api/pump/config`; that was **our proposal, not
your endpoint** — my mistake for carrying it as though it existed.

**The ask: add `GET /api/pump/config`.** Deliberately minimal — small asks get built:

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
The pump needs to fetch price on boot and before every sale, because your own server rejects any sale
where `amount ≠ expectedLitres × pricePerUnit`. Without a fetch, our sales start failing the first
time a price moves.

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

### 4. Still needed to test end-to-end: a test activation code.

We have both URLs (`api.balancee.app`, `api.dev.balancee.app`) wired in. We need **one redeemable
activation code against dev** to exercise the activation flow. Activation codes are single-use, so we
can't proceed on a borrowed one.

Worth flagging: **activation is the one irreversible step.** It issues the pump's credentials once,
and it fixes the pump's permanent identity. We've built it so a mistake there is recoverable, but it's
the reason we haven't run it against anything real yet.

---

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
- **Item 1 has an interim plan that isn't throwaway:** build the device-local operator config screen
  as the first half of 7b, behind the existing `DeviceConfigRepository` seam. It doubles as the
  backend-unreachable fallback, so it survives even once `/config` ships.
- **The stable-error-codes ask (item 3) is what unblocks TODO #14** on the API side. #14 is *also*
  blocked on attendant-facing error copy, which is ours — no error screen exists in
  `docs/Strict design screens/` and OQ #17 is open. Getting codes back doesn't finish #14 by itself.
- **Pin the answers as tests.** When `/config` and the status set come back, build the fixtures from
  whatever literal JSON they send, not from our restatement of it — that is exactly how #11 (the
  response-envelope defect) got in. And per 7a's framing-doc precedent, treat any illustrative example
  as suspect until verified: that doc's worked checksum was simply wrong.
