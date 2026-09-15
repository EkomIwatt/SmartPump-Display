# Error copy — draft for approval (OQ #17)

_Drafted 2026-09-12 so OQ #17 could be settled by reviewing concrete words rather than by answering
an abstract question._

**OQ #17 is settled — all five items, 2026-09-12.** The principle and the "see attendant" fallback
were approved; the diagnostic half goes to the **swipe-up attendant panel**; a retryable failure now
looks different from a terminal one; and the local messages that contradicted any of it are fixed.
Built across `c2c62f9` and `16d4495`. **Catalogue B is live. Catalogue A is not — see below.**

**Catalogue A is not wired and cannot be yet**: no code path receives an `ApiError` and sets
`TransactionState.Error`, because the payment feature flows (**TODO #8**) do not exist. The mapping
is the unit #8 will consume — building it before there is a call site would repeat the mistake this
file already flags, namely carrying data nothing reads.

Blocks: **TODO #14**'s mapping half (parsing landed 2026-09-12) and the attendant-facing half of
**TODO #15**. Both are otherwise ready.

---

## ⚠️ Design-authority flag

`CLAUDE.md` makes `docs/Strict design screens/*.png` authoritative for layout, copy and state
labels, and requires a deviation to be **flagged before it is built**. **There is no error screen in
that set.** So every word below is invention, and the layout it lands in was invented too — the
existing `ErrorScreen` in `CustomerStateHost.kt` was written without a spec to match.

This is the flag. Nothing gets wired in until someone says yes.

## What exists today

`ErrorScreen` renders a `BalanceeCard` with a red border, the label "Error", the message, and one
button reading "Back to idle". It is driven by `TransactionState.Error(message, recoverable)`, and
`recoverable` is **currently never read** — the screen looks the same either way.

Seven call sites produce messages, and they are not consistent:

| where | current text | aimed at |
|---|---|---|
| Guard, ×3 call sites | "Fuel parameters not set — please see attendant." | customer |
| `onCashFixedAuthorise`, price ≤ 0 | "Price not set — contact operator." | ambiguous |
| `onCashFixedAuthorise`, cutoff ≤ 0 | "Amount is below the minimum dispense (₦X)." | attendant |
| USSD failure | "USSD payment failed — <reason>." | customer |
| Payment failure | "Payment failed — <reason>." | customer |

**Two wordings for one condition.** The guard and the cash-fixed branch both mean "this pump has no
usable price", and they say it differently — one to the customer, one in operator language on the
customer-facing display. Worth fixing regardless of what else is decided here.

## The principle this draft follows

Taken from the precedent already set by `CanStartTransactionUseCase`, which shows the customer a
single `CUSTOMER_MESSAGE` while handing the operator screen a structured `missing` set:

> **The pump screen is a customer-facing display.** A customer can act on "your payment did not go
> through" and cannot act on "request timestamp is not fresh". So the card carries one plain line
> plus, where relevant, "see attendant" — and the diagnostic detail goes where attendants actually
> look, behind the PIN.

Everything below is split on that basis. If the principle is wrong, say so first, because it decides
every row.

---

## Catalogue A — server errors (feeds TODO #14)

**Provenance** matters here, because inventing server strings is how this project got burned before.
`observed` = seen on the wire 2026-09-12 (`docs/api-probes/`); `Reference` = quoted from the PDF in
`API_CONFORMANCE_AUDIT.md`. Matching should key on the stable `code` where the server sends one and
fall back to the message otherwise — codes are currently sent on some paths only (TODO #18f).

| server says | provenance | customer sees | attendant sees | recoverable |
|---|---|---|---|---|
| "Amount mismatch for PETROL…" | Reference | "Could not start — please see attendant." | "Price on this pump does not match the station's. Re-check the price, then retry." | yes |
| "PETROL is currently out of stock" | Reference | "This pump is out of fuel." | "Station reports this fuel out of stock. Sales are blocked until stock is updated." | no |
| "Fuel station has an invalid price per unit" | Reference | "Could not start — please see attendant." | "The station's price is not set on the backend. Nothing to fix on this tablet." | no |
| "Fuel type not available at station: PETROL" | Reference | "Could not start — please see attendant." | "This pump is set to a fuel the station does not sell. Check the fuel type in settings." | yes |
| "Payment has not been confirmed…" | Reference | "Payment not confirmed yet." | "The backend has not seen this payment. **Do not dispense.** Wait, or refund and retry." | no |
| "Request timestamp is not fresh" | Reference | "Could not start — please see attendant." | "This tablet's clock is wrong. Turn on automatic date and time in Android settings." | yes |
| "Invalid request timestamp" | Reference | "Could not start — please see attendant." | "The pump sent a malformed timestamp. This is a software fault — report it." | no |
| "Invalid API key" | observed | "Could not start — please see attendant." | "This pump's credentials were rejected. It may need re-activating." | no |
| "Missing pump authentication headers" | observed | "Could not start — please see attendant." | "Software fault — the pump did not authenticate. Report it." | no |
| "pumpId does not match authenticated device" | Reference | "Could not start — please see attendant." | "This tablet is activated against a different pump. Re-activation needed." | no |
| "Device id does not match credential" | Reference | "Could not start — please see attendant." | "Identity mismatch after a reinstall. Re-activation needed." | no |
| anything else, incl. no envelope | — | "Could not start — please see attendant." | the raw server message, verbatim, plus the HTTP status | no |

The last row is the one that must exist. The Reference's list will not stay complete, and an
unrecognised error must degrade to showing the attendant exactly what came back rather than being
swallowed.

## Catalogue B — local errors (no backend involved)

| condition | customer sees | attendant sees | recoverable |
|---|---|---|---|
| Price and/or fuel type unset | "Fuel parameters not set — please see attendant." *(unchanged)* | which field is missing, as today | yes |
| Cash amount below minimum dispense | "Amount is too small — please see attendant." | "Below the smallest dispensable step (₦X for 0.01 L)." | yes |
| Payment failed / USSD failed | "Payment was not completed." | the processor's reason string | yes |
| No connectivity at authorise | "Could not start — please see attendant." | "The pump cannot reach the server. Check the station's internet." | yes |

## What this needs from a reviewer

1. ~~**The principle**~~ — **APPROVED 2026-09-12**, implicitly, by accepting the fallback that only
   exists because of it.
2. ~~**"see attendant" as the customer's universal fallback.**~~ — **APPROVED 2026-09-12.** It
   repeats a lot, and that is deliberate: it is always the true next step. Still worth showing the
   boss, since customer-facing wording is ultimately brand.
3. ~~**Where the attendant detail is shown.**~~ — **DECIDED 2026-09-12: the swipe-up attendant
   panel**, and built (`16d4495`). It is already behind the PIN, it is what an attendant opens when
   a customer waves them over, and the Pump settings button that fixes most of these sits in the
   same chrome row. Rendered as a flat full-width banner, not a card, so the three action cards stay
   the only tappable-looking things.
4. ~~**Whether `recoverable` should change the card.**~~ — **DECIDED 2026-09-12: yes**, and built
   (`16d4495`). Gold for retryable, red for terminal, reusing the colour vocabulary the app already
   has. The button's *action* is unchanged in both cases: there is no retry in the state machine and
   adding one would be behaviour, not copy.
5. ~~**The two-wordings fix**~~ — **DONE 2026-09-12** (`c2c62f9`), along with the below-minimum
   message, since both had live call sites and both contradicted the approved principle. The
   below-minimum figure comes back once item 3 gives it somewhere to live.

## Not in scope here

Anything that changes *state machine* behaviour. This is copy and routing only; no new states, no
new transitions, no change to what blocks a sale.
