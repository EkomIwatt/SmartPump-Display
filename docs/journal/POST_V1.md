# SmartPump Display — Post-V1 backlog

Improvements that **do not block V1**, kept out of [`TODO.md`](TODO.md) so that board stays about the
road to V1 — the 14-day parallel run on a release build, then live money. Created 2026-09-22 at the
user's direction: *"V1 is the priority; non-blocking upgrades are documented separately."*

**How this file works.**

- An entry here is **not dropped**, only sequenced after V1. Each keeps the number and wording it had
  on the board, so old cross-references still resolve; the board keeps a one-line `[→]` pointer where
  each entry used to be.
- **Moving something back** is a one-line decision: if a post-V1 item turns out to block V1, move its
  entry back to `TODO.md` and say why in the move.
- **New non-blocking work goes straight here**, not onto the board. When unsure, ask whether the
  parallel run or live money can start without it — if yes, it belongs here.
- [`V1_BLOCKERS.md`](V1_BLOCKERS.md) is the view of what *does* block V1; nothing in this file should
  appear there.

**Legend:** `[ ]` open · `[~]` partly done · `[·]` deferred by decision

---

## Product decisions, deferred

Questions for the boss whose answer changes behaviour at the pump but not whether V1 can run.

4b. [ ] **#R14 — a genuine drive-off has no exit.** Since #R13 a fill-up past shutoff can only be
   closed by CASH RECEIVED, so a customer who leaves without paying wedges the pump on cash-confirm:
   the attendant's choices are to record cash nobody received, or restart (which resumes the same
   screen). The honest answer is an attendant **"unpaid / drive-off"** action behind the PIN that
   closes the sale and writes an event with the litres and amount — **but the design specifies
   exactly three attendant actions**, so a fourth is a deviation for the boss to approve, not ours to
   make. Ask together with #R11: both are "what does an unattended pump do when a customer walks
   away". (A 0 L fill-up is fine — it closes through CASH RECEIVED as a zero sale.)

- [ ] **#R15 — the 20-minute QR window feels too long** (raised by the user, 2026-09-22). It is not
  the app's number: it is the `expiresAt` Balancee issues with every authorise, and the app honours
  it on purpose — until #43 the app gave up at 5 min while the checkout stayed payable, so a
  customer who scanned at 5:30 was cancelled under them. **Capping it in the app re-creates that**:
  a customer paying after the pump's cap pays for fuel the pump will not dispense (the abandonment
  row makes it answerable, but it is a refund). And the server does not enforce `expiresAt` at all
  (payable 3m16s past it at the 10g gate). **The right fix is Balancee's: a shorter `expiresAt`, and
  enforced** — send with the asks below. Meanwhile a customer can always cancel from the QR, and
  since #R11 an abandoned pre-pay frees the pump 2 min after the window ends. If the boss still
  wants an in-app cap knowing about late payers, it is a one-line change.

## Code improvements

Each is its own small branch off `main` when its time comes. None is a defect today.

- [ ] **Pre-pay "Preparing QR…"** (from the fill-up QR-wait work, `de1e976`, 2026-09-21). Pre-pay
  holds `ModeSelect` for the same `/config` + `/authorise` round trip the fill-up did, with nothing
  on screen to say the tap registered. The fill-up's `preparingQr` flag and the prefetch pattern
  apply; the difference is that pre-pay has no natural moment to prefetch before the tap (no nozzle
  shutoff), so the win there is the feedback, not the speed.

7. [ ] **#R3 — `KEEP` discards an upload request for a sale completing during an in-flight drain.**
   Real, but the record is **delayed, not lost**: every new sale and every app launch re-requests.
   **At minimum fix the comment**, whose reasoning is wrong — it only holds if the row was written
   before that run's `getPendingSync()`. Decide then whether `KEEP` should become `APPEND`.

8. [ ] **#52 — `runCatching` on a coroutine path swallows cancellation.** Opened by #R6, which added
   `domain/util/runCatchingCancellable` and converted the three sites where an absorbed cancellation
   changes control flow on a money path. **~20 remain** across `CustomerViewModel` (pulse
   persistence, the receipt read, the state-writer loop), `OnboardingViewModel`, `ApiProbeViewModel`
   and `UsbSerialConnection`. **Not a blind sweep** — most are inert (a loop body, a
   read-then-return), and converting an inert one is a no-op while converting a live one changes
   behaviour. Go site by site and ask what runs *after* the guard.

9. [ ] **#50 — three screens read `uiState.priceKoboPerLitre` while holding a struck figure.**
   `FillupDigitalAwaitingPayment`, `FillupAwaitingCashConfirm` and `CashFixedAmountEntry` display a
   ₦/L that lives outside the state whose amount they are showing. `priceMayMoveFreely` is what
   keeps them honest — a guard doing a type's job. **Deliberately last:** it touches
   `CustomerStateHost` *and* the serialized state classes, so it carries persisted-state
   compatibility risk (new fields need defaults, as every other state class does), and **#6 already
   closed the one path that could actually move the figure**. Not a defect today.

- [ ] **NEW — `DeviceConfig.stationName` vs `StationIdentity.displayName`: two station names.**
  Receipts print the first (`ReceiptText.kt:67`); every customer screen shows the second
  (`CustomerStateHost.kt:108,126,143`). `/config` carries a third. 10c-bis declined to reconcile
  them by side effect — a price sync silently changing what receipts say is the wrong way to
  settle it. Small, and wants deciding before the parallel run prints receipts anyone keeps.

- [ ] **NEW (10g) — the receipt's station name is not pinned to the sale.** `transactions` has no
  station-name column, so `ReceiptText` reads the **current** `DeviceConfig`. Now that the backend
  owns that name, a rename changes the name on every past receipt re-shared. Exactly #37's shape —
  which was fixed by storing `priceKoboPerLitre` **on the row** — and it needs the same answer: a
  `stationName` column at **schema v6**. Deliberately not folded into the 10g fixes; v5 has only
  just been proven on a device once, and a second migration deserves its own run at the gate.

- [ ] **39. `docs/api-probes/2026-09-12/probe.sh` is re-runnable** _(was a second #33, renumbered
  2026-09-15 at the merge; nothing referenced it by number)_ and sends no secrets. Re-run it
  after any backend deploy to see whether the 401s have grown a `code` field yet (#18f).

- [ ] **41. Credentials can only arrive by redeeming a code — and dev may not use codes.**
  `PumpActivationRepositoryImpl:54` is the **only** writer of `PumpCredentialsStore` in the app;
  everything else reads. So if dev hands over an `apiKey` + `signingSecret` + `pumpId` directly
  (which is the most likely reading of "dev does not require an activation code" — see **#31**),
  there is nowhere to put them and the whole dev path is unreachable.
  - **Shape:** a debug-only load path, sensibly part of the probe panel (**#32**) rather than a
    second screen. It must go through `PumpCredentialsStore.save()` and then **read back**, for the
    same reason `persist()` does — a Keystore blob that cannot be decrypted is discarded silently,
    so a write that returns is not a write that worked.
  - **Guards to keep:** `BuildConfig.DEBUG` only; never log the secret (the `/activate` allowlist in
    `PumpLoggingInterceptor` and the redacting `toString()` on `PumpCredentials` both stay, #12); and
    it must not become a way to hand-edit credentials on a live pump.
  - **Do not build speculatively.** It is cheap, but which of the three forms dev answers with
    decides whether it is needed at all.

## Balancee and boss asks — improvements, send together

None of these gates V1: the payment lifecycle was proven against production by observation (#32,
10g). They make the backend better, not the pump possible. The orphan ₦149 sale is **not** here — it
is real money unreconciled and stays on the board as an action.

11. [ ] **Ask Balancee to move the pump API off `iad1`** (added 2026-09-21): every call from Lagos
    enters Vercel at Cape Town (`cpt1`) and executes in Washington. A region nearer Nigeria roughly
    halves every call — authorise, polls and uploads alike — and is a setting on their side, not
    code. Send with the asks below.

11b. [ ] **Two backend asks are drafted and unsent:** does a transaction ever leave
    `PENDING_PAYMENT`, and is the checkout URL still payable after `expiresAt`? Plus the user's
    question — **round the litres, not the money** (₦200 → ₦199.66); cash already behaves that way,
    so this is digital diverging from cash. #R8's wording depends on the second answer, so sending
    these early is worth more than it looks.

- [ ] **NEW (10g) — backend ask: does a transaction ever leave `PENDING_PAYMENT`?** Observed on
  production 2026-09-19: **3m16s** after a transaction's own `expiresAt`, `GET /transactions/{id}`
  still answered `200` / `PENDING_PAYMENT` with the same live Paystack `authorizationUrl`
  (`docs/api-probes/2026-09-19-10g/`). So `expiresAt` is reported, not enforced, and a customer can
  pay after the pump has stopped watching — money out, no fuel, no local record. Two questions, and
  the client cannot answer either by guessing:
  1. Does the record ever transition on its own, and to what?
  2. Is the checkout URL still payable after `expiresAt`?
  Until then the polling policy is **unchanged on purpose** and `PAYMENT_ABANDONED` records the
  transaction id so an orphaned payment is at least answerable. Goes with **#18**.

- [ ] **NEW — backend ask (goes with #18): honour the price a fill-up was struck at.**
  The server checks `amount == expectedLitres × pricePerUnit` against **its own** price, so a
  fill-up that ends seconds before a price change cannot be charged at the figure the customer
  watched climb. 10c-bis narrowed the window to seconds and logs each occurrence; closing it needs
  the server to accept a struck price (or a struck-at timestamp) on `/authorise`. **Do not wait on
  it** — an improvement, not a gate.

- [ ] **NEW (10g, raised by the user 2026-09-19) — round the litres, not the money.** A customer
  who types **₦200** is quoted **₦199.66** for 0.134 L, and the round figure is the one that gives
  way. Asked for the opposite: hold the naira, approximate the litres.
  - **Cash sales already behave the way he wants** — `onCashFixedAuthorise` keeps `cashAmountKobo`
    exactly as typed and floors `litresCutoff` to 2 dp (pinned by `CustomerViewModelMoneyTest`).
    So this is not a change to cash; it is **digital diverging from cash**, which is the better
    argument for doing something about it.
  - **What forces it:** the server checks `amount == expectedLitres × pricePerUnit` exactly, so the
    amount has to land on a payable litre step (`SaleQuote.litreStepMicrosFor`). At ₦1,490/L,
    ₦200 buys 0.134228… L, which is not expressible — so either the litres carry more precision
    than the server accepts, or the money moves. Today the money moves.
  - **So it is probably a backend question, not a client one:** how many decimal places will
    `expectedLitres` accept, and will the check tolerate a rounding delta? #18f asked a neighbouring
    question at the #32 gate. Goes with the `expiresAt` ask.
  - Not a defect — the quote floors, so the customer is never charged more than they tendered.
    Discuss after the 10g sitting.

- [ ] **29. The `events` table has no backend home.** Nothing on the server accepts these rows.
  **A fifth ask for #18**, currently not on that list. The upload job (7e) can carry them once an
  endpoint exists.

- [ ] **48. A dispense can be recorded once and never corrected — and the app is told otherwise.**
  Corrected 2026-09-17 from "last-write-wins", which was read out of a 200 and was wrong in the more
  dangerous direction.
  - **Observed:** a second upload for an already-`DISPENSED` transaction, carrying 0.2 L instead of
    0.1, returned `200 Transaction recorded` — and the dashboard still shows **0.1**. First write
    wins; the repeat is acknowledged and discarded.
  - **The retry is safe.** `retryingApiCall` repeats an identical upload, which is now demonstrably
    harmless. That question is closed.
  - **The hazard is correction, not duplication.** If a dispense is ever uploaded with the wrong
    litres — a bug, a bad K-factor, a figure sent before 7h's reconciliation finished — re-uploading
    the right one **succeeds loudly and changes nothing**. The station's record stays wrong while
    every log in the app says "recorded".
  - **Why it was invisible:** the reply does not echo `actualLitresDispensed` (#46), so a 200 is the
    only signal the app gets, and it means "accepted", not "stored". Nothing in the API can read the
    figure back; only the dashboard shows it.
  - ~~**Ours (7e):** upload once per transaction and never re-send a superseded figure~~ ✅ **DONE
    2026-09-19 (10f).** `syncedAt` is written only on a 200, `getPendingSync` excludes anything
    carrying it, and nothing in the app re-sends. **Theirs — a fifth item for #18, still open:**
    either accept a correction, or refuse the repeat with a code instead of a 200 that reads as
    success.

- [~] **18. Backend/spec asks — MOSTLY ANSWERED BY OBSERVATION; what is left does not block V1.**
  **Updated 2026-09-17 after the gate.** (a)–(f) below were written against the 2026-09-12 dev probe,
  when the shapes were still unverified. The gate verified all of them on production against real
  credentials — see `docs/api-probes/2026-09-16-prod-config/` and `…-prod-gate/`:
  - **(a) `/config` — shape now KNOWN and wholly unlike what was modelled.** One pump, one fuel, one
    price. Rebuilt from bytes in `1c3dc26`. This retired `BOSS_CONFIRMATIONS_DRAFT.md` item 1, the
    ask marked *highest*, before it was sent.
  - **(b) `/transactions/{id}` — shape KNOWN**, and it is the same object `/authorise` returns (#46).
  - **(c) decimals — ANSWERED: accepted**, and the exact `amount == litres × price` check passes on
    one. Forces **#44**.
  - **(d) status set — ANSWERED:** `PENDING_PAYMENT` → `PAID` → `DISPENSED`.
  - **(e) GET signing — ANSWERED** by a 200: `timestamp + "." + ""` is what the server verifies.
  - **(f) stable error codes — ANSWERED, and the half-built reading was the right one.** They exist
    on business failures (`AMOUNT_MISMATCH`, `PAYMENT_NOT_CONFIRMED`, `TRANSACTION_NOT_FOUND`,
    `INVALID_REQUEST`) and on **no** authentication failure. That is a rule, not an inconsistency:
    match business errors on `code`, identify the auth family by 401.
  - **Still genuinely open, and none of it gates V1:** **#29** (no backend home for the `events`
    table), **#48**'s other half (accept a correction, or refuse the repeat with a code instead of a
    200 that reads as success), and **#46** (echo `actualLitresDispensed` so the app can read its own
    record back). Drafted in `BOSS_CONFIRMATIONS_DRAFT.md`.

  _Original 2026-09-12 dev-probe findings, kept because they are how the routes were found at all:_
  Probing
  `api.dev.balancee.app` answered more than the reply did. Evidence: `docs/api-probes/2026-09-12/`.
  - ~~(a) `GET /api/pump/config` doesn't exist~~ — **DEPLOYED.** 401 `Missing pump authentication
    headers` with `X-Matched-Path: /api/pump/config`; an undeployed route returns an HTML 404 with
    `X-Matched-Path: /404`, so this is a real handler. **Its payload shape is still unverified** —
    that needs credentials, and 7b's second half rides on it.
  - ~~(b) `GET /api/pump/transactions/{id}` doesn't exist~~ — **DEPLOYED**, as a parameterised route
    (`X-Matched-Path: /api/pump/transactions/[id]`). Response shape likewise unverified.
  - (c) **does `amount` accept decimals?** Unanswered; unreachable without credentials. Still a
    pricing decision, not just a technical one.
  - (d) **full status set** — unanswered; needs a real transaction to observe.
  - (e) **what to sign for a GET** — unanswered **and untestable from outside**: the server
    validates the API key *before* the timestamp and signature, so a stale `X-Timestamp` and a
    removed `X-Signature` both return `Invalid API key`. Now behind activation, along with (c), (d)
    and the clock-skew window (#15).
  - (f) **stable error codes — HALF BUILT.** The 400 from `/activate` returns
    `"code":"INVALID_REQUEST"` beside `message`; **none of the three observed 401s carry one.** Go
    back with that specific gap rather than re-asking the general question.
  - **Also confirmed for free:** our four signing header names (`X-Api-Key` / `X-Device-Id` /
    `X-Timestamp` / `X-Signature`) are correct — sending them moves the server off "missing headers"
    onto "Invalid API key". That was previously only our reading of Reference §3.

- [~] **6. Chase the 7 boss confirmations** (from `phase7_blocker_resolution.md`): (1) reference is
  canonical — gates everything; (2) ~~tablet has Google Play Services? → FCM vs WebSocket~~ →
  **ANSWERED 2026-08-04: FCM.** Tablet will have Play Services; we're *advising* for it (better than
  a persistent WebSocket on a kiosk device) and the manager is expected to provide it. Bench SM-T220
  already satisfies it. Ask becomes a ratification, not an open question — see OQ #8; (3) GET
  `/transactions/{id}` exists; (4) GET `/config` exists + final payload/units (incl. money unit on
  `amount` — naira vs kobo); (5) confirm offline-USSD 7d deferral; (6) late-payment policy; (7) hosted
  staging URL + test activation code. **Draft ready → `BOSS_CONFIRMATIONS_DRAFT.md`.**
  - **NO LONGER BLOCKS #8 — updated 2026-09-17.** The gate answered (3), (4) and the money unit by
    observation, and item 4 of the draft (live Paystack?) by paying ₦149 through it. (1) is moot now
    that behaviour has been observed directly: **the wire outranks the Reference**, and where they
    disagreed the wire was right. What is still worth sending is (5), (6) and the three remaining
    backend asks under **#18** — all improvements, none of them gates.

## Features deferred by decision

- [·] **9. Offline USSD (Flow 5 / sub-phase 7d).** Boss-deferred to a future update. The genuinely
  *offline* path (bank USSD + parsed SMS), distinct from Paystack's *online* USSD. Kept in
  `flows.md`/`state-machine.md`. Revisit with OQ #9–#12 (real bank SMS samples, SIM provisioning,
  ref-collision scheme, per-station code generation).
