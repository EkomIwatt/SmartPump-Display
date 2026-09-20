# SmartPump Display — Project Log

## Current status — 2026-09-20, late (**the scoped review is done; its one finding is fixed, and the tablet is the last thing between this branch and `main`**)

**Merge-gate step 2 is complete.** The review scoped to this round's six fixes found the six sound —
each guard was checked for what runs after it fires — and found one blocking defect in their
**sibling**: #R9, fixed in `802c1dc`. Branch `feature/phase-10-payments` is **52 commits**, **505
tests / 48 classes** green, `lintDebug`, `assembleDebugProd` and `compileDebugRealHwKotlin` clean.
**Still NOT merged.**

**#R9: the pre-pay expiry cancelled its own coroutine.** `startExpiryCountdown` runs inside
`expiryJob` and called `cancelInFlightJobs()`, which cancels `expiryJob`. The next line is a
suspending Room write, so the coroutine died there — **neither the `PAYMENT_ABANDONED` row nor the
return to Idle ever happened on a real device**. Every abandoned pre-pay left the pump on a dead QR
at zero seconds, polling stopped, with a checkout URL the backend still honours and nothing written
down to answer the customer who pays it. That row exists for exactly one scenario and this was it.

**It was introduced by a fix — the third on this branch.** `8e0a15c`, the 10g fix that added the
audit write, inserted a suspension point between a self-cancel and a transition. The fill-up twin
never had it, because it cancels the payment job and leaves its own alone. Same lesson as #R4 and
#R5 one more time: **a defect fixed in one flow, left standing in its sibling.**

**The harness was the other half, and is the more useful half.** `FakeEventRepository` returned
without ever suspending, so nothing in 505 tests could observe a cancellation arriving at an audit
write — the exact class of defect #R5 was about. One `yield()` makes three pre-pay tests fail
against the pre-fix file, **including #R5's own**. A fake that cannot suspend cannot test a
coroutine.

**What is left is step 1: the tablet.** Nothing below it is a defect — #R3 and #R8 are boarded
judgment calls, #50/#51/#52 improvements. See `TODO.md`.

---

## Previous status — 2026-09-20, end of day (**all six blocking review findings are closed; the branch is ready for its merge gate**)

**Phase 10 is done, reviewed twice, and every blocking finding is fixed.** `feature/phase-10-payments`
is **50 commits**, pushed (`5b2731b`), **505 JVM tests / 48 classes** green, `lintDebug`,
`assembleDebugProd` and `compileDebugRealHwKotlin` clean. **Still NOT merged.**

**Round 3 closed #R4, #R5 and #R6** — one wrong id, and two unguarded Room writes that escaped into
`viewModelScope`. The worst of them meant a pump whose database had gone bad **could not open the
app at all**, which on a forecourt also stops it taking cash.

**The honest note about this branch, which the next session should carry:** the defects these
reviews keep finding are in code written for this branch, by this assistant, days or hours earlier.
Two of them were introduced *by earlier fixes* — `202144e` widened the surface #R6 then flagged, and
#R5's own guard used `runCatching`, which swallows the `CancellationException` that unwinds a
cancelled job, so a payment succeeding mid-write could have let the following `setState(Idle)` wipe
a sale just paid for. That second one existed for about six hours and never left the branch. **The
rate that matters is not findings per review; it is defects introduced per fix, and it is not
zero.**

**What is left is not a defect.** #R3 and #R8 are boarded judgment calls; #50, #51 and #52 are
improvements. The next thing is the **merge gate** — see `TODO.md`, which now carries the plan
rather than a list.

---

## Previous status — 2026-09-20 (**the 10g gate passed on real money, and then a review found five more**)

**Phase 10 is built, gated and reviewed, and it has not merged.** The tablet sitting is done: three
real paid sales against `SN-TEST-001` on production, **₦548.32** of the user's own money, seven of
eight runbook steps passed and the eighth answered in the negative. Then a high-effort review of the
branch found **eight** further findings, all verified, five of them blocking — and three of those
were in code written the same evening.

**The gate earned its keep twice over.** Sitting 1 was stopped before payment: a customer typing
**₦200** was about to be charged **₦2,007.03**, because the keypad committed every digit and a
*deletion* did not, and the validity gate meant to catch that never fires when one valid number is
backspaced into another. It had shipped since May. Alongside it, the app was quoting the ₦870 debug
seed while the server charged ₦1,490 — the sync wrote the database and never reached the screen,
because its guard asked for `Idle` and the tablet had restored to `ModeSelect`.

**Sitting 2 passed. Sitting 3 failed, which is why Flow 3 was worth running at all.** The fill-up
recorded the local `BLC-…` reference minted at attendant-authorise instead of the id `/authorise`
issued, so `POST /transactions/upload` was refused as terminal: **fuel sold, money taken, and a paid
transaction on production with no dispense against it** — the single outcome 10f exists to prevent.

**The backend does not expire a transaction.** Three minutes and sixteen seconds past its own
`expiresAt`, `GET /transactions/{id}` still answered 200 / `PENDING_PAYMENT` with a live Paystack
URL. `expiresAt` is reported, not enforced. Since the app stops polling on its own clock, a customer
can pay after the pump has stopped watching. `PAYMENT_ABANDONED` now records the id so that is at
least answerable; the polling policy is deliberately unchanged until the backend answers.

**The same wrong sentence was written three times.** Two copy rows told an attendant *"nothing was
charged"* — one on expiry, one on `TRANSACTION_NOT_FOUND`, the latter firing on a sale the customer
had just paid ₦149 for. Both now say what is actually known, which is that we do not know.

**The review's lesson is narrower and sharper: a defect fixed in one flow was left standing in its
siblings.** The local-id bug was right in Flow 1, wrong in Flow 3, wrong in USSD. The stale-price
bug had four readers and the first fix closed one. Neither was caught by the tests written for those
fixes.

**USSD came off the customer's screen** (user's decision, 2026-09-20). Flow 5 is deferred, not cut —
but the tile was live, and tapping it called the real processor, created a genuine Paystack
transaction, and waited for an SMS on a SIM that is not provisioned. Handled exactly as NFC was.

**Branch state:** `feature/phase-10-payments`, **47 commits**, working tree clean, JVM
**491 tests / 48 classes** green, `lintDebug`, `assembleDebugProd` and `compileDebugRealHwKotlin`
clean. (Run `lintDebug` and `assembleDebugProd` in **separate** invocations — together they race on
generated Hilt sources.) **Not merged.** Review #1's eight findings are all closed (#7 `202144e`,
#5 `c015bcf`, #6 `28d8c03`). **A re-review of the whole branch then found eight more**, and its
first was the worst defect this branch has produced: a code-less 401 — the observed clock-skew
failure — was classified TERMINAL, so a tablet whose clock drifted overnight wrote `uploadError`
on every queued dispense, which `getPendingSync` filters and nothing clears. **Fuel sold, money
taken, and the station's record of it destroyed permanently by putting the clock right too late.**
Fixed in the shared taxonomy (`e26246e`), which fixed the poll's half of it for free. **Five of
the re-review's findings remain open** — see TODO #R4/#R5/#R6 and the two boarded judgment calls.

---

## Previous status — 2026-09-19, latest (**10f is done** — a dispense finally reaches the backend, and the record survives a power cut)

**The upload job exists, and the record it uploads exists for the first time.** 10f opened by
finding that `PaymentResult.Success` has carried the server's `paymentReference` since 10a and
**every call site dropped it** — `/transactions/upload` requires one and only `/authorise` issues
one, so every digital dispense this app had ever completed was unreportable and nothing said so.
Schema v5 keeps it, along with when fuel started flowing and why a record was refused.

**`TransactionUploader` holds every decision and has no Android in it**; the worker answers
WorkManager's one question and has no `Result.failure()` branch, because the only thing allowed to
abandon a dispense is the uploader — by marking the row, with a reason, in the log a person reads.

**`RETRY_LATER` is doing the job 10e built it for.** `PAYMENT_NOT_CONFIRMED` waits instead of
discarding the record of fuel a customer has already taken. `NotActivated` departs from the shared
taxonomy on purpose: credentials are a device state, not a verdict on a sale.

**#48 is closed on our side** — `syncedAt` is written only on a 200 and nothing re-sends after it.
The backend half of it stays open with #18.

**Branch state:** `feature/phase-10-payments`, working tree clean, JVM **432 tests / 45 classes**
green, `compileDebugRealHwKotlin`, `lintDebug` and `assembleDebugProd` clean. (Run `lintDebug` and
`assembleDebugProd` in **separate** invocations — together they race on generated Hilt sources and
lint dies with an internal error that is not a code defect.) **Next is 10g, the tablet gate against
production** — now the only thing between phase 10 and a merge. Four new migration tests and the
worker's real scheduling have not run on a device yet and go with it.

---

## Previous status — 2026-09-19, later (**10e is done**; a failure now says two different things to two different people)

**Both halves of 10e have landed, and #14 and #15's mapping half close with them.** A failure no
longer reaches the screen as one invented sentence plus the server's raw prose. `FailureCopy` carries
the customer's plain line, the attendant's diagnostic line and the recoverable flag out of the data
layer together, and `ApiError.toFailureCopy()` decides all three — keyed on the server's `code`, and
on the 401 where there is none.

**Never on a string nobody has seen.** The Reference quotes `"Amount mismatch for PETROL…"`;
production returns something else entirely for that same `AMOUNT_MISMATCH`. The one Reference string
comparable against the wire had already been reworded, so three Catalogue A rows are **parked**
pending one observation of their code, and everything unrecognised takes the catalogue's last row —
the server's own words, verbatim, plus the status and the code.

**The one exception found on the way: the clock-skew 401.** It shares a codeless 401 with a rejected
API key, so the drafted credentials line would have told an attendant to re-activate a pump whose
clock was simply wrong. It is matched on its message, which was observed twice on production at the
#32 gate — and a rewording degrades safely to the credentials line.

**Branch state:** `feature/phase-10-payments`, working tree clean, JVM **409 tests / 43 classes**
green, `compileDebugRealHwKotlin`, `lintDebug` and `assembleDebugProd` clean. (Run `lintDebug` and
`assembleDebugProd` in **separate** invocations — together they race on generated Hilt sources and
lint dies with an internal error that is not a code defect.) **Next is 10f, the upload job.**

---

## Previous status — 2026-09-19, later still (10e's taxonomy half; **the branch is finally pushed**)

**`feature/phase-10-payments` is on `origin` for the first time.** It had never been pushed at all —
no remote branch, twenty commits of 10a–10d existing only on one laptop. That was a bigger exposure
than anything on the board, and it is closed.

**#45 is done** (`6162027`). `RetryPolicy` is `RETRY_NOW` / `RETRY_LATER` / `TERMINAL`, keyed on the
server's `code`. `PAYMENT_NOT_CONFIRMED` is the one refusal that becomes a success on its own, and
an upload job reading it as final would drop the record permanently — the single outcome 10f exists
to prevent. `RETRY_LATER` is **not** retried in-flight, because a second and a half of backoff will
not outlast a payment confirming. It also retired the duplication 10d created an hour earlier:
`isPollTerminal` had its own private code constant and now defers to the shared taxonomy for the
question the two genuinely share.

**10e's copy half is deliberately not started.** Per the authority order the strict design screens
govern copy, so wiring `ERROR_COPY_DRAFT.md` Catalogue A is a reading-and-wording pass that wants
fresh eyes rather than the tail of a long build session. **That is where the next session opens.**

**Branch state:** `feature/phase-10-payments`, **21 commits, pushed**, working tree clean, JVM
**388 tests / 42 classes** green, `compileDebugRealHwKotlin`, `lintDebug` and `assembleDebugProd`
clean. (Run `lintDebug` and `assembleDebugProd` in **separate** invocations — together they race on
generated Hilt sources and lint dies with an internal error that is not a code defect.)

---

## Previous status — 2026-09-19, later (10d: a digital sale can now complete, and a restart cannot sell twice)

**A digital sale works end to end in code for the first time.** The QR goes up, the poll watches
`GET /transactions/{id}` on a 10 s cadence, and `PAID` starts the fuel. `BalanceePaymentProcessor`
is **bound** — per build type, not unconditionally: `MOCK_PAYMENTS` mirrors `MOCK_HARDWARE`, so
`debug` and `debugRealHw` keep the simulator and only `debugProd` and `release` charge real cards.

**The boot-resume trap is closed on both digital flows**, not just the pre-pay one the board named.
`resume(ref, request, deadline)` is a separate method from `process`, so the wrong call is
impossible rather than discouraged, and the deadline is restored rather than re-granted.

**Three money defects surfaced on the way and are fixed.** Flow 3's QR still could not be paid (10c
fixed pre-pay only, and the state class had been documented as carrying a checkout URL it never
did). The pre-pay screen printed the round tender beside a checkout page charging the quote. And
the pump was stopping 5 ml short of what was paid for on every pre-pay sale, because the quote's
payable litre step and `DeviceConfig.litresCutoff` disagree.

**Branch state:** `feature/phase-10-payments`, **19 commits, local only, working tree clean**, JVM
**378 tests / 41 classes** green, `compileDebugRealHwKotlin` + `lintDebug` + `assembleDebugProd`
clean. Not pushed. **Next is 10e** — error mapping, where #45's *retry later, not now* outcome has
to exist before 10f's upload job can be trusted not to drop a record.

---

## Previous status — 2026-09-19 (10c-bis: the displayed price and the charged price are finally the same number)

**The divergence is closed.** Nothing in this app had ever written the server's price into
`DeviceConfig` — `PumpConfigResponse` had three consumers and none of them stored anything, while
the only writers of `DeviceConfig` were two settings screens and a debug-build seed. The price the
customer read and the price `/authorise` was checked against were unrelated numbers, agreeing only
because someone had typed one to match the other. `PumpConfigSync` now stores what the
fetch-before-authorise already read, and the boot path fetches too. **#18(a) / 7b's second half is
done**, and `BOSS_CONFIRMATIONS_DRAFT.md` item 1 — the ask marked *highest* — is retired in code.

**All three 10c-bis calls went the recommended way:** build it before 10d; the server wins and the
operator's typed price becomes the pre-activation / offline fallback; the residual seconds-wide
fill-up race proceeds at the server's price and is logged rather than refused.

**Two new event types, and the fuel-log card is now the Pump log.** `PRICE_SYNCED` is the operator's
only evidence the screen's price changed with nobody at the pump; `PRICE_CHANGED_MID_SALE` records
what a fill-up customer watched beside what they were charged. Both written only when something
actually moved.

**Two follow-ups were declined on purpose rather than folded in:** the two station names
(`DeviceConfig.stationName` vs `StationIdentity.displayName`, plus a third on `/config`), and the
backend ask that would let a fill-up be charged at the price it was struck at. Both on the board.

**Branch state:** `feature/phase-10-payments`, **14 commits, local only, working tree clean**, JVM
**357 tests / 40 classes** green, `compileDebugRealHwKotlin` + `lintDebug` clean. Not pushed.
**Next is 10d** — PAID detection by poll, carrying the boot-resume trap that would otherwise
authorise a second sale for a customer who has already paid.

---

## Previous status — 2026-09-17 (the gate's docs debt paid; Phase 10 planned and started)

**`main` is pushed** — `origin/main` = `84d6f49`, carrying the whole gate. The docs the gate made
stale are corrected: `V1_BLOCKERS.md` §4 no longer claims the API line is blocked on the backend,
and TODO #8 no longer claims to be blocked by #3/#4/#6. All three were stale — #3 and #4 shipped in
July, and the gate answered #6's blocking items by observation rather than by reply.

**Phase 10 (payment feature flows, #8) is planned and underway** on `feature/phase-10-payments`.
Seven sub-deliverables, 10a–10g, ending in a tablet gate against production. **Poll-only by design,
not by omission:** push has no server side (no device-token endpoint exists) and no client side (no
Firebase in the project), and OQ #8 already rules push a freshness optimisation with the poll
carrying the correctness guarantee.

**10a, 10b and 10c are done** (`70ad358`, `4a87877`, `ea6037b`, `96b5241`, `ef17770`). JVM **341
tests / 39 classes** green; `compileDebugRealHwKotlin` and `lintDebug` clean.

**The QR can be paid now.** It encoded a payload this app invented, so the artefact looked right and
could not take money. That, **#43** (the expiry is the server's, 20 minutes, not a 5-minute constant)
and **#46** (three DTOs were three partial views of one object) are all closed.

**The processor is built but NOT bound in DI** — the terminal result needs the poll, which is 10d.

**Next session starts with a decision, not with code (10c-bis).** Answering "what happens if the price
changes mid-fill-up" found the question rested on a false premise: **nothing ever writes the server's
price into `DeviceConfig`**, so the displayed price and the authorised price are unrelated numbers
and always have been. That is #18(a) / 7b's second half, marked BLOCKED since 2026-09-03 because the
`/config` shape was unknown — it has been known since 2026-09-16 and 10c already parses it, so the
block is stale. Proposed as 10c-bis before 10d; detail on the board.

**Branch state at close:** `feature/phase-10-payments`, **10 commits, local only, working tree clean**,
JVM 341 tests / 39 classes green, `compileDebugRealHwKotlin` + `lintDebug` clean. Not pushed.

---

## Previous status — 2026-09-15, later (OQ #22 built; signing cutover decided)

**The last open decision holding up written code is settled and built.** OQ #22 went with Option 1:
an attendant "End sale early" button that ends a fixed sale which will not reach its target. Checking
the code first found the problem was wider than the question: a pre-pay customer whose tank fills
before the target stranded the sale with no fault at all, no exit and the relay still on, and a
power cycle only restored the same stuck sale. On `feature/oq22-end-sale`; JVM **232 tests / 27
classes** green, `compileDebugRealHwKotlin` and `lintDebug` clean. **Not yet run on the tablet.**

**#37 is fixed and on `origin/main`** (`213b123`): both receipts now show the price the sale was
struck at.

**Signing is decided.** Balancee has an Android key and keeps it for production; the 14-day run is
signed with a dummy key, and the switch is a planned reinstall. That reinstall wipes the tablet, and
it exposed **#40**: a release build cannot be debugged, so nothing can get the run's records off it
first. Needs an in-app export before the run ends.

---

## Previous status — 2026-09-15 (both lines merged: 7h and Phase 9/9b/9c are on `main`)

**`main` now carries everything built except the 7g firmware.** The Phase 9 line
(`feature/onboarding-activation`, which contains `feature/api-live-probe`) was merged on top of 7h
in `553a049`. Both had branched from `3aea28c` without seeing each other. Five files conflicted, all
of them two additions in the same spot. Verified on the merge commit: JVM **224 tests / 26 classes**
green, exactly 165 + 59; `compileDebugRealHwKotlin`, `lintDebug` and `assembleRelease` clean. Not
yet pushed.

**One thing the merge made worse, on purpose to record rather than fix inside a merge: TODO #37.**
7h's notes predicted it and it is true: the on-screen receipt computes price/litre from amount ÷
litres, while the shared receipt from 9b prints the price the sale was struck at. They now disagree
about the same sale whenever litres and money come apart.

**The activation code is still the gate** on the API line (#31, #32), and the operator settings
screen that redeems it is now on `main`. The remaining no-wait work: #37, the 7g bench gate (#19),
the upload job (7e) and the OQ #22 options.

---

## Previous status — 2026-09-13 (7h bench gate PASSED; a defect found, fixed and retested on the rig)

**The Phase 7h merge gate is closed.** All eight steps of TODO #27 ran on an Arduino Uno with the
sketch from `main` and **no flow meter** — the firmware's own synthetic generator supplies the
pulses at 50 pps, which is 30 L/min at the placeholder K-factor. The branch is six commits now
(`15dee70` joins the original five) and is ready to merge. Verified at the gate: JVM **165 tests /
21 classes** green, `compileDebugRealHwKotlin` and `lintDebug` clean.

**The gate did its job: it found a defect the 162 unit tests could not.** Boot resume added the
recovered pulses to memory and left the database untouched, so the stored count and anchor stayed
at the previous checkpoint until the dispensing loop's next 25-pulse write. Restart twice inside
that window and the second reconciliation measured from the *same* anchor and re-reported fuel the
first had already reported — two fuel-log rows of 1.44 L and 2.83 L where the truth was 1.44 then
1.39. The sale's arithmetic was never wrong, because count and anchor are always read as a pair;
what came apart was the operator's record of what went missing, which is the only reason that card
exists. Fixed in `15dee70` with three tests, two of which fail without it.

**Diagnosing it took an instrument, not an argument.** Three plausible explanations were proposed
and killed by observation in turn: noise on the meter input (grounding pin 2 removed the wild
outliers but not the pattern; a kill from idle recorded nothing at all), a fast relaunch keeping the
watchdog fed (the operator was waiting eight seconds), and a stale anchor (the trace showed the
anchor is written live at every checkpoint). The answer was the fourth: **the checkpoint cadence is
not what the code assumes.** A checkpoint fires every 25 pulses *as processed by the app's
collector*, and on the SM-T220 that collector runs behind the board, so consecutive checkpoints
drift seconds apart and the last one before a kill can be far older than 25 pulses of real time.
Measured gaps reached 307 pulses where the three-second watchdog window alone allows about 150. The
oversized recoveries were real fuel.

**The trace had to be on screen.** The tablet's USB-C port cannot be an adb link and an Arduino host
at the same time, and wireless debugging would not hold for more than a few seconds at a stretch, so
logcat was unusable. The trace was written to the events table and rendered on the fuel log card,
photographed, and read back from there. Added in `53fa746` / `ec9399c`, reverted whole in `3631b38`
— revert that commit to get it back for a future bench session.

**Three findings logged rather than fixed, all pointing the same way.** Every one of them
under-counts, so the customer is never overcharged and the station absorbs the difference.
- **#28** — the gap ceiling is not merely calibrated against a placeholder, it is *structurally*
  short: it budgets 25 pulses of anchor staleness when the real bound is collector lag. A 4.48 L gap
  was refused on the bench and was almost certainly genuine fuel.
- **#36** — the app ends each restart about 22 pulses (0.22 L) behind the board, 67 across a
  three-restart sale. Invisible before the trace.
- **#37** — the receipt **computes** price per litre as amount ÷ litres instead of carrying the
  price the sale was struck at. It therefore states a figure the station never charged, and reads
  cheapest exactly when the customer got fuel for free. Found during the gate; not a 7h defect —
  recovery only made litres and money come apart often enough to notice.

**Step 8 turned OQ #26 from an argument into a measurement.** Killing the app two seconds into a
₦2,000 pre-pay overshot by about 1.5 L, more than half the sale again, because the only thing
bounding fuel while the app is dead is the firmware's three-second watchdog. The app behaved
correctly — the relay did not reopen and the sale completed recording more litres than were charged
— but nothing in the current design *can* stop fuel with the app dead, since the cutoff is the app's
decision to send. That is the case for a firmware-owned cutoff, and it is no longer about
milliseconds of latency. **#38** proposes halving the give-away by shortening the watchdog to 2 s as
a mitigation, not a fix.

**Bench-rig note for whoever repeats this.** The meter input is a bare pulled-up pin with nothing
attached, and a floating pin counts electrical noise as fuel. Tie it to 5 V whenever the synthetic
generator is the pulse source. Untied, recovered gaps ranged 1.11–4.48 L; tied, the spread closed
and a kill from idle recorded nothing. This is the same bare pullup **#22** already says must not
survive into production.

---

## Previous status — 2026-09-12 (Phase 9c: the activation code finally has a way in)

**Phase 9c is on `feature/onboarding-activation`, one commit (`ce4a0b8`) off
`feature/api-live-probe`** — branched from there rather than from `main` because it builds directly
on `5a378fe`, which is not merged. No bench gate: nothing here is device-specific. Verified: JVM
**184 tests / 22 classes** green (170 → 184); `compileDebugRealHwKotlin` and `lintDebug` clean, with
no new lint findings.

**What was wrong.** `PumpActivationRepository` was built the same morning with **no caller**. The
call, the save and the read-back were all correct and completely unreachable: nothing in the app
asked an operator for an activation code, so a code arriving from Balanceè could not be redeemed,
and **#32** — the one-sitting sequence that unblocks the rest of the API line, and that insists on
being driven through `PumpApiClient` rather than curl — had nothing to drive it from.

**What was built.** Two entry points sharing one panel and one `ActivationViewModel`:

- **Onboarding step 4.** Provisioning moved from the PIN confirm-match to the end of the new step.
  That reordering is the substance of the change, not housekeeping: writing the identity row *is*
  what ends onboarding, because `IdentityGateViewModel` observes that row and swaps the screen out,
  so provisioning at step 3 would have made step 4 unreachable. The PIN is held in memory across
  the activation step, which is no worse than it already was across the confirm entry.
- **The operator settings screen**, behind the attendant PIN. This one was not in the original
  suggestion and turns out to matter more: a pump installed before its code was issued finished
  onboarding long ago and will never see it again, and **every debug build auto-provisions a demo
  identity and never shows onboarding at all**. An onboarding-only entry would therefore have been
  unreachable in exactly the build that points at the dev backend — #32 would still have been
  blocked, for a new reason.

**Activation is optional, deliberately.** A pump is routinely installed before its code exists, and
cash sales do not need one. The exit reads "Finish without activating" rather than hiding as a skip
link, and the panel says plainly that cash works and card does not.

**The outcome copy carries a decision, not just words.** `ActivationReport` maps each of the six
outcomes to operator-facing text **plus what may be done next**, as two separate flags. They diverge
on exactly one case, which is the case an installer will get wrong: after `Unreachable` the *same*
code is safe to resend, while a *different* code may burn a spare on a pump the server has already
registered. Typing a different code there **warns rather than blocks** — support may have confirmed
the first code never landed, and an operator who did the right thing must not be stuck behind a
guard with no key. The device ID is on screen from the first frame, because that recovery is a phone
call about this specific unit; `PumpActivationRepository` gained `pumpId` so the panel can also say
what the unit is registered *as*.

⚠️ **Design-authority flag (CLAUDE.md).** There is **no activation screen in `docs/Strict design
screens/`**, so this layout is invention — the same deviation already on record for the error
screen. Tokens and components are the existing ones; only the arrangement is new.

**Not covered by tests:** `OnboardingViewModel`'s step sequencing. It takes an Android `Context` for
logo decoding, and the project has no Robolectric and no mocking library, so there is no cheap JVM
fake for it. The outcome-to-next-move logic, which is the part worth protecting, has 13 tests.

**Still the gate:** the activation code itself. #32 is now runnable the moment one lands.

---

## Previous status — 2026-09-12 (first real backend contact; two untracked V1 gaps closed)

**Everything below is on `feature/api-live-probe`, 15 commits off `main` at `3aea28c`, unmerged and
with no bench gate** — nothing here is device-specific. Verified at the last commit: JVM **170 tests
/ 21 classes** green, `compileDebugRealHwKotlin` + `lintDebug` + `assembleRelease` clean.

**Start here next session:** [`V1_BLOCKERS.md`](V1_BLOCKERS.md) sorts the remaining work by *who is
holding it up*. Three candidates need nobody: an **activation step in onboarding** (done 2026-09-12,
see the status above), the **transaction upload job** (7e — `workmanager` is not in the catalogue and nothing marks a row
synced), and a **draft of the OQ #22 options** (the last open decision). Everything else waits on
the bench rig, Kelvin, Olonade, or the activation code.

**Phase 9b (same day) closed two V1 gaps that were tracked nowhere at all.** Release builds could
not be signed — no `signingConfig` existed, so `assembleRelease` produced an uninstallable APK, and
nothing would have caught it until someone tried to put a build on a tablet. The build side is now
in; **the keystore itself is deferred to last by decision**, since signing gates the parallel run
(which waits on the K-factor) and key custody is the boss's call. Separately, **receipt sharing was
an empty function** behind a live button, and now works. **OQ #17 is settled**: customer gets one
plain line, diagnostics go to the swipe-up attendant panel, and the long-dead `recoverable` flag now
changes what the card looks like.

**Phase 9 — first contact with the real backend.**
The project has now made real requests to a real Balancee server for the first time — every prior
test ran against fixtures we wrote ourselves, which is the arrangement that let the response-envelope
defect through in August.

**What the probe settled** (evidence in `docs/api-probes/2026-09-12/`, no credentials sent, nothing
spendable touched): **both endpoints from `BOSS_CONFIRMATIONS_DRAFT.md` items 1 and 2 are deployed on
dev**; **our four signing header names are correct**; the **literal failure envelope** is captured for
the first time; and a **top-level `code` exists on the 400 from `/activate` but on none of the 401s**,
so the stable-error-code ask is half built. The server checks the API key *before* the timestamp and
signature, so **GET signing and clock skew cannot be tested from outside** — they now sit behind
activation with the `/config` payload, the decimals question and the status set.

**Two fixes rode on that.** TODO #14's parsing half is done — 4xx refusals now arrive as
`ApiError.Business` with the server's own message and code, on observed bytes rather than inference
(its *mapping* half is still blocked on attendant copy, OQ #17). And **activation is now safe to
spend**: `PumpApiClient.activate()` had no caller, so redeeming the single-use code would have
marked the pump activated server-side and thrown the once-only keys away. Call, save and read-back
are now one operation with outcomes that distinguish "refused" from "unknown" from "the code is
spent and the answer is lost".

**The activation code is now the gate on everything remaining.** Two questions to the backend shrink
the risk before it is used: are dev codes re-issuable, and can a dev pump be reset and re-activated.
Unchanged and independent of all of it: the **7h bench gate** and the **7g firmware gate**, both of
which need only the Arduino.

Verified: JVM **155 tests / 19 classes** green (125 → 155); `compileDebugRealHwKotlin` and
`lintDebug` clean.

---

## Previous status — 2026-09-11 (7h built on a branch; bench run outstanding)

**Phase 7h — pulse continuity across restarts — is BUILT on `feature/phase-7h-pulse-continuity`**
(five commits off `main` at `3aea28c`, unmerged). It closes the live under-billing described in
OPEN_QUESTIONS #25: fuel the adapter counted while the app was not running is now measured against
a persisted anchor and either put on the customer’s sale or written to a new operator-visible log
with a reason. **Its merge gate is a bench run with the Arduino, which has never happened** — no
part of the recovery path has met a real board (TODO #27). On the 7a-hardening / 7b precedent,
that gate is what merging waits on.

Verified so far: JVM **162 tests / 21 classes** green (125 → 162); **16 instrumented green on the
SM-T220** (12 → 16); `compileDebugKotlin`, `compileDebugRealHwKotlin` and `lintDebug` clean.

**Phase 7g was split rather than merged whole** (merge commit `4dee113`, 2026-09-07), pushed to
`origin/main`. The docs and the app-side `PULSES_PER_LITRE` Double are on `main`; the **five
firmware commits stay on `feature/phase-7g-eeprom-totaliser`** until the EEPROM totaliser is
verified on hardware. ⚠️ `hardware/*.ino` and `hardware/README.md` on `main` are therefore the
**pre-7g** versions while the docs beside them describe the merged sketch — flash from the branch,
not from `main`. Friday 2026-09-04 left no trial result in the repo, so that gate has not moved.

**Still the critical path, and still unsent: the #18 backend asks.** Nothing there moved this
week. 7h was chosen deliberately as work that depends on nobody — not the backend, not Olonade,
not the meter — and it is now done bar the bench. A **fifth ask** has been added to the list: the
new `events` rows have no endpoint to sync to (TODO #29).

---

## Previous status — 2026-09-07 (7g split: docs/app merged, firmware held)

**Phase 7g was split rather than merged whole** (merge commit `4dee113`, 2026-09-07). Merged and
**pushed** to `origin/main` on 2026-09-07. The docs and
the app-side `PULSES_PER_LITRE` Double are on `main`; the **five firmware commits stay on
`feature/phase-7g-eeprom-totaliser`** until the EEPROM totaliser is verified on hardware. ⚠️
`hardware/*.ino` and `hardware/README.md` on `main` are therefore the **pre-7g** versions while the
docs beside them describe the merged sketch — flash from the branch, not from `main`. Friday
2026-09-04 left no trial result in the repo, so the gate has not moved.

**Phase 7b (first half) is MERGED to `main`** (merge commit `0cfba90`, 2026-09-03; `main` =
`origin/main` = `0cfba90`). Verified before merge: 125 JVM tests / 17 classes, 12 instrumented on
the SM-T220, both variants compiling.

**Phase 7g (adapter EEPROM totaliser) is in flight on `feature/phase-7g-eeprom-totaliser`** —
pushed; its docs and app-side change merged 2026-09-07, **its five firmware commits not merged**.
It merges the previously separate bench sketch into `smartpump_pulse_adapter.ino`, fixing four firmware defects on the way (interrupts on
non-interrupt pins, a 150 ms debounce that capped counting at ~4 L/min, a torn-write ordering bug
in the EEPROM save, and relay-after-commit in the power-fail path), and adds a format marker plus
an erase utility. **The EEPROM totaliser has never been verified on hardware** — that is its merge
gate, on the 7a-hardening precedent.

**Live meter trial: Friday 2026-09-04.** Firmware is meter-ready (250 µs debounce, no synthetic
pulse sources, 4N35 front end documented). See `docs/FIELD_RUN_SHEET_2026-09-04.md` and the Friday
checklist in `docs/journal/BRANCH_7G_SUMMARY.md`. The day's goal is one number — the meter's
K-factor, which has never been measured. Two spec problems found while preparing: `TEST-01` states
two tolerances ten times apart, and deriving K and verifying K cannot be the same runs.

---

## Previous status — 2026-09-02 (7b)

Strict-design rebuild is complete and merged to `main`: all 5 flows, the attendant overlay, persistence/boot-resume, kobo money, and Room migrations are in. **Phase 7a (real USB-serial hardware) is merged to `main`** (Arduino pulse driver + relay, bench-verified). `main` is pushed to `origin`.

**`feature/phase-7a-hardening` is MERGED to `main`** (merge commit `9b76f42`; branch deleted) — comms-loss heartbeat watchdog (firmware PING dead-man + app heartbeat + reconnect `RLY:1` re-assert; the earlier app-side disconnect pause/resume was removed 2026-07-08) **plus** the **Balancee Pump API network-layer foundation** (signed client, typed errors + retry, Keystore-encrypted creds). Both merge gates closed before merge — #10 (Keystore crypto, device-verified 2026-07-08) and #2 (firmware-watchdog safety, device-verified 2026-07-10). The boss-facing watchdog safety summary + report shipped on top (`ceef973`, `8b72775`). `main` = `8b72775` = `origin/main` — build green on both variants, nothing outstanding on this line of work.

**Phase 8 (CustomerViewModel unit tests) is DONE and MERGED to `main`** (merge commit `d2c4283`, 2026-08-04; branch commits `88be743`/`98fa167`/`a128457`/`0bd45f3`) — 23 new pure-JVM tests (money/cutoff, dispensing completion, boot-resume, lifecycle) via hand-written fakes + an `UnconfinedTestDispatcher` rule; test-only bar one build flag. Post-merge verification on `main`: full suite green at **81 tests** (12 classes, 0 failures/errors/skips) and `compileDebugRealHwKotlin` clean. **Pushed: `main` = `origin/main` = `f5038d8`; branch `feature/phase-8-vm-tests` deleted.**

**⚠️ API conformance audit — 2026-08-05 → 4 of 9 issues FIXED and MERGED 2026-09-02.** The Pump API Reference PDF landed in `docs/` on 2026-08-04 (first sight of the *primary* doc; the network layer was built in July against our summary of it). Line-by-line audit found **9 issues** — see [`API_CONFORMANCE_AUDIT.md`](API_CONFORMANCE_AUDIT.md), tracked as TODO #11–#18. **`fix/api-response-envelope` is MERGED to `main`** (merge commit `cdb7c55`) closing the two that blocked everything plus the two identity fields: **(#11, critical)** responses are enveloped in `{status,message,data}` and we parsed the inner shape → all 5 calls would have failed against the real server, and the fixtures encoded the same wrong assumption so the green suite proved nothing — now `ApiEnvelope<T>` + `unwrap()`, every fixture rebuilt **verbatim from the Reference's literal JSON**; **(#12, security)** `apiKey`/`signingSecret` could reach logcat via `Level.BODY` *and* via the data-class `toString()` (a route the audit missed) — both closed by an allowlist `PumpLoggingInterceptor` + redacted `toString()`; **no leak ever occurred** (logcats re-grepped clean; activation never ran); **(#13/#16)** the once-only `pumpId` is now persisted and required, and `deviceId` is minted once into its own *plain* prefs file (deliberately **not** the encrypted blob, which self-purges on KeyStore invalidation and would silently re-mint the identity). **Decided:** `amount` is **naira** (app stays kobo; mapper owns the ÷100). Signing, headers, retry policy and the Keystore store were verified **correct**.

**Verification at merge:** JVM suite **107 tests / 15 classes**, 0 failures/errors/skips (81 → 107 across the batch); `compileDebugRealHwKotlin` clean; **instrumented on the SM-T220 (Android 14) = 8 tests green** — the 2 deviceId invariants plus the Keystore store now at 6 (the added case proves the `v: 2` legacy-blob purge against real crypto). **Pushed 2026-09-02: `main` = `origin/main` = `15bc0be`** (merge commit `cdb7c55`); branch `fix/api-response-envelope` deleted.

**Still open from the audit — #14, #15, #18.** **#14** (parse the envelope out of 4xx error bodies; `ApiError.Business` already exists) is blocked on *copy*, not code — no error screen in `docs/Strict design screens/`, OQ #17 open. **#15**'s mapping half is ready and rides on #14; its enforcement half has no home (not a device-owner app, so it cannot set the clock — only read `Settings.Global.AUTO_TIME` and warn). **#18 backend asks remain unsent and are the critical path by lead time:** `GET /config` and `GET /transactions/{id}` don't exist, **nothing tells the pump its `fuelType`** (which `/authorise` requires), does `amount` accept decimals, the full status set, what to sign for a GET — plus a new fifth ask: **stable error codes** alongside `message`, since matching on interpolated human strings breaks silently on a reword.

**✅ Phase 7b (first half) — device-local operator config — DONE 2026-09-02**, branch `feature/phase-7b-operator-config` (`05556c1`/`37388c5`/`af43918`). The pump had no way to know **which fuel it sells** — `/authorise` requires a `fuelType` and the API supplies none — so a manager now sets fuel type + price on the tablet, behind the attendant PIN. `DeviceConfig.fuelType` added at **schema v3** with the project's **first Room migration + migration test** (step 4 of the documented workflow, never previously done). The transaction guard blocks on a missing fuel type exactly as on a missing price. Also fixed: `seedDefaultConfigIfMissing()` ran in **every** build type, so a fresh release install seeded itself ₦870/L and the price guard could never fire in production — now debug-only. JVM **125 tests / 17 classes** green; **12 instrumented green on the SM-T220**. Accepted risk in OQ #19: same shared PIN, so any attendant can change the price (role-based PINs are V2).

**Next:** send the #18 asks — still unsent, still the critical path by lead time, and 7b's second half (`/config` sync) is blocked on exactly that. Then #14/#15 once attendant error copy exists (OQ #17), 7e backend sync (self-contained, unblocked), and the payment feature flows (#8).

Older entries (Week 1 → Room migrations) are archived in [PROJECT_LOG_ARCHIVE.md](PROJECT_LOG_ARCHIVE.md).

---

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

> Entries before Phase 7a are in [PROJECT_LOG_ARCHIVE.md](PROJECT_LOG_ARCHIVE.md). New entries still go at the bottom of this file.

### Phase 7a — Hardware: real USB-serial pulse driver + relay (bench-verified)
**Date:** 2026-06-11
**Status:** done (happy path bench-verified; one disconnect-robustness gap logged for follow-up)
**Commit(s):** 97f310d (plan), 122e1b0 (parser + tests), 32acf20 (driver + build type), a7b8fef (Arduino sketch); merged to `main` from `feature/phase-7a-hardware`

**Summary (plain language):**
The app can now run a real fuel dispense off actual hardware, not just the simulator. We wrote the firmware for an Arduino (the little board that will sit between the pump's pulse sensor and the tablet), and the Android side that talks to it over the USB cable. On the bench with an Arduino Uno: starting a transaction opens the pump (the board's light comes on), the litres count up as real electrical pulses arrive, and finishing the sale closes the pump (light goes off). Both directions were confirmed. The whole thing is built as a separate "real-hardware" version of the app that installs alongside the normal simulator version, so the boss demo has a rock-solid fallback — if the rig ever misbehaves, you just open the other app. The boss demo can show genuine hardware dispensing.

One rough edge found during testing and deliberately deferred (see Known limitation): if you yank the USB cable in the *middle* of a pre-pay dispense, the screen freezes instead of recovering cleanly. The fill-up flow handles this fine; the fixed-amount flows don't yet. Not a demo issue (you don't unplug mid-sale on purpose), but it's the next thing to harden.

**Technical notes:**
- **Pure core (`122e1b0`):** `data/hardware/serial/SerialFrameParser` (stateless line → typed `SerialFrame`; validates the `TYPE:<cum>*<cs>` framing + XOR-8 checksum, never throws) and `PulseAccumulator` (stateful cumulative→delta: dropped lines self-heal off the running count, BOOT re-baselines, backward jumps re-sync to 0 so no negative delta reaches the litre maths). 19 unit tests; checksums hand-computed as golden vectors (the framing doc's illustrative `PULSE:0042817*7C` is wrong — real XOR-8 is `5D`).
- **Driver (`32acf20`):** `UsbSerialConnection` (@Singleton owner of the one physical port via mik3y `usb-serial-for-android` 3.8.0 — single read loop, line-buffers to `\n`, parses, republishes typed frames on a hot `SharedFlow`; `writeLine()` for relay; `connected` StateFlow; USB permission via runtime `requestPermission` + persistent grant through the manifest attach filter; non-zero read timeout so writes aren't starved). `UsbSerialPulseSource` is a cold `observe()` per dispense that resets the session count on each relay-open and runs `PulseAccumulator` — so the VM's `pulseBaseline + msg.count` maths is unchanged (true drop-in for the mock). `UsbSerialRelayController` writes `RLY:1`/`RLY:0` (XOR-8 framed), optimistic `isDispensing` always cleared on stop so a failed write can't strand the app believing fuel still flows.
- **Wiring/build (`32acf20`):** `HardwareModule` switched from `@Binds` mocks to `@Provides` + `Provider`, branching on `BuildConfig.MOCK_HARDWARE` so only the selected impl is instantiated. New `debugRealHw` build type (`initWith` debug, `applicationIdSuffix .realhw`, `MOCK_HARDWARE=false`) installs alongside the mock `debug` app. Manifest gained the `USB_DEVICE_ATTACHED` intent-filter + `res/xml/usb_device_filter.xml` (Arduino/CH340/FTDI/CP210x vendors) for auto-launch + persistent permission.
- **Firmware (`a7b8fef`):** `hardware/smartpump_pulse_adapter/*.ino` — emits `BOOT` at power-up (relay asserted OFF first → upholds relay-open-on-boot), `HB` ~2s when idle, throttled `PULSE` carrying the free-running cumulative; reads checksum-validated `RLY:1`/`RLY:0` to drive the relay pin and mirrors state on the onboard `D13` LED (so a bare Uno demos with no extra parts). Meter-free demo synthesises pulses while dispensing (`AUTO_PPS=50` ≈ 30 L/min); optional button + real-meter-on-INT0 paths included. `hardware/README.md` has protocol, worked checksums, wiring, flashing, and the bench checklist.
- **Bench result (2026-06-11):** Uno flashed; `BOOT`/`HB` seen on serial. `debugRealHw` installed; USB permission granted. Fill-up before plug-in → no count (correct); after plug-in → litres count (correct). Relay confirmed both directions via the onboard `D13` LED: `RLY:1` lit it on authorise, normal completion sent `RLY:0` and cleared it. Fill-up unplug-mid-dispense → flow-gap watchdog moved to amount-due (correct).
- Both variants compile; lint clean; serial unit tests green. `main`'s mock demo path is unchanged.

**Known limitation (deferred to a 7a-hardening pass):**
- A mid-dispense USB disconnect is **not handled gracefully in the fixed-dispensing flows** (pre-pay, cash-fixed, USSD). Fill-up has a 3s flow-gap watchdog and recovers; the fixed flows have none, so a yank freezes `FixedDispensing` (litres stop, screen holds) instead of moving to a safe state. Reconnect resumes but stutters — leading suspects: the Uno reboots to relay-OFF on replug while the app still believes `isDispensing` (no `RLY:1` re-assert), the `USB_DEVICE_ATTACHED` filter may relaunch the activity, and `PulseMessage.Disconnected` is ignored in the fixed collector. Needs logcat to pin the exact sequence. Tracked in `OPEN_QUESTIONS` (mid-dispense link loss); relates to OQ #3 (relay-on-boot) and #7 (relay closing late). **Not a demo blocker** — only triggers on a deliberate cable-pull mid-sale.

**Next:**
After the demo: 7a-hardening — add disconnect handling to the fixed-dispensing flows (a watchdog and/or an explicit "pump disconnected — reconnect" state) and a reconnect-mid-dispense relay/session re-assert policy; capture logcat from a repro first. Then resume the Phase 7 sub-phases by external-blocker availability (7b operator config / 7e backend sync are the unblocked first movers).

---

### Phase 7a-hardening — mid-dispense disconnect: fail-safe relay + fixed-flow pause/resume
**Date:** 2026-06-30
**Status:** done (code + bench-protocol; build green. Field logcat verification + VM unit tests still to come)
**Commit(s):** dbf4cb6 (§1 firmware watchdog), c416322 (§2 app keepalive), 8453d85 (§3 app disconnect handling), <docs> — on branch `feature/phase-7a-hardening`, not yet merged to `main`

**Summary (plain language):**
We closed the rough edge found during the Phase 7a bench test: yanking the USB cable in the middle of a *prepaid* sale used to freeze the screen. Two things now make that safe. First, the Arduino itself became the safety authority over fuel: it only keeps the pump on while the app keeps telling it "still going" (about once a second); the moment the app goes quiet — cable pulled, app frozen — the board shuts the pump off on its own within ~2 seconds. Fuel can no longer keep flowing just because the tablet lost contact. Second, the app no longer freezes: if the link drops mid-sale, the screen shows a clear "pump disconnected — reconnecting" hold, remembers how much was dispensed, and **automatically carries on to the litres the customer paid for** the instant the cable is back. Because the customer prepaid, we never charge them for the partial amount — we owe them the full litres, so we resume rather than cut the sale short (that's different from a fill-up, where you pay for whatever flowed). The whole thing can be demoed on the simulator too, via the existing debug "inject disconnect".

**Technical notes:**
- **§1 — firmware dead-man watchdog (`smartpump_pulse_adapter.ino`):** relay is now fail-closed. `serviceRelayWatchdog()` closes the relay if no valid `RLY` command arrived within `RELAY_DEADMAN_MS = 2000` (≈3× the app keepalive period, under the 3 s fill-up shutoff window). Emits best-effort `ERR:WDOG` (XOR-8 `64`) on trip. `lastRelayCmdMs` updated on every accepted `RLY:1/0`. Protocol header + `hardware/README.md` (protocol table, worked checksum, bench checklist) updated. Android build untouched.
- **§2 — app relay keepalive (`UsbSerialRelayController`):** while `isDispensing`, a coroutine on an app-lifetime IO scope re-asserts `RLY:1` every `KEEPALIVE_MS = 700`. This feeds §1's watchdog *and* re-energises the relay automatically when the port reopens after a brief unplug (no VM involvement). All relay writes (`start`/`stop`/keepalive) funnel through one `Mutex` so the keepalive can't race a start/stop on the single physical port; `isDispensing` is gated off before the keepalive job is cancelled in `stopFuelFlow()` so a late `RLY:1` can't follow the final `RLY:0`. Mock `MockRelayController` unchanged.
- **§3 — app fixed-flow disconnect handling (`CustomerViewModel` + new state):** new `TransactionState.PumpDisconnected` (snapshot: flow, txnId, price, amount, litresTarget, litresSoFar, method). The pre-pay/USSD collector (`startDispensing`) and the cash-fixed collector (`startCashFixedDispensing`) now `when` over the message: `Disconnected` → pause into `PumpDisconnected` **without** cancelling the collector or dropping relay intent (so §2 keepalive keeps trying); `Heartbeat`/`Pulse` while paused → flip back to the dispensing state and continue. The live collection keeps its session pulse count across the reconnect (the accumulator's `onBoot` re-baselines the Uno's reset counter), so resume needs **no** `pulseBaseline` surgery. `bootResume()` gained a `PumpDisconnected` branch that restarts the underlying dispense toward its prepaid target (covers a power cut while paused). New `PumpDisconnectedScreen` (WarningRed, dispensing-family card language) wired into `CustomerStateHost`; `StateColors` + `txnRefFor` branches added.
- **Design call (resolves OQ #21, pending boss ratification):** fixed flows **pause-and-resume**, not treat-as-shutoff, because the customer prepaid for the full litres. Fill-up keeps its 3 s flow-gap shutoff (it bills what flowed by design and so was never affected).
- **Verification:** `:app:compileDebugKotlin`, `:app:compileDebugRealHwKotlin`, `:app:testDebugUnitTest` all green (existing 19 serial unit tests still pass). Firmware reviewed but not re-flashed this pass — the §1/§2 watchdog+keepalive loop wants a bench re-run (unplug-mid-fixed-dispense → LED off within ~2 s → replug → resumes from where it paused) before merge. Manual mock-build check available via debug "inject disconnect".

**Deferred (intentionally):**
- **CustomerViewModel unit tests** for the pause/resume/boot-resume paths — these need the fakes+Turbine VM test harness that **Phase 8** is scoped for (gated, with two open decisions). The disconnect/resume logic is now the obvious first Phase 8 target rather than a reason to stand the harness up early here.
- **Field logcat verification** of the exact reconnect sequence on the real rig (the original bench note's worry about a `USB_DEVICE_ATTACHED` activity relaunch — not reproduced on the demo path with the persistent grant; revisit only if logcat shows it).

**Next:**
Bench re-run of the watchdog+keepalive loop on the Uno, then merge `feature/phase-7a-hardening` → `main`. Phase 8 (VM tests) picks up the disconnect path first. Then the unblocked Phase 7 sub-phases (7b operator config / 7e backend sync).

---

### Phase 7a-hardening (addendum) — watchdog re-keyed on a dedicated PING heartbeat
**Date:** 2026-06-30
**Status:** done (code + protocol; build green. Same bench re-run still pending before merge)
**Commit(s):** 07d7ccb — on branch `feature/phase-7a-hardening`

**Summary (plain language):**
After the boss reconciled this against the project docs, we firmed up *how* the pump knows the tablet is still there. The earlier version treated the "keep dispensing" command itself as the heartbeat. The agreed production design separates the two: the tablet now sends a tiny, dedicated "still alive" ping about once a second, and the pump-side board watches **that**. If the ping goes quiet mid-sale — cable knocked out, app frozen — the board shuts the pump off on its own, without needing to know how many litres were bought. It's the cleanest statement of the rule: *lose contact, stop dispensing.* This works in the field because the production board runs off the station UPS, not off the tablet's USB, so it stays powered and in control even if the data cable drops. Behaviour the customer sees is unchanged — a brief drop still auto-resumes their prepaid litres once the link is back.

**Technical notes:**
- **Protocol:** new app→device `PING*<cs>` (XOR-8 `10`), distinct from the device→app `HB`. `RLY:1`/`RLY:0` revert to one-shot edge commands. `ERR:WDOG` retained as the trip notice.
- **Firmware:** watchdog now keys on `lastHeartbeatMs` (fed by `PING`) vs `HEARTBEAT_TIMEOUT_MS = 3000`; `RLY:1` seeds the heartbeat clock to avoid a start-of-dispense race. Renamed from the `RELAY_DEADMAN_MS`/`lastRelayCmdMs` form.
- **App:** `UsbSerialConnection` owns the heartbeat — a framed `PING` every `HEARTBEAT_PERIOD_MS = 1000` on an app-lifetime IO scope while the port is open; `writeLine` is now `synchronized(writeLock)` so heartbeat and relay writes can't interleave on the one port. `UsbSerialRelayController` drops the RLY keepalive and instead **re-asserts `RLY:1` on the `connection.connected` down→up edge** when `isDispensing` (the watchdog is fail-safe and never re-energises itself). `§3` `PumpDisconnected` pause/resume UI is unchanged.
- **Residual gap (documented, not fixed):** the heartbeat covers USB-drop / crash / process-death. A pure *main-thread* ANR where background (IO) threads keep running would keep the heartbeat alive while litre-counting (Main-confined) is stalled — the adapter wouldn't trip. The app's own litre-cutoff remains the primary control; the watchdog is the backstop. Gating the heartbeat on main-thread liveness is a possible future hardening if that failure mode matters.
- **Verification:** `:app:compileDebugKotlin` + `:app:compileDebugRealHwKotlin` + `:app:testDebugUnitTest` green. Firmware not re-flashed — folds into the one pending bench re-run (now: confirm `PING*10` ~1/s; unplug mid-dispense → relay off within ~3 s; replug → `RLY:1` re-assert → resume).

**Next:**
Unchanged from the parent entry — bench re-run, then merge. (The bench step now also checks the `PING` cadence + 3 s trip.)

---

### Docs reconciliation — Pump API Reference / blocker-resolution ingest
**Date:** 2026-07-03
**Status:** done (docs only, no code)
**Commit(s):** uncommitted

**Summary (plain language):**
A new doc (`docs/phase7_blocker_resolution.md`) arrived that answers most of the payment/onboarding
blockers — and changes the shape of the whole payment side. The old design had the bank's backend
calling *into* the pump (a "webhook"); the new one has the **pump doing the calling** — it asks the
backend to start a sale, gets a Paystack QR back, the customer pays Paystack, and the pump finds out
it was paid by a mix of a push message and a quick repeated check. We went through the project's
authority docs and brought them in line with this, marked which old open questions are now answered,
and — importantly — corrected one mistake in the new doc: it claimed the offline USSD flow was dead,
but that flow (for stations with no internet) is only **deferred to a future update**, not cut.

**Technical notes:**
- **`OPEN_QUESTIONS.md`:** #5 (webhook signing → now *outbound* HMAC-SHA256, 4 signed headers), #6
  (station virtual account → obsolete, Paystack owns payments), #14 (receipt sharing → system share
  sheet) moved to **Resolved** (all "pending boss ratification that the API Reference is canonical").
  #7 (late-payment) and #8 (price-push channel: FCM-vs-WebSocket pending a Play-Services answer) and
  #13 (DeviceConfig → `/activate` + `/config`) annotated in place. USSD/SMS block (#9–#12) re-headed
  **DEFERRED to a future update** with the two-USSD-paths correction. Numbers kept stable (gaps left).
- **`phase7_blocker_resolution.md`:** struck-and-corrected two claims against reality — (a) USSD is
  *not* obsolete (offline mode ≠ Paystack USSD; deferred, not dropped); (b) the "7a stays mocked"
  line is stale — 7a real hardware is built/bench-verified/merged; this doc has no firmware impact.
- **`flows.md` / `state-machine.md`:** added payment-inversion banners (webhook→pump-initiated
  Paystack QR + push/10s-poll on `GET /api/pump/transactions/{id}`; Flow 3 NIP-QR replaced by a
  Paystack `authorizationUrl`; states unchanged, only the confirmation *trigger* changes). Flow 5
  marked deferred in both. Flow bodies left intact pending ratification — banners hold authority.

**Next:**
Build the network client layer (Retrofit/OkHttp + the outbound HMAC signing interceptor) as the
Phase 7c/7e foundation — the one piece safe to build before the boss ratifies the reference, since
the signing scheme is fully specified and unit-testable offline.

---

### 7a-hardening follow-up — remove app-side disconnect pause/resume (keep firmware watchdog)
**Date:** 2026-07-08
**Status:** done
**Commit(s):** uncommitted (this entry committed alongside the change)

**Summary (plain language):**
The "pump disconnected" pause-and-resume screen — added in the 7a-hardening work so a mid-fill USB
cable-yank would hold the sale and resume where it left off — was causing recurring bugs. We're now
building on the assumption that **the USB cable is fixed in the kiosk** (it can't be yanked) and the
only real failure is a power cut, which the UPS covers. So that whole app-side "disconnected" state
and its screen were removed to simplify things. Crucially, the **safety cut-off stays**: the Arduino
still stops the pump on its own if it stops hearing from the tablet (e.g. the app freezes while fuel
is flowing) — a fixed cable does nothing to prevent that, so the backstop had to remain. A genuine
brief USB glitch also still self-heals and keeps counting; the customer just sees the normal
dispensing screen pause a moment instead of a dedicated "disconnected" screen.

**Technical notes:**
- Removed `TransactionState.PumpDisconnected`, `PumpDisconnectedScreen.kt` (deleted), and its
  branches in `CustomerStateHost`, `StateColors.borderColor()`, and `CustomerViewModel`
  (boot-resume, `txnRefFor`, both dispensing collectors, the four `to*/toDisconnected` mappers).
  The pre-pay/USSD/cash-fixed collectors now no-op `PulseMessage.Disconnected`/`Heartbeat`
  (the `PulseMessage` types are retained — they still map real wire events).
- **Kept untouched:** `UsbSerialConnection`'s outbound `PING` heartbeat (feeds the firmware
  dead-man watchdog), `UsbSerialRelayController`'s down→up reconnect `RLY:1` re-assert, and the
  Arduino sketch. Comms-loss *safety* is therefore unchanged; only the disconnect *screen/pause* is gone.
- Backward-compat: a device persisted mid-`pump_disconnected` across this upgrade fails to
  deserialise and falls back to `Idle` on boot (same graceful path as the kobo migration).
- Verified green: `:app:compileDebugKotlin` and `:app:compileDebugRealHwKotlin` both BUILD SUCCESSFUL.
- Docs reconciled: `OPEN_QUESTIONS.md` #21 got a dated revision (parts 1–2 kept, part 3 removed);
  `TODO.md` #2 bench checklist updated (watchdog + reconnect only, no pause/resume).

**Next:**
Unchanged — Uno bench re-run of the firmware watchdog + reconnect re-assert, then merge
`feature/phase-7a-hardening` → `main`. Then Phase 8 (CustomerViewModel unit tests).

---

### Phase 7a-hardening — bench verification: firmware watchdog safety confirmed on device (merge gate #2 closed)
**Date:** 2026-07-10
**Status:** done (watchdog safety verified on the real rig; branch merge-ready, merge pending)
**Commit(s):** committed alongside this entry — on branch `feature/phase-7a-hardening`

**Summary (plain language):**
This was the final safety check on the real tablet-and-Arduino rig before merging the comms-loss
watchdog work. The scare from the previous bench session — where a normal sale kept cutting off after
about six seconds — turned out **not** to be a code fault. On a freshly installed build the rig ran
six sales back-to-back with no trouble, and the tablet's "still-alive" pings to the board stayed
perfectly steady the whole time. The earlier cut-offs were a flaky-power quirk of the *bench* setup:
the little board browns out when it runs off the tablet's own USB port, and momentarily drops the
connection. Production avoids this entirely by powering the board from the station UPS, and even when
the glitch does happen it fails *safe* (it stops fuel, never over-pours). The safety test itself then
passed cleanly: with fuel flowing we force-killed the app to simulate a freeze/crash, and the Arduino
shut the pump off on its own about three seconds later — exactly as designed. That was the last thing
blocking the merge.

**Technical notes:**
- **Rig:** Samsung SM-T220 (Galaxy Tab A7 Lite, Android 14, USB-C; wireless adb since the USB-C port
  hosts the Uno). Freshly built + installed `debugRealHw` carrying two temporary `Log` diagnostics
  (`PING tx ok=<bool>` per heartbeat; `device ERR frame:<code>` for any `SerialFrame.Error`) — **since
  reverted** (source now byte-identical to HEAD).
- **Repro attempt (did not reproduce):** 6 dispenses back-to-back — 4 fill-up, 1 pre-pay, 1
  post-replug — each 23–50 s, all to normal completion. `PING tx ok=true` **steady at 1 Hz through
  every dispense**; **zero `ERR:WDOG`**; no write failures. The "consistent ~5.9 s trip" from the prior
  session was absent → **PING-starvation-under-pulse-load is ruled out.**
- **Root cause reframed (not a code bug):** an **intermittent bus-power USB disconnect**. Signature is
  `USB get_status request failed` → self re-enumeration (device path `001/060`→`061`). When it lands
  mid-dispense, the read-error `handleDetach()` teardown stops the heartbeat → firmware trips at +3 s →
  app pulse-gap watchdog ends the sale (a fixed ~3 + 3 s offset *after* the disconnect; whether it
  strikes mid-dispense is intermittent). Bench artifact — the bare Uno + relay browns out off the
  tablet's OTG port; production powers the adapter from the UPS; the failure is fail-safe.
- **Merge gate #2 — safety check PASSED (the reframed primary case):** mid-dispense
  `adb shell am force-stop app.balancee.smartpump.display.realhw`. Last `PING` 14:38:56.443, process
  confirmed killed 14:38:57.423, **relay physically opened ~3 s later** (watched on `D13`). `ERR:WDOG`
  is guaranteed by construction — `serviceRelayWatchdog()` runs `setRelay(false)` then `sendError("WDOG")`
  inside the one `if`, so the relay dropping *is* that branch executing. This proves the "app
  frozen/crashed mid-flow, cable still connected" mode that the UPS + fixed-cable assumption do **not**
  cover.
- **Evidence retained:** `docs/logcats/bench-multirun_realhw_2026-07-10.log` (the 6 clean dispenses),
  `docs/logcats/forcestop-test_2026-07-10.log` (the safety proof). A stale Android-Studio `package:mine`
  export that captured zero app logs was removed.
- **Non-blocking follow-up (post-merge):** spontaneous-disconnect robustness — tolerate a sub-second USB
  glitch with a fast reconnect-and-resume vs. keep the current fail-safe (stop fuel). Validate on
  **external 5 V** (powered USB-C hub + PD pass-through, Route A); the `get_status` re-enumerations are
  expected to vanish on stable power. Does not hold the merge.

**Next:**
Merge `feature/phase-7a-hardening` → `main` (pending explicit go). Then Phase 8 (CustomerViewModel
unit tests) → unblocked Phase 7 sub-phases + payment feature flows (#8).

---

### Phase 7a-hardening — merged to `main` + boss watchdog safety deliverable
**Date:** 2026-07-10
**Status:** done
**Commit(s):** `9b76f42` (merge), `ceef973` (safety summary + OQ #22), `8b72775` (report to `/reports`)

**Summary (plain language):**
With both merge gates closed, the hardening branch was merged into `main` and the branch deleted.
That folds the comms-loss safety watchdog and the Balancee payment network-layer foundation into the
mainline. On top of the merge, we produced the boss-facing deliverable that explains the watchdog
safety story in plain terms — a one-page feature summary and a formatted report (HTML + PDF) now
living under `/reports`. `main` is pushed; there is nothing left outstanding on the hardware/hardening
line of work — the next scheduled work is the Phase 8 test harness.

**Technical notes:**
- **Merge (`9b76f42`):** `feature/phase-7a-hardening` → `main`, no-fast-forward merge commit; source
  branch deleted (confirmed absent from `git branch -a`). Brings in the full lineage `1f2a3ea …
  4a10824` (firmware watchdog, PING heartbeat re-key, app-side pause/resume removal, network layer,
  KeyStore crypto, both gate closures). Build green on `debug` + `debugRealHw`.
- **Boss deliverable (`ceef973`, `8b72775`):** watchdog safety feature summary written for a
  non-engineer audience; report moved into `/reports` as an HTML + PDF pair. New open question logged
  as OQ #22.
- **State after:** `main` = `origin/main` = `8b72775`. Both merge gates (#2 firmware-watchdog safety,
  #10 Keystore crypto) verified on device before merge.

**Next:**
Phase 8 (CustomerViewModel unit tests — disconnect/boot-resume + litre-cutoff paths first), then the
unblocked Phase 7 sub-phases (7b operator config / 7e backend sync) and the boss-gated payment feature
flows (#8).

---

### Phase 8 — CustomerViewModel unit tests (harness + money/dispensing/boot-resume/lifecycle)
**Date:** 2026-07-31
**Status:** done
**Commit(s):** `88be743` (A: infra), `98fa167` (B: harness + money + completion), `a128457` (C: boot-resume + lifecycle) — on branch `feature/phase-8-vm-tests`

**Summary (plain language):**
The app's highest-risk piece — the ~1,000-line "brain" that runs the money maths, opens and closes
the pump, and resumes a half-finished sale after a power cut — had no automated tests. It now has 23,
all passing. They pin down the behaviour that matters: fuel stops exactly at the litres the customer
paid for (and never over-pours, even on a big pulse jump), amounts too small to buy any fuel are
refused, the pump is forced closed on start-up, cancelling a sale shuts fuel and wipes the running
count, and a sale interrupted by a power cut picks up from where it left off rather than restarting or
double-charging. This is test-only work — no behaviour of the shipping app changed. The full project
test suite is green at 81 tests.

**Technical notes:**
- **Purely additive (per the two settled decisions):** the only `src/main` touch is a build flag.
  `testOptions { unitTests.isReturnDefaultValues = true }` lets the VM's direct `android.util.Log`
  calls return defaults instead of throwing "not mocked" — chosen over introducing a production
  `Logger` interface. DAO/Room tests deliberately **deferred** to keep Phase 8 pure-JVM (no
  Robolectric, no androidTest).
- **Harness (`CustomerViewModelTestSupport.kt`):** hand-written fakes (no mocking library) for
  `PulseSource`/`RelayController`/`PaymentProcessor`/`DeviceConfigRepository`/`PulseRepository`/
  `TransactionRepository`, wired to the **real** `CanStartTransactionUseCase`. A `MainDispatcherRule`
  pins `Dispatchers.Main` to one `UnconfinedTestDispatcher` (shared `TestCoroutineScheduler`), so
  `viewModelScope` runs eagerly and assertions read `ui.value` synchronously — **Turbine proved
  unnecessary** and was not added. Because `init` runs `bootResume()` eagerly, the fakes are seeded
  **before** `VmHarness.build()`. Test price fixed at `TEST_KOBO_PER_LITRE = 100_000` (₦1000/L) so
  litres = pulses/100 give round numbers.
- **Coverage (23 tests):** money/cutoff (cutoff = amount÷price, the floor-to-0.01L
  never-over-dispense guard, below-minimum → recoverable Error, price-not-set guard, audit-record
  accuracy); completion (fixed/pre-pay/cash-fixed stop at target, no overrun on a pulse jump, relay
  closed, correct `Transaction` saved, method recorded); fill-up (open-ended count + nozzle-shutoff →
  `FillupTankFull` with locked litres/amount); boot-resume (every `bootResume` branch, restarting from
  the persisted pulse baseline); lifecycle (relay-open-on-boot invariant, cancel teardown, dismiss,
  prepay expiry auto-cancel via `advanceTimeBy`).
- **No production bug surfaced** — the tests pin current behaviour and all passed as written. (Had one
  failed, fixing it would have been a separate flagged change, not folded into a test commit.)
- **Verification:** `:app:testDebugUnitTest` → **BUILD SUCCESSFUL, 81 tests, 0 failures, 0 errors**
  (23 new + 58 existing). JBR (Java 21) via `JAVA_HOME`.

**Next:**
Merge `feature/phase-8-vm-tests` → `main`. Then the unblocked Phase 7 sub-phases (7b operator config /
7e backend sync) and the boss-gated payment feature flows (#8, pending the 7 boss confirmations).

---

### Phase 7 network layer — Balancee Pump API client foundation
**Date:** 2026-07-04 (committed on `feature/phase-7a-hardening`; logged here 2026-07-10)
**Status:** done (transport + credential foundation; payment *feature* flows still gated on boss confirmations)
**Commit(s):** 29fe12b (docs reconciliation), 4af9514 (network layer), ff8fd11 (PumpApiClient), ac73feb (encrypted creds), ccf0534 (debug cleartext), 6df836f (backend URLs), 91fa772 (Keystore instrumented test / gate #10)

**Summary (plain language):**
Alongside the watchdog work, this branch also built the plumbing for the app to talk to Balancee's
backend over the internet — the signed HTTP client, consistent error handling, and secure on-device
storage of the pump's login credentials — *without* yet wiring up the actual payment screens (those
still wait on a set of confirmations from the boss). This is the safe-to-build foundation: the request
signing is fully specified, so it can be written and unit-tested offline now, ahead of the parts that
are still provisional.

**Technical notes:**
- **Signed client (`4af9514`):** Retrofit/OkHttp network layer with the outbound HMAC-SHA256 signing
  interceptor per the Pump API Reference (4 signed headers).
- **Transport wrapper (`ff8fd11`):** `PumpApiClient` over all 5 endpoints; `ApiResult`/`ApiError`
  typed-error funnel (`safeApiCall`), `retryingApiCall` backoff (retry on the idempotent upload);
  interceptor throws typed `PumpNotActivatedException`. MockWebServer tests green. **DTO↔domain mapping
  deliberately deferred** (client kept transport-only while the money unit and `/config` shape are
  provisional).
- **Encrypted creds (`ac73feb`):** `KeystorePumpCredentialsStore` — AES-256-GCM key in the Android
  KeyStore + ciphertext in private SharedPreferences, decrypted creds cached for the synchronous
  `current()` hot path. Chose KeyStore-direct over the deprecated `security-crypto` lib; `NetworkModule`
  binding swapped, `InMemoryPumpCredentialsStore` deleted.
- **Gate #10 (`91fa772`):** first androidTest in the project — 5 instrumented tests
  (`KeystorePumpCredentialsStoreTest`) pass on a physical device: not-activated, save→current
  round-trip, isActivated toggle, persistence across a fresh instance, `clear()` wipe, corrupt-blob →
  null fallback + ciphertext purge. Runtime AES-GCM-at-rest confirmed.
- **Config (`ccf0534`, `6df836f`):** debug-only cleartext `network-security-config` for the local
  backend (10.0.2.2/localhost/127.0.0.1; `debugRealHw` reuses it; release stays cleartext-denied);
  real prod/dev backend URLs wired.

**Next:**
Payment feature flows (#8) — activate → persist creds; authorise → Paystack QR; PAID via push + 10 s
poll; price-config fetcher; WorkManager upload job — **blocked on the 7 boss confirmations (#6).**
Sandbox-testable; live money gated behind the 14-day parallel run.

---

### API conformance audit — Reference PDF vs the built network layer
**Date:** 2026-08-05
**Status:** done (audit + decisions; no code fixes yet)
**Commit(s):** this entry + `docs/journal/API_CONFORMANCE_AUDIT.md`

**Summary (plain language):**
The official API document from the backend team was added to the project folder yesterday — the first
time we've been able to read the *primary* document rather than our own written summary of it. We
went through it line by line against the code we built in July and found nine problems. The most
serious: every response from their server arrives wrapped in a standard outer layer
(`status`/`message`/`data`), and our code expects the contents without the wrapper — so as it stands
**not one of our five API calls would work against the real server**. Our tests didn't catch this
because they were written from the same wrong assumption, so they were only ever checking that we
agreed with ourselves. Second most serious: in test builds we print the server's reply to `/activate`
into the device log, and that reply contains the pump's permanent secret key — the one thing the API
document says in capital letters to store securely and never expose. Nothing has actually leaked
(that step has never been run, and the committed logs are clean), but it had to be caught before the
first real activation, because that key is issued exactly once.

We also settled a money question that had been open for a month: prices go to the backend in **naira**,
not kobo. Their worked example (₦7,000 for 10 litres = ₦700/litre) only makes sense that way. Usefully,
their server rejects any mismatch outright, so a wrong guess here breaks loudly at the till rather than
silently overcharging a customer 100×.

Three of the nine aren't ours to fix: two endpoints we've designed against **don't exist in their API
at all**, and one consequence is sharp — nothing in their system currently tells a pump *which fuel it
sells*, yet their own sale endpoint requires that. That now sits on the critical path for the whole
payment phase.

**Technical notes:**
- **Method:** all 526 lines of the Reference (`pdftotext -layout`) vs `data/network/`,
  `domain/network/`, `di/NetworkModule.kt`. Full write-up with evidence, file:line refs, severities
  and fixes in `docs/journal/API_CONFORMANCE_AUDIT.md`; tracked as TODO #11–#18.
- **Root cause:** the layer was built against `phase7_blocker_resolution.md` (our summary of a v3
  `.docx`). The summary was correct on **endpoint inventory and the signing scheme** — and those parts
  of the code are verified correct. Compression dropped the **response envelope**, the **once-only
  `pumpId`**, and the **`fuelType` requirement**. Every defect is payload-shape or lifecycle-value;
  none is a logic defect.
- **Critical (#11):** `PumpApiService` returns inner DTOs; server wraps everything in
  `{status:Boolean, message, data}`. Top-level `status` is Boolean vs our `String` → type mismatch, and
  all other fields are one level down → missing-field failure. Affects all five calls incl. unsigned
  `/activate`. `PumpApiClientTest` fixtures are unenveloped, so green tests proved nothing.
- **Security (#12):** `HttpLoggingInterceptor.Level.BODY` under `BuildConfig.DEBUG`; `redactHeader()`
  covers headers only, so the `/activate` **body** (`apiKey` + `signingSecret`) prints. `debugRealHw`
  is a debug build and `docs/logcats/` is committed practice. Grepped `docs/logcats/` for
  `signingSecret|apiKey|bal_live|sec_|X-Signature` → **0 hits**; exposure is prospective only.
- **Lifecycle (#13, #16):** `pumpId` is returned once by `/activate`, required in `/authorise` +
  `/upload` bodies, and absent from `PumpCredentials` → unrecoverable without revoke-and-reissue.
  Name collision with `DeviceConfig.pumpId` ("PUMP 1" label vs UUID) flagged for a rename. `deviceId`
  is ours to mint, has no generator, and must be stable forever.
- **Decision — `amount` is NAIRA** (Reference states no unit anywhere; §4.2 example `7000`/`10 L`
  → ₦700/L). App stays kobo internally; the repository mapper owns the ÷100 as the single flip point.
  Server's exact `amount === expectedLitres × stationPricePerUnit` check (`400 Amount mismatch`) makes
  a wrong unit fail closed. **Open:** whether `amount` accepts decimals — integer-only would reject a
  ₦33,166.05 fill-up outright (exact check), constraining pricing to whole naira/L (business call).
- **Verified correct, no action:** `PumpRequestSigner` (HMAC-SHA256 over `timestamp + "." + rawBody`,
  lower-case hex — now confirmed against their Node reference impl), `PumpSigningInterceptor` (signs
  the already-serialised body; honours "do not re-serialize after signing"; `@Unsigned` exempts
  `/activate`), all four headers, upload-only retry, Keystore store, `FuelType` enum.
- **Backend gaps (#18):** `GET /api/pump/config` and `GET /api/pump/transactions/{id}` are **our
  proposals, not their endpoints** — the Reference documents exactly three (`/activate`, `/authorise`,
  `/transactions/upload`), confirmed by its §5 cheat sheet. `/activate` returns credentials only, so
  there is **no source for `fuelType`**, which `/authorise` requires. `PAID` is a real status (§2
  diagram) but missing from the §5 list.
- **Standing lesson:** where a spec exists, build fixtures from its **literal examples**. Had
  `PumpApiClientTest` used the Reference's verbatim JSON, #11 would have been caught in July.

**Next:**
Fix #11 (envelope) first — nothing else is testable until responses parse, and it yields the corrected
fixtures. Then #12 before any real activation. #14/#15/#13/#16 alongside the activation flow. Send the
#18 asks to the boss immediately (longest lead time). Interim for `/config`: build the device-local
operator config screen as 7b's first half behind the existing `DeviceConfigRepository` seam — also the
backend-unreachable fallback, so not throwaway.

---

### API conformance batch — envelope, secret logging, and the two identity fields (#11/#12/#13/#16)
**Date:** 2026-09-02
**Status:** done
**Commit(s):** `cda2f7e` (#11), `e901ecb` (#12), `3ed6fff` (#13/#16), `00e16a4`/`5c17a4f`/`1f03b46`/`b20d349` (docs); merged to `main` as `cdb7c55` from `fix/api-response-envelope`

**Summary (plain language):**
The Pump API Reference PDF arrived in August, and reading it line by line showed that the code we
wrote in July — against our own written summary of that API rather than the document itself — had
four real problems. This phase fixes them.

The big one: every reply the server sends is wrapped in an outer envelope, like a letter in a
sealed package, and our code was reading the package as though it were the letter. **Every single
one of the five calls to the backend would have failed** the first time we pointed the app at a
real server. Worse, our tests had been written with the same misunderstanding baked in, so they
passed happily and told us nothing — the tests confirmed our mistake instead of catching it. The
fixtures are now copied word-for-word out of the Reference document, so they can only agree with
the real server.

The second: the app's debug logging could have printed the two permanent secrets the server hands
out at setup — the API key and the signing key — into the device log, and we commit device logs
into this repo. **Nothing actually leaked** (we searched every committed log; the setup step has
never been run against a real server), but the secrets are issued once, so a leak would have meant
asking the backend team to cancel and reissue them. Both routes are now closed.

The last two are about identity. When the pump is first set up, the server permanently assigns it
an ID and expects the pump to remember a second ID it makes up for itself. Neither was being kept.
Both are now stored properly and, importantly, they survive the kind of maintenance — resetting the
app's saved credentials — that would otherwise have made the pump unable to prove who it was, with
no way to recover.

Verified on the actual tablet, not just on the laptop.

**Technical notes:**
- **#11 envelope (critical).** `ApiEnvelope<T>(status, message, data)`; every `PumpApiService` method
  now returns `ApiEnvelope<T>` and `PumpApiClient` calls `unwrap()`. `status:false` (or `status:true`
  with absent `data`) throws `EnvelopeFailureException` → `ApiError.Business(message, httpCode)`,
  **not retryable**, so the idempotent upload cannot hammer a considered refusal. All success
  fixtures rebuilt verbatim from §4.1/§4.2/§4.3; `PumpSigningInterceptorTest`'s three fixtures went
  red the instant the shape was corrected — which was the point. Added a regression test asserting
  the *old* unenveloped shape now fails as `ApiError.Serialization`.
- **Reading the primary doc:** the PDF has no text layer this machine can extract (no poppler/pypdf),
  so the literal JSON was recovered by decoding the PDF's Flate streams and ToUnicode CMaps directly.
  Envelope + all three `data` shapes confirmed field-by-field. The same pass extracted the full error
  catalogue, which the audit had only sampled — it now feeds #14/#15.
- **#12 secret logging (security).** `PumpLoggingInterceptor` replaces raw `HttpLoggingInterceptor`;
  body logging is an **allowlist** (`/authorise`, `/config`, `/transactions/upload`,
  `/transactions/{id}`), so `/activate` — and any endpoint added later, e.g. the credential rotation
  anticipated in OQ #8 — drops to `HEADERS`. Allowlist over denylist deliberately: forgetting to
  update it yields thinner logs, not a leaked secret. Second door: `PumpCredentials` and
  `ActivateResponse` are data classes whose generated `toString()` printed both secrets in full — a
  route the audit missed, closed by redacting both. 12 tests assert on what was actually **written**
  (real OkHttp stack + MockWebServer + collecting logger) rather than on interceptor configuration,
  which is precisely what was wrong before.
- **#13 `pumpId`.** Required (not defaulted) on `PumpCredentials` and `StoredCredentials`, so
  activation code cannot construct credentials without it — that *is* the enforcement, since there is
  no activation flow yet (#8) to remember to do it. Collision resolved by renaming the *other* one:
  `DeviceConfig.pumpId` → `pumpLabel`; Room column preserved via `@ColumnInfo(name = "pumpId")` →
  **no migration** (`identityHash` unchanged at `2c9cd927…`). Stored blob versioned `v: 2`; a pre-#13
  blob is purged rather than partially read — defaulting to `""` would decode cleanly and then earn
  an opaque `401 pumpId does not match authenticated device` in the field, where "not activated" is
  the honest, recoverable answer. Nothing real was purged; no device has activated.
- **#16 `deviceId`.** `DeviceIdProvider` (domain seam) + `PersistentDeviceIdProvider` mint a random
  UUID once and never re-mint. **Departs from the audit on storage:** kept in its own *plain* prefs
  file, not the encrypted credentials blob. The deviceId is not secret (it goes out as `X-Device-Id`),
  and the encrypted store deliberately drops its blob on KeyStore invalidation or corruption — so
  storing identity there would silently mint a new deviceId on the next boot, reintroducing the exact
  unrecoverable failure the issue exists to prevent. A separate file also survives credentials
  `clear()`. Two unnamed hardenings: a failed write **throws** rather than returning an
  in-memory-only id, and `activate()` sources the deviceId from the provider instead of taking it as
  a parameter, so no caller can supply an ad-hoc one.
- **Verification.** JVM suite **107 tests / 15 classes**, 0 failures/errors/skips (81 → 107 across
  the batch); `compileDebugRealHwKotlin` clean. **Instrumented on the SM-T220 (Android 14):**
  `connectedDebugAndroidTest` = **8 tests, 0 failures/errors/skips** — the 2 new deviceId tests
  (persists across a fresh instance; credentials `clear()` does not change it) plus
  `KeystorePumpCredentialsStoreTest` now at **6** (was 5 at gate #10), the added case proving the
  `v: 2` legacy-blob purge against real KeyStore crypto. Gradle was pinned with
  `ANDROID_SERIAL=R83WC02H90E` — the tablet enumerates twice (USB + wireless adb) and would
  otherwise have run the suite against the same physical device twice.
- **Standing lesson, restated:** where a spec exists, build fixtures from its literal examples. Had
  `PumpApiClientTest` done that in July, #11 would never have shipped.

**Next:**
Three audit items remain, none of them code-blocked in the way #11 was. **#14** (parse the envelope
out of 4xx error bodies, fill in `httpCode`, map known messages to attendant copy) is blocked on
*copy*, not parsing — there is no error screen in `docs/Strict design screens/` and OQ #17 is open.
**#15**'s mapping half is ready and rides on #14, but its enforcement half has no home: the app is
not a device-owner app (kiosk lock-task still deferred), so it cannot set the clock — only read
`Settings.Global.AUTO_TIME` and warn. **#18**'s backend asks remain the critical path by lead time,
still unsent, and now carry a fifth: request **stable error codes** alongside `message`, since
matching on interpolated human strings ("Amount mismatch for PETROL…") breaks silently on a reword.
Otherwise: 7b operator config (the device-local screen doubles as the `/config` fallback) and the
payment feature flows (#8).

---

### Phase 7b (first half) — device-local operator config: fuel type + price
**Date:** 2026-09-02
**Status:** done
**Commit(s):** `05556c1` (schema v3 + migration test), `37388c5` (guard), `af43918` (operator screen); branch `feature/phase-7b-operator-config`

**Summary (plain language):**
Until now the pump had no way of knowing which fuel it sells. That sounds absurd for a fuel pump, and
it is — but the reason is real: the backend never tells it. Every sale has to declare a fuel type,
and nothing in their API supplies one. We asked for it (it's the headline item in the message going
to the backend team), but their build time is not something we control, so this phase gives the pump
a way to be told locally.

There is now a **pump settings screen**. A manager opens the attendant panel with the usual PIN, taps
"Pump settings", and chooses the fuel and the price. The screen says plainly whether the pump can
currently take sales, and if not, exactly what's missing.

Two things about it are deliberately awkward. It opens **blank** on a new pump rather than filled in
with sensible-looking numbers, and it makes you **pick** a fuel rather than pre-selecting petrol.
Both are because a filled-in field doesn't invite you to check it — and the mistakes here are the
expensive kind: a pump quietly selling at a leftover demo price, or a diesel pump billing as petrol.
A pump that isn't configured now refuses to sell and says *"Fuel parameters not set — please see
attendant"* rather than guessing.

While doing this we found that the app was seeding itself a demo price of ₦870/L on **every** build,
including the production one — so the "no price set" safety check could never actually fire on a
real pump. That's now fixed; a real pump starts genuinely blank.

This is not throwaway work. When the backend endpoint eventually ships, this screen stays on as the
manual override and as what the pump falls back to when the backend is unreachable.

**Technical notes:**
- **`FuelType` moved** from `data/network/dto/PumpApiDtos.kt` to `domain/model/`. `DeviceConfig` needs
  it, and leaving it in the DTO file would have made the domain layer reach into a transport type. It
  keeps its `@SerialName` wire strings rather than being mirrored by a parallel enum + mapper — the
  backend's vocabulary *is* the domain vocabulary here, so the second enum would guard nothing.
- **`DeviceConfig.fuelType: FuelType?`** — nullable on purpose. An unconfigured pump genuinely has no
  answer, and defaulting to `PETROL` would let a diesel pump authorise against the wrong fuel *and*
  the wrong price. Null blocks instead.
- **Schema v2 → v3** — `ALTER TABLE device_config ADD COLUMN fuelType TEXT DEFAULT NULL`. Add-column
  only, so the transaction audit log, identity row and PIN hash are untouched. Existing rows migrate
  to NULL rather than a back-filled guess. Unrecognised stored values decode to null (not a crash) so
  a backend enum rename can't take a pump down mid-shift.
- **First migration test in the project** (`SmartPumpMigrationTest`, 4 cases) — this carries out step
  4 of the workflow documented at the top of `SmartPumpMigrations.kt`, which had never been done
  because no migration existed to test; `room.testing` was already wired for it and had been sitting
  unused. The v2 fixture is **literal SQL, not Room-built**: the entities now describe v3, so a
  fixture derived from current classes would drift with them and stop representing what is actually
  installed on a tablet. Covers a configured row surviving, an empty DB, the audit/identity tables
  being left alone, and a fuelType write round-tripping.
- **Guard** — `CanStartTransactionUseCase.Result.PriceNotSet` became `NotConfigured(missing: Set<Missing>)`.
  The split is deliberate: the **operator** screen needs to know *which* field is absent to highlight
  it (and an unconfigured pump reports *both*, not an empty set, which would read as "nothing
  missing"); the **customer** gets one message for every case, since they can't act on the difference
  and the fix path is identical. That single message is pinned by a test so the two cannot drift into
  separate wording. Copy: *"Fuel parameters not set — please see attendant."*
- **Entry point is a chip in the attendant panel's chrome row, not a fourth action card.** The three
  cards are fixed by `flows.md` and the strict-design screens ("three actions, never more"), so the
  settings control sits beside DISMISS, which is already a non-action control. **Deviation flagged:**
  no operator/settings screen exists in `docs/Strict design screens/` — the screen is built from
  `design-system.md` tokens and existing components.
- **`seedDefaultConfigIfMissing()` gated to debug builds** — which is what `CustomerViewModel`'s
  header comment has always claimed. It ran in every build type, so a fresh *release* install seeded
  itself ₦870/L and "Total Lekki Ph2", the price guard could never fire in production, and the new
  settings screen would have opened pre-filled with a plausible price.
- **Test harness caught a trap:** `FakeDeviceConfigRepository`'s default config had no `fuelType`, so
  adding the guard condition silently turned every existing flow test into a not-configured
  assertion. Default now includes one.
- **Verification.** JVM **125 tests / 17 classes**, 0 failures/errors/skips (114 → 125 across the
  phase); `compileDebugRealHwKotlin` clean; **instrumented 12 tests green on the SM-T220** (Android
  14) — 4 migration + 6 Keystore + 2 deviceId. Gradle pinned with `ANDROID_SERIAL` (the tablet
  enumerates twice over USB + wireless adb).
- **Accepted risk, recorded in OQ #19:** the settings screen is behind the *same* shared PIN as the
  authorise actions, so any attendant who can authorise a sale can change the price. Accepted for V1
  over inventing a second PIN outside the agreed model; revisit with role-based PINs in V2.

**Next:**
7b's second half (`GET /config` sync) stays blocked on the backend — it's item 1 of the message in
`BOSS_CONFIRMATIONS_DRAFT.md`, still unsent and still the critical path by lead time. Unblocked
alternatives: 7e backend sync (audit-log upload via WorkManager, self-contained), or #14/#15 once
attendant error copy exists (OQ #17).

---

### Phase 7b (first half) — merged to `main`
**Date:** 2026-09-03
**Status:** done
**Commit(s):** `0cfba90` (merge); branch `feature/phase-7b-operator-config`

**Summary (plain language):**
The pump settings work merged into the mainline. A manager can now set which fuel the pump sells
and its price on the tablet, behind the attendant PIN, and a pump that hasn't been configured
refuses to sell rather than guessing. This existed because the backend never tells the pump its
fuel type, yet every sale has to declare one.

**Technical notes:**
- No-fast-forward merge, verified on `main` after merging: **125 JVM tests / 17 classes**, 0
  failures/errors; `compileDebugKotlin` and `compileDebugRealHwKotlin` both clean. 12 instrumented
  tests were green on the SM-T220 before the merge (4 migration + 6 Keystore + 2 deviceId).
- Carries `DeviceConfig.fuelType` at schema v3 with the project's first Room migration and first
  migration test, the `NotConfigured(missing:Set<Missing>)` guard split, the PIN-gated operator
  screen, and the fix for `seedDefaultConfigIfMissing()` running in every build type.
- Also carries the `PULSES_PER_LITRE` collapse from two definitions to one
  (`domain/hardware/MeterCalibration.kt`).
- `main` = `origin/main` = `0cfba90`, pushed.
- **Accepted risk (OQ #19), unchanged:** the settings screen sits behind the same shared PIN as the
  authorise actions, so any attendant who can authorise a sale can change the price. Role-based
  PINs stay V2.

**Next:**
Friday 2026-09-04 live meter trial on `feature/phase-7g-eeprom-totaliser`. That trial is also the
merge gate for 7g, whose EEPROM totaliser has never been verified on hardware. After it: OQ #25
(pulses counted while the tablet is down are silently absorbed — live on `main`), then the spec's
payment-direction conflict (`DELTA-04`/`DELTA-05`), which nobody has examined yet.

---

### Phase 7g (docs + app-side) — branch split, firmware half held back
**Date:** 2026-09-07
**Status:** partial (deliberately — the firmware half is unmerged by design, not unfinished)
**Commit(s):** `4dee113` (merge); `6df3688`, `e66dfec`, `8c89239` on `merge/phase-7g-docs-and-app`

**Summary (plain language):**
The 7g branch had grown into two very different things: a pile of documentation and one small app
change, sitting on top of new Arduino firmware that has never been run against real hardware. Rather
than merge all of it or none of it, the branch was split. Everything that could be checked on this
machine is now on the mainline — the specification extract, the calibration run sheet, the open
questions, and the fix that lets the meter constant hold a decimal. The firmware stays on its branch
until somebody power-cycles the board and confirms the pulse memory actually survives, which is the
one claim no amount of compiling can support.

This also corrected a stale belief: Phase 7b was recorded on the branch as "merge-ready", but it had
already been merged four days earlier. The branch's copy of the work board was simply older than the
mainline's.

**Technical notes:**
- **The split is path-scoped, not commit-scoped.** Two commits (`7d9113b`, `bfc7de9`) touch both
  `MeterCalibration.kt` and the `.ino`, and the firmware commit `4072b7c` also edits `TODO.md`, so no
  commit boundary separates the halves. Applied via `git merge --squash` then restoring
  `hardware/**` to `main`'s state.
- **Deliberately not a merge commit from the 7g branch.** Recording that merge would mark the five
  firmware commits as already merged, and Git would silently skip them when the branch lands for
  real. `feature/phase-7g-eeprom-totaliser` is untouched and still carries all 18 commits.
- **App-side:** `PULSES_PER_LITRE` becomes `100.0` (Double). Held as an `Int`, rounding a measured
  K-factor costs up to 0.5 pulses — at ~100 pulses/L that is 0.5% error before the meter is
  involved, i.e. the entire TEST-01 tolerance spent on a type declaration.
  `CustomerViewModelDispensingTest` no longer hardcodes pulse counts (380 for 3.8 L), which had
  pinned `PULSES_PER_LITRE = 100` into assertions that are not about the K-factor.
- **Docs landed:** Prototype Specification v1.0 extract, `FIELD_RUN_SHEET_2026-09-04`,
  `BRANCH_7G_SUMMARY`, the OPEN_QUESTIONS index + #26, and the re-argued boss-draft item 1.
- **Trap flagged in-repo:** `hardware/*.ino` and `hardware/README.md` on `main` are still the
  pre-7g versions while the docs beside them describe the merged sketch. Anyone flashing from `main`
  would get firmware predating the four defect fixes. Banners added to `BRANCH_7G_SUMMARY.md` and
  the `TODO.md` 7g section.
- **Verified before merge:** 125 JVM tests / 17 classes, 0 failures/errors/skips;
  `compileDebugKotlin` + `compileDebugRealHwKotlin` clean.
- **Friday 2026-09-04 left no result in the repo** — no run-sheet entries, no new logcats, no log
  entry. The blocker recorded against it (meter output type and voltage, owed by Kelvin) was still
  open at the last commit. The 7g merge gate has therefore not moved.

**Next:**
Close the 7g gate — either the live meter trial, or the five-minute bench check in
`BRANCH_7G_SUMMARY.md` (note the `BOOT` count, run one dispense, power-cycle, confirm the count came
back higher *and* that the next sale still starts from zero litres on the tablet). Then merge the
firmware half. Independently of the board: OQ #25 (pulses counted while the tablet is down are
silently absorbed — live on `main`) and the #18 backend asks, still unsent and still the critical
path by lead time.

---

### Phase 7h — Pulse continuity across restarts (fuel counted while the app was down)
**Date:** 2026-09-11
**Status:** partial — built and merge-ready bar one gate; never run against a real board
**Commit(s):** `66fd353` (schema v4), `30872d3` (adapter-count seam), `d9da72f` (reconciler),
`d60483f` (boot resume), `51f0ce0` (operator fuel log); branch
`feature/phase-7h-pulse-continuity`, **unmerged**

**Summary (plain language):**
Until now, if the tablet ever stopped — a crash, an Android update, a flat battery — while fuel was
flowing, the app came back and quietly forgot the fuel that went into the customer’s tank in the
meantime. Roughly a litre and a half, every time, delivered and charged to nobody. The pump’s little
adapter board kept counting it the whole while; the app just threw the number away.

It no longer does. The app now remembers where the board’s counter stood, and on restart works out
exactly how much fuel went past while it was blind. Where it can prove the figure, those litres go
onto the customer’s sale. Where it cannot — because the board lost power too, or the cable is out,
or the amount is too large to have come from one interrupted sale — it refuses to guess and writes
an entry to a new **Fuel log** on the operator screen, in plain language, behind the attendant PIN.

One safety case is worth spelling out. If the recovered fuel turns out to have already exceeded what
the customer paid for, the pump does **not** start again. It closes the sale then and there, and the
record shows more litres delivered than were charged. The station absorbs that, which is the right
way round: every error in this path runs in the customer’s favour, which is also why a refund was
considered and rejected as the wrong tool here.

**Technical notes:**
- **The enabler was already on the wire.** The adapter puts its free-running cumulative in the
  **~2 s `HB` keep-alive**, not only in `PULSE`. `SerialFrameParser` has parsed it since 7a;
  `UsbSerialPulseSource` discarded it when mapping `HB` → `PulseMessage.Heartbeat`. So the count is
  readable **while the pump is idle, without opening the relay and with no protocol change** — this
  phase needed nothing ratified by Olonade, which is why it could proceed at all.
- **Schema v4** (`66fd353`): `pulse_state.adapterCount` (the anchor), `transactions.recoveredLitres`,
  and a typed `events` table. The anchor is **nullable and null is never zero** — zero is a real
  reading from a board that just booted, so a back-filled zero would invite the reconciler to
  attribute the adapter’s entire lifetime count to one customer. `recoveredLitres` is the opposite
  call (NOT NULL DEFAULT 0) because "no recovery was applied" is simply true of every prior sale.
  `events` is typed rather than gap-specific so `PWR-03` power events need no second migration.
- **Tracking lives in the hot read loop** (`30872d3`), not the cold per-dispense flow — the count is
  needed precisely when nothing is dispensing. `BOOT` updates it too, so a reader can see the count
  go backwards and conclude "restarted" rather than subtracting from a stale high-water mark.
  `handleDetach()` clears it to null: after a dropped link, the last value seen is not evidence.
- **`ReconcilePulseGapUseCase`** (`d9da72f`) is pure — no Android, no coroutines — with 16 tests
  covering the decision table **and the precedence between refusals**. Silence outranks a missing
  anchor, because sending someone to inspect a database column when the fault is an unplugged cable
  wastes the one person who could fix it.
- **The ceiling is in pulses, not litres** (`MAX_PLAUSIBLE_GAP_PULSES = 400`). Litres run through the
  unmeasured `PULSES_PER_LITRE`; written in litres the bound would silently change meaning at
  calibration, in the direction of accepting larger gaps. Its derivation has **two** terms and the
  easy one to miss is the first: up to `PULSE_PERSIST_EVERY_N` (25) pulses of ordinary in-sale flow,
  because the anchor is only written every 25th pulse — so **the same subtraction also recovers the
  second, smaller leak OQ #25 describes**, for free. Cross-referenced at both constants.
- **A real defect surfaced in testing** (`d60483f`): correcting `pulseBaseline` was not enough, since
  the dispatched state still carried the stale `litresSoFar`, so a resumed screen briefly showed the
  pre-outage figure. `resumedLitres()` overrides it **only** when recovery added pulses — the two
  sources are stale in opposite directions, and a recovered gap spans exactly the pulse-count write
  lag as well as the outage, making it authoritative precisely when it exists.
- **An idle boot is silent**: no anchor and no sale in flight means nothing could have been missed,
  so there is no adapter wait on a cold start and no event on every launch burying the real ones.
- **Deviation flagged:** the Fuel log card has no counterpart in `docs/Strict design screens/`, same
  as the operator form it sits on. Built from design-system tokens and existing components.
- **Verified:** JVM **162 tests / 21 classes**, 0 failures/errors/skips (125 → 162); **16
  instrumented green on the SM-T220** (12 → 16 — four new migration tests, including a chained
  v2→v4 that proves a tablet which skipped a release keeps its config, PIN hash and audit log);
  `compileDebugKotlin`, `compileDebugRealHwKotlin`, `lintDebug` clean.

**Not verified — the merge gate (TODO #27):**
No part of this has met a real Arduino. Everything is proved against fakes and a migration helper.
The Fuel log card has compiled and linted but **has never been rendered on a device**. The bench
checklist is in `TODO.md`; its decisive step is one observation — kill the app mid-dispense, relaunch,
and see the resumed screen show *more* litres than it did at the kill.

**Also left open:**
- The **adapter-down** case stays unrecoverable and is reported honestly as unexplained. Closing it
  needs the **7g EEPROM totaliser** (written, never flashed, held off `main`); the reconciler’s
  refusal branch is the seam it plugs into.
- `MAX_PLAUSIBLE_GAP_PULSES` wants re-deriving at T-01 (TODO #28).
- The `events` rows have **no backend endpoint** — a fifth #18 ask, not previously on that list
  (TODO #29).

**Next:**
The bench run. Then merge, and back to the #18 asks, which remain the critical path by lead time.

---

### Phase 9 — first contact with the real backend
**Date:** 2026-09-12
**Status:** done
**Commit(s):** `f77cfb3` (probe evidence) / `4970c4e` (error-envelope parsing, TODO #14 half) /
`5a378fe` (activation persistence) — branch `feature/api-live-probe`, off `main` at `3aea28c`

**Summary (plain language):**
Until today nothing in this project had ever spoken to a real Balancee server. Every test passed
against a pretend server we built ourselves from the API document, which is exactly the arrangement
that let a serious bug through in August. We sent five real requests to the development server —
carrying no passwords and unable to spend anything — and learned three useful things. Both of the
new endpoints we asked the backend team for are built and running. The names we use to identify
ourselves in every request are correct. And we captured what a real error from the server actually
looks like, which the document describes but never shows.

We then fixed the two things that finding out made possible. The app now understands the server's
refusals ("out of stock", "amount mismatch") instead of treating them as an unreadable blob. And the
one step that can never be repeated — redeeming the pump's activation code — now actually keeps what
it is given. Before today it would have thrown the pump's permanent keys away and left the code
spent.

**Technical notes:**
- **The probe.** Five unauthenticated requests against `api.dev.balancee.app`, captured verbatim in
  `docs/api-probes/2026-09-12/` with a re-runnable `probe.sh`. No credentials exist to send and
  none were sent; the `/activate` probe carries `{}`, so it cannot redeem a code.
  - **Both new endpoints are deployed** — items 1 and 2 of `BOSS_CONFIRMATIONS_DRAFT.md`.
    `X-Matched-Path` settles it past the status code: `/api/pump/config` and
    `/api/pump/transactions/[id]`. The control request proves the inference: an undeployed route
    returns an HTML 404 with `X-Matched-Path: /404`, so a JSON envelope is a real handler answering.
  - **Our four signing header names are correct.** Sending `X-Api-Key` / `X-Device-Id` /
    `X-Timestamp` / `X-Signature` moves the server off `Missing pump authentication headers` and
    onto `Invalid API key`. Previously this was only our reading of Reference §3.
  - **The literal failure envelope, first sight.** `{"status":false,"message":…}`, `data` absent.
    §1 describes it; the document never prints one.
  - **A top-level `code` exists on one path.** The 400 from `/activate` returns
    `"code":"INVALID_REQUEST"` as a sibling of `message`, **not** inside `data`. That is the stable
    error code asked for in draft item 3 — so the ask was partly built, and the remaining gap is now
    specific rather than general.
  - **What the probe cannot reach.** The server validates the API key *first*: a two-hour-stale
    `X-Timestamp` and a request with `X-Signature` removed entirely both return `Invalid API key`.
    So GET signing and the 5-minute freshness window (#15) are untestable from outside, and sit
    behind activation along with the `/config` payload shape, the decimals question and the full
    status set.
- **TODO #14, parsing half (`4970c4e`).** `safeApiCall` now reads the envelope back out of a non-2xx
  body and returns `ApiError.Business(message, code, httpCode)`. Parsing is conservative: only a
  JSON object with a real boolean `status:false` counts, so an HTML 404, a plain-text 502 and a 4xx
  whose envelope claims success all stay `ApiError.Http` with the bytes intact — a deployment
  mistake must not read as the server declining a sale. Retryability is unchanged. Every fixture is
  a byte-for-byte copy from `docs/api-probes/`. One existing test *changed* rather than being added
  to: it asserted the old opaque-blob behaviour.
  - **The mapping half is still blocked**, on copy rather than code: no error screen exists in
    `docs/Strict design screens/` and OQ #17 is open.
- **Activation persistence (`5a378fe`).** `PumpActivationRepository` + impl. `PumpApiClient.activate()`
  existed and **nothing called it** — it returned the credentials and no caller saved them, so
  running it would have spent the single-use code, marked the pump activated server-side and dropped
  the once-only `apiKey`/`signingSecret`. The call, the save and a **read-back** are now one
  operation.
  - **The read-back is the point.** `save()` returning without throwing proves only that nothing
    escaped, and the Keystore store deliberately discards an undecryptable blob — so a write that
    produced one would look exactly like success. Write retried once: giving up on a transient
    failure costs a revoke-and-reissue.
  - **Outcomes separate three things a caller must not confuse.** `Refused` = nothing issued, try a
    fresh code. `Unreachable` = **unknown, not "no"** (a timeout or 5xx can land after the server
    committed) → ask the backend whether this deviceId activated before burning a second code.
    `CredentialsLost` = the server activated us and the answer did not survive, *including* a
    success body we could not parse — deliberately not softened into "retry".
  - **Two guards.** Activating an already-activated device is refused locally and never sent (a
    valid second code would succeed and overwrite, abandoning the `pumpId` the backend holds). And
    the `deviceId` echo is checked — a mismatch **keeps** the credentials (they are the
    irreplaceable half, and the server's own id is what it authenticates) but reports the
    disagreement, which nothing downstream could otherwise detect.
  - No secret appears in any outcome message.
- **Verified:** JVM **155 tests / 19 classes** green (125 → 155); `compileDebugRealHwKotlin` and
  `lintDebug` clean. Nothing device-specific here, so no instrumented run was needed.

**Next:**
The activation code is the gate on everything left. With it: redeem once on dev, capture `/config`'s
literal payload and build its fixture from those bytes, then settle GET signing, clock skew, the
decimals question and the full status set in the same sitting. Ask the backend two things first —
are dev codes re-issuable, and can a dev pump be reset and re-activated — since that decides whether
this stays a one-way door. Independently: the 7h bench gate and the 7g firmware gate, both of which
need only the Arduino.

---

### Phase 9b — the V1 gaps that were tracked nowhere, plus error copy
**Date:** 2026-09-12
**Status:** done (signing partial by decision)
**Commit(s):** `68c7107` blocker inventory / `9ab8f58` release signing / `5e92572` release doc /
`e250849` receipt sharing / `d23db1a` + `94bda66` board / `b65cd5c` + `c2c62f9` + `16d4495` +
`99f6e66` + `6a055e1` error copy — all on `feature/api-live-probe`

**Summary (plain language):**
Asked what was still standing between the app and V1, and two answers turned out to be written down
nowhere. The app could not produce an installable release build at all, because it had no signing
setup — that would have been discovered on the day someone tried to put it on a station tablet. And
the Share button on the receipt screen did nothing; it had been an empty function since Phase 3.
Both are now fixed. We also settled how the pump talks to people when something goes wrong: the
customer gets one plain sentence they can act on, and the technical explanation goes to the
attendant panel behind the PIN, where somebody can actually use it.

**Technical notes:**
- **`V1_BLOCKERS.md`** — a new view of the same work the board tracks, sorted by *who is holding it
  up* rather than by phase. Points at TODO/OQ numbers and deliberately does not restate them.
- **TODO #34 release signing — build side done, keystore DEFERRED TO LAST by decision.** There was
  no `signingConfig` at all and `release` still carried the scaffold's `versionCode = 1` /
  `versionName = "1.0"`. Credentials now come from a gitignored `keystore.properties` or four
  `SMARTPUMP_*` env vars; absent credentials leave release **unsigned rather than failing
  configuration**, so a fresh clone and CI still work, with a loud warning and `docs/RELEASE.md`
  making `apksigner verify` mandatory. Verified: `assembleRelease` emits `app-release-unsigned.apk`.
  - **Deferred because it is not on the critical path** — signing gates the *parallel run*, which
    gates on the K-factor, which waits on Kelvin. And **key custody is the boss's call**, with a
    prior question worth asking: does Balancee already have an Android signing key?
  - **Recorded: a debug build cannot stand in for the parallel run.** It seeds its own price/fuel
    via `seedDefaultConfigIfMissing()` (true for `debugRealHw` too — it is `initWith(debug)`),
    exposes the long-press debug hotspot with live price editing and payment force-resolve, points
    at the dev backend, and installs under a different application id. Later demonstrated
    accidentally: a unit test could not simulate an unconfigured pump with a null config, because
    `BuildConfig.DEBUG` is true under test and the seed fired.
- **TODO #35 receipt sharing — DONE.** `onShareReceipt()` was empty while the button was live.
  Plain text through the Android system share sheet (OQ #14). The record is **re-read from the audit
  log**, not rendered from screen state, so a receipt shared after a power-cut resume is not dated
  "now" — needed a new `TransactionRepository.getTransaction(id)` + DAO query, falling back to
  screen state because `saveTransaction` is best-effort. Month names are **pinned in code**: `MMM`
  under `Locale.UK` renders "Sept" on a modern JVM and "Sep" elsewhere, and the JVM's CLDR data is
  not Android's ICU data.
- **OQ #17 error copy — SETTLED, all five review items.** Drafted as
  [`ERROR_COPY_DRAFT.md`](ERROR_COPY_DRAFT.md) so it could be decided by reviewing words rather than
  answering an abstract question. **Design-authority flag stands on record: there is no error screen
  in `docs/Strict design screens/`**, so both the words and the layout are a deviation.
  - **The split:** customer gets one plain line ("…please see attendant"), diagnostics go to the
    **swipe-up attendant panel** — already behind the PIN, already what an attendant opens, and the
    PUMP SETTINGS button that fixes most cases is in the same chrome row.
  - `TransactionState.Error` gains `attendantDetail`. **No Room migration**: state persists as
    kotlinx JSON in one column, and a new field with a default decodes from older rows.
  - **`recoverable` is finally read.** It was carried on every error and used by nothing, so a dead
    end looked identical to a retry. Gold vs red, reusing the app's existing colour vocabulary; the
    button's *action* is unchanged, since there is no retry in the state machine.
  - Fixed two leaks onto the customer display: the payment processor's raw reason and the USSD SMS
    parser string. Fixed two wordings for one condition — the missing-field copy now lives on
    `CanStartTransactionUseCase` and is shared with the operator screen.
  - 6 new tests assert **the split, not the prose**: no naira figure, no gateway error on the
    customer line.
  - **Catalogue A (server errors) is written but NOT wired** — nothing receives an `ApiError` until
    the payment flows (#8) exist. Wiring it now would carry text nothing reads.
- **Verified:** JVM **170 tests / 21 classes** green (155 → 170); `compileDebugRealHwKotlin`,
  `lintDebug` and `assembleRelease` clean. No instrumented run needed — nothing device-specific.

**Next:**
Three unblocked candidates, none needing the rig, the backend or the boss: an **activation step in
onboarding** (the `PumpActivationRepository` built earlier today has no caller, so an arriving code
could not actually be redeemed by an operator); the **transaction upload job** (7e — needs
`workmanager` re-added and a `markSynced` path, neither of which exists); and a **draft of the
OQ #22 options**, the last open decision. Everything else waits on the bench rig, Kelvin, Olonade or
the activation code.

---

### Phase 9 line — merged to `main` on top of 7h
**Date:** 2026-09-15
**Status:** done (not yet pushed)
**Commit(s):** `553a049` merge / docs follow-up on `merge/activation`, fast-forwarded to `main`

**Summary (plain language):**
Two lines of work had been built side by side without seeing each other: the fix for fuel counted
while the app was restarting (7h), and the first real contact with the Balancee server plus the
screen where an installer types the pump's activation code (Phase 9). Both are now in the main copy
of the app, and the full test suite and release build pass with both in. When the activation code
arrives, it can be entered from the operator settings screen in the build on `main`.

**Technical notes:**
- Both branches started at `3aea28c`. The merge was done on a throwaway branch cut from `main` and
  only fast-forwarded once green, so `main` was never in a half-merged state.
- **Conflicts, all additive:** `DatabaseModule` (two new repository bindings), `CustomerViewModel`
  (two imports), `OperatorConfigScreen` (activation panel placed under "Save settings", fuel log
  below it — an ordering choice, no design screen exists for either), `PROJECT_LOG` and `TODO`.
- **Verified on the merge commit:** JVM **224 tests / 26 classes**, 0 failures; that is 165 (7h) +
  59 (Phase 9), so no test went missing. `compileDebugRealHwKotlin`, `lintDebug`, `assembleRelease`
  clean. No instrumented run: neither side's device-specific code changed in the merge.
- **#37 confirmed after merging:** shared receipt prints the struck price, screen prints amount ÷
  litres. Recorded, not fixed here.
- **Housekeeping:** the Phase 9 TODO had two items numbered #33; the `probe.sh` one is now **#39**.
  `V1_BLOCKERS.md` refreshed: 7h and Phase 9 moved to merged, #27 ticked, #37 added as movable.

**Next:**
Push when approved. Then #37 (small, needs nobody), the 7g bench gate (#19), and the activation code
on dev (#31 questions first, then #32).

---

### OQ #22 — End sale early, for fixed sales that will not reach their target
**Date:** 2026-09-15
**Status:** done (not yet run on the tablet)
**Commit(s):** on `feature/oq22-end-sale` — see `git log`

**Summary (plain language):**
A pre-paid or cash-fixed sale used to have exactly one way to finish: pumping every litre paid for.
If the customer's tank filled first, or the link to the pump adapter dropped for good, the screen
waited forever, and even restarting the tablet brought the same stuck sale back. The attendant can
now end it from the swipe-up panel. The app stops the fuel, records the litres that actually flowed
against the amount the customer paid, and tells the customer to see the attendant about the
difference.

**Technical notes:**
- **Decision:** Option 1 of `OQ22_OPTIONS_DRAFT.md`. No automatic timeout: a no-flow timer cannot
  tell a full tank from a pause, and cutting off a paid sale by mistake is worse than a stuck screen.
- **The case the question missed:** OQ #22 named a permanent link loss, which is rare under the
  fixed-cable assumption. A tank that fills before the target is routine and strands the sale with
  the relay still commanded on. Both corrections to the OQ text — no attendant exit existed, and a
  power cycle does not clear it — were found in the code, not assumed.
- `CustomerViewModel.onAttendantEndSaleEarly()`: relay off, collector cancelled, **then** the state
  re-read, so a pulse in flight lands in the record or not at all, and a sale that hit its target in
  that window completes normally instead of ending twice.
- `TransactionState.Complete.litresTarget: Double? = null` — set only on an early end. Defaulted,
  so persisted rows from earlier builds still decode (tested against a literal legacy JSON). The
  audit note is `Ended by attendant at X of Y L`; amount stays what was paid.
- UI: a full-width gold button in the slot "End fill-up" already uses, not a fourth card
  (`flows.md` fixes three). Completion screen adds *"Sale ended early — X of Y L. Please see the
  attendant."* ⚠️ **No design screen covers either** — deviation on record.
- `state-machine.md`: early-end transitions added to Flows 1 and 4; the Universal row that sent a
  disconnect to `Error(recoverable=true)` is corrected — that would have written no audit row.
- **Not done:** the shared receipt text does not mention the early end, so it shows ₦ paid against
  fewer litres with no explanation. `Transaction` has no target field and printing the free-text
  `attendantNote` to customers would leak future notes. Small follow-up if wanted.
- Tests: 7 new in `CustomerViewModelEndSaleEarlyTest` (pre-pay, cash-fixed, late pulses ignored,
  stuck sale restored after a power cycle, no-op outside a fixed sale, normal completion unchanged,
  legacy decode). JVM **232 / 27** green.

**Next:**
Try the button on the tablet with the rig (a pre-pay stopped short by holding the nozzle). Then
#40 before any parallel run, the 7g bench gate, and the activation-code line.

---

### Pre-gate — the activation code arrived, and it is a production one
**Date:** 2026-09-16
**Status:** done (investigation + evidence; no app code changed)
**Commit(s):** see below

**Summary (plain language):**
The activation code we have been waiting on since July finally arrived — but it turns out to belong to
the **live** system, not the test one. That matters because the plan for using it was a seven-step
run-through that deliberately creates a few junk transactions to see how the server answers; doing
that on the live system would put fake sales into the station's real records. So the code has not been
used. Instead we checked, without sending anything secret or using the code, that the live server
really does speak the same language our app was built for — and it does, exactly, down to the byte. We
have written the request for a test-system code and recorded what we found.

Two other things came out of it. The app as built cannot reach the live server at all except through a
release build, which does not exist yet. And of the seven steps in the plan, only the first has a
button anywhere in the app — the other six call functions nothing presses. A small debug-only panel
has to exist before the code, whichever code it is, is worth spending.

**Technical notes:**
- **Environment identified by devtools, not guesswork:** the dashboard at
  `smartpump.balancee.app/dashboard/pumps` that minted the code posts **GraphQL to
  `api.balancee.app`** — production.
- **New evidence directory `docs/api-probes/2026-09-16-prod/`.** `probe.sh` re-run with a base-URL
  argument against production; captures written to a **new** directory so the 2026-09-12 dev captures
  on `main` are untouched. No credentials sent (filler key, all-zero device id, `deadbeef` signature),
  activation probed with an empty body so it cannot redeem.
- **Production is byte-identical to dev** on all four bodies (`diff` clean) and every `X-Matched-Path`
  resolves to the same handler: `/api/pump/config`, `/api/pump/transactions/[id]`,
  `/api/pump/activate`, plus the `/404` HTML control. The REST pump surface is deployed on production
  and the GraphQL dashboard sits alongside it.
- **#18f is a contract gap, not a deployment gap:** the top-level `code` field is present on the 400
  and absent from all three 401s on production too, exactly as on dev.
- **Codes are re-issuable** (confirmed with the user). The "single-use, so we cannot proceed on a
  borrowed one" framing in `API_CONFORMANCE_AUDIT.md` was our own assumption without a quoted
  Reference line. A code is single-use; another can be issued. #31's one-way door is now narrow.
- **Two blockers this exposed, both recorded rather than worked around:**
  - `debug`/`debugRealHw` hard-wire `api.dev.balancee.app` via `buildConfigField`; only `release`
    points at production, and there is no signed release build. A prod code has nothing to redeem it.
  - `activate()` is the only `PumpApiClient` method with an in-app caller (`ActivationPanel` via
    `OperatorConfigScreen.kt:214`). `config()`, `authorise()`, `transactionStatus()` and
    `uploadTransaction()` are called from nowhere in `ui/`, so #32 steps 2–7 cannot be driven at all —
    and #32 requires driving them through the client rather than curl.
- **Docs updated:** `BOSS_CONFIRMATIONS_DRAFT.md` item 4 rewritten from "we need a test code" to "a
  production code arrived, can the dashboard mint a dev one", with a named read-only fallback if it
  cannot; `TODO.md` #31 (now `[~]`) and #32; `V1_BLOCKERS.md` §4.

**Next:**
Send the dev-code ask. Meanwhile build the debug-only API probe panel that drives the four uncalled
client methods, so that whenever a usable code lands the whole of #32 can be run in one sitting.

---

### Phase 9d-1 — the gate gets a driver: API probe panel + a build that can reach production
**Date:** 2026-09-16
**Status:** done (built, verified green; not yet run on the tablet)
**Commit(s):** `7e1e548` on `feature/api-probe-panel`

**Summary (plain language):**
The activation code we were given belongs to the live system, and until today nothing we could
install on a tablet was able to talk to the live system at all. There is now a third version of the
app that does — it installs alongside the other two, keeps its own login to the server, and can be
removed without disturbing them.

It also has a new panel, visible only in test builds and only behind the attendant PIN, that lets
someone press a button and ask the server a question through exactly the same code the real app uses.
That last part is the point: testing with a separate script would prove that the script works, not
that the app does. The panel shows the server's answer word for word, not our tidied-up version of
it, and can save it to a file that can be copied off the tablet.

One deliberate piece of unhelpfulness: if the server answers "OK" but we understood none of it, the
panel says so in amber rather than showing a green tick. That exact situation — everything looking
fine while the app understood nothing — is the bug that went unnoticed for two months in July.

**Technical notes:**
- **`debugProd` build type:** `initWith(debug)`, `applicationIdSuffix = ".prod"`, base URL
  `https://api.balancee.app/`. Mock hardware, so the USB port stays free for `adb` — the constraint
  that made 7h's bench session so painful. **Not a parallel-run candidate:** it is a debug build with
  everything `V1_BLOCKERS.md` says disqualifies one (self-seeding config, debug hotspot).
- **Chose a variant over a `-P` gradle property on `debug`.** The property is fewer lines and fails
  silently: the next build without the flag points production credentials at dev with nothing on
  screen saying so. A separate applicationId also isolates credentials and `deviceId`.
- **`ProbeCaptureInterceptor` + `ProbeResponseRecorder`** (`data/network/ProbeCapture.kt`): peeks
  body-safe responses into a bounded in-memory list. **Reuses
  `PumpLoggingInterceptor.bodyLoggingAllowed()`** instead of a second allowlist — one predicate, one
  place to be wrong, and `/activate` is already deliberately absent from it (#12). `peekBody`, never
  `body`, so Retrofit still receives the response: an instrument that changed the measurement would
  break every call while the panel looked healthy. Tested both ways round.
- **`ProbeCaptureFormat`**: plain text, not JSON — wrapping bodies in a JSON document would escape
  them, and the file exists to preserve bytes. Every file names its server, because this project now
  holds fixtures from dev and an activation code for production.
- **`toConfigSummary()`** is a pure function so the judgement is unit-testable without a VM, a server
  or a device. The case it exists for: `PumpConfigResponse.prices` defaults to `emptyMap()`, so a
  renamed server field parses cleanly into nothing → reported as **caution**, pointing at the raw
  bytes, not as success.
- **Not built, deliberately:** #32 steps 3–7 (`/authorise`, amount mismatch, decimal amount, status
  poll, upload). They create transactions; the only code we hold is for production.
- **Runbook:** `docs/journal/GATE_32_RUNBOOK.md` — install, activate, verify against the dashboard's
  Device ID column, restart to prove persistence, capture `/config`, `adb pull`.
- Verified: JVM **254 tests / 30 classes** green (was 232 / 27); `compileDebugProdKotlin`,
  `compileDebugRealHwKotlin` and `lintDebug` clean, no lint findings in the new files.
- ⚠️ **Design-authority flag:** no probe/settings screen exists in `docs/Strict design screens/`.
  Built from existing components and tokens, like the activation and error screens before it.

**Next:**
Run the runbook on the tablet against `SN-TEST-001`. Stage 9d-2 (the transaction-creating steps) only
once it is settled which server may be dirtied — see the ask in `BOSS_CONFIRMATIONS_DRAFT.md` item 4.

---

### The activation gate — steps 1 and 2 run on the tablet
**Date:** 2026-09-16
**Status:** partial (steps 1 and 2 passed; 3–7 not built)
**Commit(s):** `ad838f8` (two defects fixed), `d2ace9f` (capture), `1c3dc26` (DTO rebuilt)

**Summary (plain language):**
The pump is activated. It is registered with Balanceè, it kept its keys through a force-stop, and it
has successfully asked the server a question and been answered. That is the first time this app has
ever talked to a real server in its life.

Getting there took two fixes, both found by actually running it. The app had never been given
permission to use the internet — a thing no test could have caught, because the tests run on a
computer rather than on Android, where that permission does not exist. And the box you type the
activation code into was silently changing what you typed: it forced capitals and threw away
punctuation, so the correct code became a different code before it was sent.

Then the useful part. The server's answer did not match what we had been building against **at all** —
not a renamed field, a different idea entirely. We had modelled a price list for several fuels; the
server describes one pump with one fuel and one price. Our version had a default value, so the wrong
shape did not fail, it quietly said "this pump sells nothing" and everything above it would have
believed that. It has been rebuilt from the bytes the server actually sent.

The answer also settles two of the questions we were about to send to the backend team — one of them
the one we had marked as most important and most urgent.

**Technical notes:**
- **Step 1 passed.** Activated on production against `SN-TEST-001`. `pumpId`
  `3727aebf-3c77-4180-a818-4254cbeeae72`; `deviceId` `ae2b7a83-…`, matching the dashboard's Device ID
  column — the echo check in `PumpActivationRepositoryImpl` passing against an independent source.
  Credentials survived a force-stop and relaunch.
- **Defect 1 — `android.permission.INTERNET` was never declared.** Not in main, not in the debug
  overlay. Crash: `SecurityException: Permission denied (missing INTERNET permission?)` at
  `Inet6AddressImpl.lookupHostByName`, on the OkHttp dispatcher. Invisible to 254 JVM tests because
  MockWebServer runs off-device. **The code was not spent** — nothing left the device.
- **Defect 2 — `setCode` uppercased and filtered the code**, reasoning from the Reference's uppercase
  examples. The dashboard issues mixed case. It mangled silently, so the field looked right. The test
  asserting the old behaviour is reversed, not deleted.
- **`GET /config` returned 200**, and the payload bears no resemblance to `PumpConfigResponse`:
  `{"pumpId","stationName","fuelType","pricePerUnit":1490,"updatedAt"}` — one pump, one fuel, one
  price. No `prices` map. Captured verbatim at `docs/api-probes/2026-09-16-prod-config/` and rebuilt
  from there with **nothing defaulted**, so the next shape mismatch is a loud
  `ApiError.Serialization` rather than a silent zero.
- **The probe panel's "zero prices parsed" caution is what made it visible**, and it is now removed as
  impossible by construction. It existed for one day and paid for itself.
- **Two asks are now obsolete before sending.** `BOSS_CONFIRMATIONS_DRAFT.md` item 1 — "nothing in the
  API tells a pump what it sells or what to charge", marked highest and said to set the date — is
  **built and deployed**: `/config` returns `fuelType`, `pricePerUnit` and `stationName`. And the
  GET-signing question in item 3 is answered by the 200 itself: `timestamp + "." + ""` is verified.
- **`pricePerUnit: 1490` is naira by inference**, not by contract — corroborating TODO #17, which was
  our own call. #18c still deserves its one line.
- **New #42:** a `RuntimeException` in the OkHttp chain kills the process (AsyncCall rethrows after
  `onFailure`), which is how a missing permission became a crash instead of an error report.
- **Bench note:** logcat **is** usable on this tablet when the Arduino is not attached —
  `adb logcat -b crash -d` is how defect 1 was diagnosed. 7h's note applies only to the USB-host case.
  Also: Git Bash rewrites a leading `/` in an `adb pull` path; prefix `MSYS_NO_PATHCONV=1`.
- JVM **260 tests / 30 classes** green.

**Next:**
Steps 3–7 need stage 9d-2. Step 6's other half (clock skew, #15) and step 5 (status poll) need no
transaction and can run as soon as they are built; steps 3, 4 and 7 create Paystack initialisations.

---

### Phase 9d-2 — the rest of the gate has buttons
**Date:** 2026-09-16
**Status:** done (built and green; unrun on the tablet)
**Commit(s):** see branch `feature/api-probe-panel`

**Summary (plain language):**
Every remaining question we need to ask the server now has a button. Three of them are safe to press
at any time — they only ask. The rest create real records, including a real payment request, so they
sit behind a switch that has to be turned on deliberately and turns itself off again every time the
screen is rebuilt.

One of those questions answered itself before any button existed. The pump's price is ₦1,490 per
litre, and the app can only send whole naira. Multiply 1,490 by most real litre figures and the
answer is not a whole number: 2.35 litres is ₦3,501.50. The server checks that the amount matches
litres times price **exactly**, so rounding it is not an approximation, it is a rejection. Any
fill-up — where the customer stops when the tank is full, not on a tidy figure — will hit this. The
panel now does that arithmetic before sending and says so instead of sending something that cannot
work.

**Technical notes:**
- **Read-only probes:** `GET /transactions/{id}` (any id — an authenticated not-found envelope has
  never been seen either), and a **clock-skew probe** that signs a `/config` ten minutes in the past.
  The second is the only way to observe #15's strings: the server validates the API key first, so
  from outside, a stale timestamp and a missing signature look identical.
- **`ProbeClock` / `ProbeClockOffset`** apply the skew to **request signing only** — audit rows,
  receipts and the fuel log keep the real clock — and `set()` is inert outside debug builds.
  `shiftedBy` restores the offset in a `finally`, tested including the throwing path: a signing clock
  left in the past would make every later request fail in a way that looks like a server fault.
- **Write probes** (`/authorise`, `+1 naira`, decimal, upload) are gated on a switch that is not
  remembered. `/authorise` returns a Paystack checkout URL, which on production is a real
  initialisation.
- **`AmountPlan` + `amountFor()`** is the substantive piece: `Exact` or `Fractional`, with a
  tolerance rather than an equality test, because 1490 × 2.3 is 3426.9999999999995 in binary floating
  point and a probe that called that fractional would be reporting its own arithmetic.
- **`authoriseRaw(JsonObject)`** on the service and client, used only by the decimal probe: `amount`
  is a `Long` on `AuthoriseRequest`, so the client cannot otherwise ask the one question whose answer
  decides whether that type is right. It still goes through signing, the envelope and error mapping —
  only the request DTO is bypassed, which is the thing under test.
- **Summaries read backwards where the test does.** A refused stale timestamp and a refused
  wrong-amount authorise are reported as **successes**; an *accepted* wrong amount is a caution,
  because the exactness the Reference describes would not be enforced. Tested, since a composable
  `when` is not.
- Verified: JVM **283 tests / 32 classes** green (was 260 / 30); `compileDebugRealHwKotlin` and
  `lintDebug` clean. `installDebugProd` failed only because the tablet was unplugged.

**Next:**
Reconnect the tablet, install, and run the read-only probes — they need no reply from anyone. The
authorise steps wait on the Paystack question in `BOSS_CONFIRMATIONS_DRAFT.md` item 4.

---

### Phase 10c — the QR stops being decorative
**Date:** 2026-09-17 / 18
**Status:** done
**Commit(s):** `96b5241`, `ef17770` on `feature/phase-10-payments`

**Summary (plain language):**
Until today the payment screen drew a QR code containing something this app made up. It looked
entirely correct — right size, right position, scannable — and no phone could have paid it. It now
contains the Paystack page the server actually issues, and when there is no page to show it says so
rather than drawing a barcode that leads nowhere. A customer standing in front of a QR that cannot
work is worse off than one told plainly that something is wrong.

The clock was wrong too, in the expensive direction. The screen gave a customer five minutes to pay
and then cancelled the sale; the server honours it for twenty. So the pump was abandoning sales that
were still perfectly good, fifteen minutes early, with the customer standing there. It now uses the
deadline the server sends, and a sale interrupted by a restart picks up the same deadline rather than
being handed a fresh one.

Underneath, the thing that decides how much to charge was rebuilt so it cannot produce an amount the
payment system is unable to collect. **The first version of that was wrong and was caught before it
ran**: it worked at today's price and would have failed at a price ending in 50 kobo, producing
amounts with a fraction of a kobo in them.

**Technical notes:**
- **#46 closed.** One `PumpTransactionResponse` behind three typealiases, verified byte-for-byte
  across the gate captures rather than on the TODO's say-so. `authorizationUrl` and `expiresAt` had
  been discarded silently by `ignoreUnknownKeys` on every poll and every upload. Making the payment
  fields nullable immediately found a real call site — the probe's upload was passing a
  `paymentReference` that is provably optional, where an empty string would have bought an opaque
  server refusal in place of a clear "nothing was sent".
- **`SaleQuote`, and the bug in its first draft.** Three constraints meet: the server's check is an
  equality, Paystack collects whole kobo, and litres always floor. Quoting litres as `n/10_000`, the
  kobo amount is integral exactly when `10_000 / gcd(price, 10_000)` divides `n` — so the litre step
  is **derived from the price**: 0.001 L at ₦1,490, 0.01 L at ₦1,491, 0.02 L at ₦870.50, 0.0002 L at
  ₦1,250. The draft used a plain decimal scale and fell back to 2dp when none divided, which at
  ₦870.50 gives 3.35 L × 87,050 = **291,617.5 kobo**. Invariants are now asserted across five prices
  and six tenders, because a rule that holds at today's price can fail one naira away.
- **#43 closed.** `TransactionState.PrepayAwaitingPayment` carries `expiresAtEpochMs`, boot resume
  restores it, and a deadline already past ends the sale instead of counting backwards.
  `PumpRequestSigner`'s five minutes is **disambiguated rather than changed**: it is the signing
  freshness window, a different five minutes, and still unmeasured — #15's probe proves only that
  ten is too old.
- **`SaleBasis`** was added to `PaymentRequest`. A processor that re-prices against `/config` has to
  know which end of the sale is nailed down. There is no option to price against the device's own
  figure: the server checks against **its** price, so a stale one is refused every time.
- **Not bound in DI, deliberately.** `awaitCancellation()` after the Pending rather than completing
  the flow — a flow that ended there would look to a collector like a payment that had resolved.
- **Tested through a real `PumpApiClient`** over a hand-written fake `PumpApiService`, so the
  envelope unwrapping and error mapping are exercised rather than stubbed past.
- Verified: JVM **341 tests / 39 classes** green (was 324 / 37); `compileDebugRealHwKotlin` and
  `lintDebug` clean.

**A consequence named rather than hidden — needs a policy before the field.**
For a `Dispensed` sale the fuel is already in the tank, so a price change between the nozzle clicking
off and the QR appearing changes what is owed — and the customer watched the old figure climb on the
display. The processor cannot avoid it (the server checks against its own price; anything else is a
refused sale). Options are refuse, warn the attendant, or have the backend honour the struck price.
Rare, not a blocker, and not something to discover on a forecourt.

**Next:**
**10d** — PAID by poll, and the boot-resume trap: `CustomerViewModel` currently restarts a
`process()` call after a restart, which against a real server would **authorise a second sale for a
customer who has already paid**. The id is ours, so the fix is to resume the poll; it needs tests
written at it first. 10d also flips the DI binding.

---

### Phase 10b — money stops being an integer, and the wire form is the captured bytes
**Date:** 2026-09-17
**Status:** done
**Commit(s):** `4a87877` on `feature/phase-10-payments`

**Summary (plain language):**
The app could only tell the server whole naira. Almost no real sale is a whole number of naira — at
₦1,490 a litre, 2.35 litres is ₦3,501.50 — and the server does not accept "close enough": it checks
that the money matches the fuel exactly, so a rounded figure is refused outright rather than accepted
fifty kobo out. Every fill-up would have hit this, because a customer stops when the tank is full,
not on a tidy number.

So money now goes out as a proper decimal. The awkward part was making sure it goes out looking
*exactly* like the figure the server already accepted from us, because the app signs the message it
sends and any difference in how the number is written is a different message.

One thing this turned up that is not a technical problem but a business one, and it needs a decision.
When a customer pre-pays ₦5,000, the pump rounds *down* the litres it will give them — 3.35 litres,
which at that price is ₦4,991.50 of fuel. Those two figures are not the same, and the server will
refuse the sale if we quote it the ₦5,000 the customer actually handed over. Either the customer is
charged for the fuel they get, or the pump gives them the extra hundredth of a litre. It is written
down as a test so it cannot be forgotten, and the next piece has to answer it.

**Technical notes:**
- **`BigDecimal`, not `Double`** — confirmed by the user. The server's check is an **equality**,
  which is the exact circumstance under which binary floating point rots: 1490 × 2.3 is
  3426.9999999999995 in a `Double`, a value that fails the check while reading as correct in every
  log and on every screen. There is a test asserting that drift, for contrast.
- **`NairaAmountSerializer` emits a bare JSON number, in plain notation, with trailing zeros
  stripped.** All three properties are load-bearing, and each has a test:
  - *bare* — the signature is computed over these exact bytes, so `"3501.5"` is a different request
    from `3501.5`, not a cosmetic variation;
  - *plain notation* — `stripTrailingZeros()` alone renders 3500.00 as `3.5E+3`, which is valid JSON
    and absurd in a payment;
  - *stripped* — so a whole amount goes out as `2980`, matching every integer amount in the
    Reference and in the gate captures, rather than `2980.00`.
- **`nairaFromKobo(kobo)`** sets the scale rather than dividing, so no rounding mode is involved and
  none is needed. **`nairaForSale(litres, koboPerLitre)`** computes the server's own check the way
  the server computes it, and is what an `/authorise` body should be built from.
- **Fixtures are the captured bytes**, not invented ones: `docs/api-probes/2026-09-16-prod-gate/`.
- **A now-false refusal removed from the probe.** Its happy path used to *refuse to send* a
  fractional amount, which is how #18c was first answered — correct then, wrong now. `AmountPlan`
  survives as a **label** telling the operator which case a litre figure lands on, which is still
  worth seeing, but it no longer gates what can be sent. `authoriseRaw` is kept with its comment
  corrected: it exists to ask what the **server** does with a body we would never build, so routing
  it through the DTO would only re-test our own serializer.
- **The finding, and why it is 10c's:** pre-pay quotes litres floored to 2dp
  (`DeviceConfig.litresCutoff`), so at ₦1490/L a ₦5,000 pre-pay is 3.35 L — `nairaForSale` = ₦4,991.50
  against `nairaFromKobo` = ₦5,000.00. The exact check refuses that sale. **This bites at a
  whole-naira price**, which makes it a different and more common problem than the sub-naira
  precision question #44 originally anticipated. Test:
  `pre-pay amount and the exact product disagree when the price does not divide evenly`.
- Verified: JVM **306 tests / 35 classes** green (was 297 / 34), 9 new; `compileDebugRealHwKotlin`
  and `lintDebug` clean. No bench gate — nothing device-specific.

**Next:**
**10c** — `/config` → `/authorise` → a QR that can actually be paid, carrying **#43** (read
`expiresAt`, do not assume 5 minutes) and **#46** (one response type behind the three names). It must
also answer the pre-pay amount question above, and the `success.amountKobo` question 10a left at its
call site.

---

### Phase 10a — the payment seam grows the fields the real backend needs
**Date:** 2026-09-17
**Status:** done
**Commit(s):** `70ad358` on `feature/phase-10-payments`

**Summary (plain language):**
Groundwork, and deliberately nothing more. The part of the app that starts a payment could only be
told two things — how the customer is paying and how much — and the real Balanceeè system needs to be
told a third: how much fuel that money buys. It also hands back three things the app had nowhere to
put, the most important being the web address the customer's QR code has to point at.

So this widens the connection between the two halves without plugging the real one in yet. Everything
still runs on the pretend payment system, every existing test passes untouched, and the app behaves
exactly as it did this morning. The point of doing it separately is that the next piece — the real
thing — is easier to check when its changes are not tangled up with this renaming.

One detail worth recording because it is a money question rather than a plumbing one: the amount of
fuel the app promises the server is now guaranteed to be the same figure the pump actually stops at.
The server checks that the money and the litres match **exactly**, so if those two numbers were
worked out separately they would eventually disagree, and a customer standing at the pump would have
their sale refused for no reason they could see.

**Technical notes:**
- **`PaymentRequest(method, amountKobo, expectedLitres)`** replaces the two positional arguments.
  **`pumpId` and `fuelType` are deliberately absent** — they are properties of the *device*, not of
  the sale (credentials and `DeviceConfig` respectively), so 10c's processor sources them itself
  rather than four screens each remembering facts about the pump they run on.
- **`PaymentResult.Pending` gains `checkoutUrl`, `expiresAt`, `paymentReference`**; `Success` gains
  `paymentReference`, without which 10f's upload cannot quote one. All nullable: USSD has no
  checkout URL, and a mock has no real ones.
- **`litresFor(amountKobo)` extracted in `CustomerViewModel`.** Two consumers that must not
  disagree: the cutoff the pump enforces, and the `expectedLitres` quoted to `/authorise`. Tested
  directly — `prepay expectedLitres equals the cutoff the pump will enforce` asserts the quoted
  figure against `FixedDispensing.litresAuthorised` rather than against a literal.
- **The fill-up case is the one that is not derived from the amount.** The tank is already full, so
  `expectedLitres` is the metered figure; deriving it back out of the money would reintroduce exactly
  the rounding the exact check refuses.
- **The mock now carries production's measured 20-minute expiry** (**#43**), not the 5 minutes the
  app assumed in three places, and its checkout URL is on a `.invalid` host — a mock QR scanned by
  accident on a forecourt must fail rather than open a real checkout page.
- **Left open on purpose, with a comment at the site:** `onPaymentSuccess` derives litres from
  `success.amountKobo` while its fallback uses the requested `amountKobo`. Identical under the mock;
  with a real backend one of them has been round-tripped. **10c/10d decides which is authoritative**
  — folding them together now would have buried the question inside a refactor.
- Verified: JVM **297 tests / 34 classes** green (was 287 / 32), 10 new across two new classes;
  `compileDebugRealHwKotlin` and `lintDebug` clean. No bench gate — nothing here is device-specific.

**Next:**
**10b** — `AuthoriseRequest.amount` stops being a `Long`. `BigDecimal` confirmed by the user
2026-09-17; fixtures are the gate's captured bytes (3501.5 for 2.35 L at ₦1490).

---

### The gate — all seven steps, ending with a real paid transaction
**Date:** 2026-09-16 / 17
**Status:** done
**Commit(s):** `e5f4ebe`, `b67a21c`, `8757996`, `868820a` on `feature/api-probe-panel`

**Summary (plain language):**
The app has now done a complete, real transaction with Balanceè's live system: it asked to start a
sale, a customer (us) paid ₦149 at the Paystack checkout, the app noticed the payment had landed, and
it reported the fuel dispensed. Every step worked. This is the thing that has been blocked since July,
and it cost a hundred and forty-nine naira.

The failures were as valuable as the successes. When we tried to report fuel for a sale nobody had
paid for, the server refused — it will not record fuel against an unpaid transaction, and that
protection is on their side, not ours. When we sent a deliberately wrong amount, it refused that too,
with a proper error code rather than a sentence we would have to pattern-match.

One thing to fix later: a dispense can be recorded once and never corrected. Uploading a corrected
figure returns "recorded" and quietly changes nothing, so a wrong number could stick while everything
in our logs says it went through. Two short questions have gone to the backend about that.

**Technical notes:**
- **Status set observed — `PENDING_PAYMENT` → `PAID` → `DISPENSED`**, the three strings the DTOs
  guessed in July. **#18d closed.** Captures: `docs/api-probes/2026-09-16-prod-gate/`.
- **Stable error codes exist on business failures** — `AMOUNT_MISMATCH`, `PAYMENT_NOT_CONFIRMED`,
  `TRANSACTION_NOT_FOUND`, `INVALID_REQUEST` — and on **no** authentication failure. A rule, not an
  inconsistency: match business errors on `code`, identify the auth family by 401. **#18f closed.**
- **#15 closed:** a `/config` signed ten minutes in the past returns
  `401 "Request timestamp is not fresh"` — the exact string the audit predicted, so the error copy
  already drafted stands.
- **#18c closed:** a decimal `amount` is accepted (3501.5 for 2.35 L), and the exact
  `amount == litres × price` check passes on it. **#44:** `amount` must stop being a `Long`.
- **#47 closed:** upload does not validate `actualLitresDispensed` against `expectedLitres`, so
  partial dispenses, early ends (OQ #22) and 7h's recovered pulses can all be reported.
- **#48 — recorded, then corrected the next morning.** The second upload's 200 was first written up as
  "last-write-wins". The dashboard showed the record still at 0.1 L, so it is the opposite: first
  write wins and the repeat is acknowledged and discarded. The 200 means *accepted*, not *stored*,
  and `actualLitresDispensed` is not echoed anywhere (**#46**). A status code is not an observation of
  state — the correction is kept in the file rather than tidied away, because that is the mistake.
- **#49:** the dashboard surfaces uploaded dispenses per pump with their litres. That is the
  counterpart the **14-day parallel run** must reconcile against, and nobody had confirmed it existed.
- **#43:** the QR expiry is **20 minutes**, measured five times, against three places in the app that
  say five. `TransactionState.kt:50` is the one that costs money.
- Two questions to the backend (correction-or-refusal, and echoing the litres) are drafted in
  `BOSS_CONFIRMATIONS_DRAFT.md`. Nothing is blocked on either.

**Next:**
Everything left on the API line is ours: **#43–#48**, all of which belong with the payment flows
(**#8**). The branch is thirteen commits, green, and unmerged.

---

### Phase 10c-bis — the price on the screen is the price the sale is charged at
**Date:** 2026-09-19
**Status:** done
**Commit(s):** `f739b02` — on branch `feature/phase-10-payments`, not yet merged or pushed

**Summary (plain language):**
The pump was showing one price and charging another, and nothing in the app connected the two. The
figure the customer read off the screen was whatever an attendant last typed into the settings
screen; the figure the sale was actually charged at came from Balanceè's own records, fetched fresh
every time. They matched today only because somebody had typed 1490 to match what the office
happened to be holding — the day the office changed its price, the screen would have carried on
saying the old one, indefinitely, with nobody at the pump any the wiser.

The pump now fetches the price from Balanceè when it starts up and again before every payment, and
keeps what it fetches. That means a price change is made once in the office and every pump picks it
up on its own — which is exactly the thing the boss had been told would need someone driving to each
pump to retype it. The attendant's price field stays, because a pump that has not been activated yet,
or one that cannot reach the internet, still has to be able to sell; it is now labelled as the
fallback it is, and the settings screen shows when the price last changed.

Two things now get written into the pump's log. When Balanceè's price replaces the one on the
device, the log says so, with both figures — otherwise the price on the screen changes and nobody
can explain why. And in the one case the fix cannot cover — a fill-up that ends in the same few
seconds a price change lands, where the fuel is already in the customer's tank — the sale goes
through at Balanceè's price, because that is the only amount Balanceè will accept, and the log
records what the customer watched alongside what they were charged. A customer querying their
receipt is otherwise disputing a number nobody can reconstruct.

**Technical notes:**
- **The finding, restated precisely.** `PumpConfigResponse` had exactly three consumers
  (`PumpApiClient`, `PumpApiService`, `BalanceePaymentProcessor`) and none stored anything; the only
  writers of `DeviceConfig` were `OperatorConfigViewModel`, `DebugViewModel` and the VM's
  debug-build seed. Not a race — a permanent divergence. It is **#18(a) / 7b's second half**, marked
  BLOCKED since 2026-09-03 because the `/config` shape was unknown; it has been known since
  2026-09-16 and 10c already parsed it, so the block was stale.
- **`data/config/PumpConfigSync`** wraps `client.config()` and writes through to `DeviceConfig`
  before returning. Both callers get the sync as a side effect of what they already did: the
  processor's fetch-before-authorise (the OQ #8 correctness guarantee) and a new boot call. It
  returns `SyncedConfig`, which carries the **displaced** price — a caller cannot recover it
  afterwards, and it is what makes "the price moved during this sale" answerable at all.
- **Layering:** the boot caller only needs "refresh what you know", so it takes a narrow
  `domain/config/DeviceConfigSync` interface (bound in `NetworkModule`), keeping `CustomerViewModel`
  importing only `domain.*` as it does today. The processor takes the concrete class, because it
  needs the response it just stored.
- **Only `koboPerLitre` and `fuelType` are taken.** `pumpLabel` and `virtualAccountNumber` survive a
  sync untouched. `stationName` is deliberately **not** taken though `/config` carries one:
  `ReceiptText.kt:67` prints `DeviceConfig.stationName` while `CustomerStateHost.kt:108,126,143`
  shows `StationIdentity.displayName`. That duplication predates this work and is now on the board;
  resolving it by way of a price sync would have silently changed what receipts say.
- **No write when nothing moved**, so `updatedAt` keeps meaning *when the price changed* rather than
  *when we last had signal* — the operator screen renders it.
- **`EventType.PRICE_SYNCED`** on an actual change only. A first sync is not a change: logging one
  would put a "price changed" row in every pump's log the day it is activated, which teaches an
  operator to ignore the row.
- **`EventType.PRICE_CHANGED_MID_SALE`** for `SaleBasis.Dispensed` only, recorded after the
  authorise succeeds so the log never carries a discrepancy for a sale that never happened. The
  detail carries both prices and both amounts. `SaleBasis.Tender` is excluded on purpose — a pre-pay
  customer is buying a sum, not a volume, so a re-price simply buys them fewer litres.
- **Honouring the struck price is not available to us**: the server's check is an equality against
  its own `pricePerUnit`, so any other amount is a refused sale. Added to the **#18** asks; not
  waited on.
- **The operator's fuel-log card is now the Pump log**, one chronological list with a headline per
  event kind. A price row rendered by the fuel wording read "Amount unknown" in `WarningRed`, which
  means lost fuel — the opposite of what happened.
- **Boot sync runs on its own coroutine**, not in the boot sequence that asserts the relay-open
  invariant and resumes a live sale; nothing safety-critical waits on a server that may be
  unreachable. The fetched price is applied to the display only when the pump is `Idle` — a resumed
  dispense has already struck its price and its litre target.
- Verified: JVM **357 tests / 40 classes** green (was 341 / 39), including 11 new in
  `PumpConfigSyncTest` and 4 in `BalanceePaymentProcessorTest`; `compileDebugRealHwKotlin` and
  `lintDebug` clean.

**Next:**
**10d — PAID detection by poll.** ~10 s poll over `GET /transactions/{id}` across the
`PENDING_PAYMENT` window, terminating on `PAID`, on `expiresAt`, or on cancel — and with it the
boot-resume trap: `CustomerViewModel:1095` restarts `process()` after a restart, which against a
real server would authorise a **second** sale for a customer who has already paid for the first.
`BalanceePaymentProcessor` stays unbound in DI until that poll exists.

---

### Phase 10d — payment confirmed by polling, and a restart that stops selling twice
**Date:** 2026-09-19
**Status:** done
**Commit(s):** `42064e1` (poll + resume), `b347188` (Flow 3's QR), `8aa2879` (DI per build type) —
on branch `feature/phase-10-payments`, not yet merged or pushed

**Summary (plain language):**
Until today the app could put a payment QR on screen but had no way of learning that anyone had
paid it. It now asks Balanceè every ten seconds, until the payment lands or the server's own
twenty-minute window runs out, and starts the fuel the moment the answer comes back "paid". That is
the last missing piece of a digital sale.

The other half is what happens when the tablet restarts mid-payment — a power cut, a crash. The app
used to *start the sale again*: a brand-new payment, for a customer standing at the pump who may
already have paid for the first one. It never showed up in testing because the practice payment
system we develop against is happy to be asked twice. Against the real one it would have charged
somebody twice. The app now asks about the sale it already has, and cannot create a second.

Three more things turned up while doing it, all of them the sort that only surface when you follow
the money end to end. The fill-up QR still could not be paid — last month's fix covered the pre-pay
screen and left this one showing a code no bank will open. The pre-pay screen was printing the
customer's round ₦5,000 beside a checkout page that would actually charge ₦4,998.95. And the pump
was about to stop five millilitres short of what each customer had paid for, on every single sale,
because two parts of the app worked the amount out differently.

Finally, the real payment system is now switched on — but only in the builds that should have it.
The everyday development build keeps the simulator, so nobody demonstrates the app and charges a
card by accident.

**Technical notes:**
- **The poll.** `BalanceePaymentProcessor.process` no longer ends in `awaitCancellation()`; it polls
  `GET /transactions/{id}` on a 10 s cadence (OQ #8) until terminal or `expiresAt`.
- **What ends the poll early is deliberately a short list** — `PAID`, `DISPENSED`,
  `TRANSACTION_NOT_FOUND` and `NotActivated`. An unrecognised status, an `ApiError.Serialization`, a
  500 and a dropped connection all keep polling. The asymmetry is the point: giving up on a status
  nobody has observed refuses fuel to someone who has paid, on a guess about a word, whereas riding
  to the deadline costs a wait the server's own window bounds — and the VM's countdown cancels the
  flow at that same moment anyway. `DISPENSED` counts as paid: a sale that completed and uploaded
  before a restart is a paid sale.
- **`PaymentProcessor.resume(ref, request, deadline)` is a separate method, not a flag on
  `process`.** Calling `process` again is precisely what a resumed sale must not do, so the seam
  makes the wrong call impossible rather than discouraged. It skips `/config` and `/authorise`
  entirely — re-pricing would be wrong even if it were free, because the customer may already have
  paid the figure they were quoted.
- **The trap was on BOTH digital flows.** The board named `resumePrepayPaymentListener`; reading the
  code found `startFillupDigitalPayment` was called from the boot-resume branch too, where it is
  worse — the fuel is already in the customer's tank.
- **The deadline is restored, not re-granted.** `PrepayAwaitingPayment.expiresAtEpochMs` and
  `FillupDigitalAwaitingPayment.expiresAtEpochMs` are handed to `resume`; the server's window kept
  running while the app was down.
- **`PaymentResult.Pending` gains `amountKobo` and `litres`, both required.** They are the quote's,
  not the request's. At ₦1,490/L a ₦5,000 tender authorises ₦4,998.95 (`SaleQuote`), and the QR
  screen was printing the ₦5,000. Required rather than defaulted because a processor that does not
  answer this is showing someone the wrong price.
- **`PaymentResult.Success` gains `litresAuthorised`, and it answers the question 10c left in a
  comment.** `onPaymentSuccess` re-derived litres via `DeviceConfig.litresCutoff`, which floors to
  2 dp, while the quote lands on a payable litre step: 3.355 authorised, 3.35 derived. The pump
  stopped 5 ml short of what was paid for on every pre-pay sale, on the same figure 10f will
  reconcile against the server's record. Persisted on the state so a resume keeps it.
- **Flow 3's QR (`b347188`).** `onFillupPayDigital()` built `nip://transfer?account=…` from the
  operator's virtual account — well-formed, resolvable by no scanner, honoured by no bank. OQ #6
  retired the virtual account when payments moved to Paystack and this was its last caller; the
  state class had been *documented* as carrying a checkout URL since 10c, which it never did. The
  screen now holds on `FillupTankFull` until `Pending` arrives (mirroring Flow 1) rather than
  transitioning into an empty QR, blank content renders the reference instead of a QR of nothing,
  and `buildNipTransferQr` / `DEFAULT_VIRTUAL_ACCOUNT` are gone.
- **The DI flip is per build type (`8aa2879`).** A one-line unconditional bind would have made every
  debug build charge real cards. New `MOCK_PAYMENTS` buildConfigField mirroring `MOCK_HARDWARE`,
  branched in `PaymentModule` with a `Provider` so the unselected implementation is never
  constructed: mock on `debug` / `debugRealHw` (dev backend, no activated pump, and the debug
  screen's auto-approve and force-resolve only exist on the mock), real on `debugProd` / `release`.
  The debug screen states in red that the controls are inert when they are.
- **Verified on the real-payments graph specifically:** `assembleDebugProd` builds, so Hilt resolves
  `BalanceePaymentProcessor` and its injected `Clock` — `lintDebug` alone would not have caught a
  missing binding on the path only production builds take.
- Verified: JVM **378 tests / 41 classes** green (was 357 / 40), including 11 new poll/resume tests
  on the processor and a new `CustomerViewModelPaymentResumeTest`; `compileDebugRealHwKotlin`,
  `lintDebug` and `assembleDebugProd` clean.

**Next:**
**10e — error mapping** (#14's mapping half, #45). `PAYMENT_NOT_CONFIRMED` is a 409 that parses as
`ApiError.Business`, which `ApiResult.kt:52` makes non-retryable — and an upload job treating it as
final drops the record permanently, which is the one thing the upload job exists to prevent. The
taxonomy needs a third outcome: *retry later, not now*, keyed on `code` and never on prose. Then
10f (the upload job) and 10g (the tablet gate against production).

---

### Phase 10e — a failure finally says two different things to two different people
**Date:** 2026-09-19
**Status:** done (both halves)
**Commit(s):** `6162027` (taxonomy half), `1365cb8` (copy half)

**Summary (plain language):**
Until now, every way a sale could fail told the customer the same sentence — "Payment was not
completed." — and handed the attendant whatever raw text the server happened to send. That is wrong
in both directions. A customer standing at the pump cannot act on "request timestamp is not fresh",
and an attendant cannot act on a sentence that does not say what to do. Now each failure carries two
lines: one plain one for the screen the customer is looking at, and one for the attendant behind the
PIN that names the actual fix — open Pump settings and re-check the price, turn on automatic date
and time, wait because the payment has not landed yet. The screen already showed retryable failures
in gold and dead ends in red; it now has real answers to put in each.

The second half, done first, was about a word: the app could previously say only *retry* or *give
up* about a failure, and there was a third thing the server says — **not yet**. A payment the
backend has not seen confirmed reads exactly like a refusal and is not one. Anything treating it as
final would throw away a record of fuel that was genuinely dispensed, which is the one outcome the
upload job coming next exists to prevent.

**Technical notes:**
- **The taxonomy half (#45, `6162027`).** `RetryPolicy` is `RETRY_NOW` / `RETRY_LATER` / `TERMINAL`,
  keyed on the server's `code` and never on its prose. `RETRY_LATER` is deliberately **not** retried
  in-flight — a second and a half of backoff will not outlast a payment confirming — so
  `isRetryable` survives as `retryPolicy == RETRY_NOW` and nothing that used to back off stopped.
  `PumpErrorCodes` collects the four codes observed at the #32 gate. It also retired the duplicate
  `BalanceePaymentProcessor.isPollTerminal` had created an hour earlier in 10d.
- **`FailureCopy(customerMessage, attendantDetail, recoverable)`** is the unit the split travels in.
  `PaymentResult.Failed` carries it instead of a single `reason` string, which is what forced the
  ViewModel to invent the customer's sentence at the call site; `toErrorState()` renders it.
  `BalanceePaymentProcessor.describe()` — the 10d placeholder that put the server's own prose on a
  customer-facing display — is gone.
- **Matching is on `code`, and on the 401 where there is no code.** The Reference PDF quotes
  `"Amount mismatch for PETROL…"`; production actually returns *"The sale amount does not match the
  current station price for this fuel type…"* for that same `AMOUNT_MISMATCH`. The one Reference
  string this project has been able to compare against the wire **had already been reworded**, so a
  table keyed on the other eleven would mostly not fire and would fail silently the day it stopped.
  Five rows are keyed; three (out of stock, invalid station price, fuel type not sold here) are
  **parked pending one observation of their code**, the same rule `PumpErrorCodes.NOT_YET` follows.
- **Catalogue A's last row is the one that had to exist.** Anything unrecognised shows the attendant
  the server's own sentence verbatim, plus the HTTP status and the code, rather than being swallowed
  into a sentence that says nothing. Terminal, because the app cannot tell a temporary refusal from
  a permanent one without being told.
- **Found on the way: the clock-skew 401 shares a bucket with a rejected API key.** Both are 401s
  with **no code**, so the drafted credentials line ("it may need re-activating") would have sent an
  attendant to the one screen that cannot fix a wrong clock. It is matched on the message — the one
  prose match in the mapper, and it earns the exception by having been observed twice on production
  at the #32 gate rather than quoted from the PDF. A rewording costs the match and degrades to the
  credentials line: still terminal, still pointing at a person. **This closes #15's mapping half**;
  the enforcement half (the app cannot set its own clock) stays open.
- **Two departures from the approved draft, both recorded in `ERROR_COPY_DRAFT.md`.**
  `PAYMENT_NOT_CONFIRMED` becomes `recoverable` against the table's `no` — that row predates #45,
  and a *not yet* painted red tells an attendant a sale is dead when it is seconds from confirming.
  And `recoverable` is authored per row rather than derived from `RetryPolicy`: the two answer
  different questions, since an amount mismatch is terminal to a retry loop and recoverable to an
  attendant who fixes the price.
- **The tests pin the properties, not the prose.** Rows will be reworded; two invariants must not
  break — no server string ever reaches the customer line, across every `ApiError` shape, and every
  code in `PumpErrorCodes.NOT_YET` is `RETRY_LATER` *and* never shown as a dead end.
- **Design-authority flag stands.** There is still no error screen in `docs/Strict design screens/`,
  so every word here is invention a reviewer can overrule; the flag at the top of
  `ERROR_COPY_DRAFT.md` now records what was wired and on what date.
- Verified: JVM **409 tests / 43 classes** green (was 388 / 42); `compileDebugRealHwKotlin`,
  `lintDebug` and `assembleDebugProd` clean, the last two in separate invocations.

**Next:**
**10f — the upload job.** `workmanager`, a `TransactionUploadWorker`, and the thing that finally
sets `syncedAt`. It rests directly on this phase: `RETRY_LATER` is what stops it discarding a
dispense the backend has not yet seen payment for. Carries **#48** (upload once, never re-send a
superseded figure — a corrected upload returns `200 Transaction recorded` and changes nothing, so a
wrong figure sticks while every log in the app says it went through). Then **10g**, the tablet gate
against production.

---

### Phase 10f — the dispense finally reaches the backend
**Date:** 2026-09-19
**Status:** done (JVM-verified; the instrumented half rides with 10g)
**Commit(s):** `ed88f17` (the record), `84b20a1` (the uploader), `75f7de1` (WorkManager + the wiring)

**Summary (plain language):**
Until now the app counted the fuel, took the money, and kept the result to itself. Balanceè's own
records had no idea a sale had happened unless someone was watching the payment page. This phase
adds the job that reports each dispense to the backend — and, crucially, one that keeps trying. A
forecourt with no internet is normal, not an error, so the record sits safely on the tablet and goes
out when the link comes back, including after a power cut, without anyone reopening the app.

The first thing the work turned up was that the app could not have reported a sale even if it had
tried. The backend requires a payment reference with every dispense, it is issued once when the sale
is authorised, and the app had been throwing it away at every point it arrived — for months. So the
phase started by fixing the record, and only then built the thing that sends it.

The second thing worth saying in plain language: this job will never quietly decide a sale did not
happen. If Balanceè refuses a record for a reason that will not change, the sale stays in the pump's
log marked as *not recorded*, in red, with the litres and the reason — because at that point the
station has sold fuel that the backend's books do not know about, and that needs a person.

**Technical notes:**
- **The defect that reframed the phase (`ed88f17`).** `PaymentResult.Success.paymentReference` has
  existed since 10a; `onPaymentSuccess`, `onFillupDigitalSuccess` and `onUssdSmsConfirmed` all
  discarded it, the last two by rebuilding `Complete` from the source state and ignoring the result
  entirely. Both now take the `PaymentResult` whole.
- **Schema v5** adds `transactions.paymentReference`, `.startedAt` and `.uploadError`. All three
  nullable with no default, because a pre-10f sale has none of them and a default would invent
  history. `getPendingSync` filters on all three, and each condition excludes something different
  that would otherwise sit in the queue forever: already sent (**#48**), a cash sale with no
  reference to quote, and one the server has refused for good.
- **The two fields are carried on the states, not in the ViewModel.** A power cut mid-dispense is
  the one moment the upload most needs them. `FixedDispensing` and `Complete` carry both;
  `FillupDispensing` → `FillupTankFull` → `FillupDigitalAwaitingPayment` carry the start time along
  the fill-up chain. `CashFixedDispensing` gets neither — cash has nothing to upload.
- **`TransactionUploader` (`84b20a1`) is a plain class.** WorkManager decides *when*; everything
  that can be wrong lives where a JVM test can put it through a server that says no six different
  ways. 16 tests, refusal fixtures taken from the #32 gate captures.
- **`RETRY_LATER` is what this phase rests on.** `PAYMENT_NOT_CONFIRMED` is a 409 that parses as a
  considered refusal and is not one; read as final it discards the record of fuel a customer has
  already taken. A test drives it through refusing twice and then confirming.
- **`NotActivated` deliberately departs from the shared taxonomy.** Terminal for a customer at a
  screen, wrong here — credentials are a property of the device, and condemning the queue over one
  would throw away a day of real dispenses that a re-activation fixes.
- **A terminal refusal closes a record but never silently**: the row keeps its place, stops being
  offered, carries the attendant-facing sentence 10e already wrote for that failure, and writes a
  red `DISPENSE_UPLOAD_FAILED` entry to the pump log with the litres as the headline. Transient
  failures write no event — one per attempt would bury the ones that matter.
- **The run continues past a failure.** One record the server refuses must not hold the rest of the
  day behind it, and the run is only `RETRY` if something transient happened.
- **`TransactionUploadWorker` (`75f7de1`) has no `Result.failure()` branch.** `failure()` means give
  up for good, and the only thing allowed to abandon a dispense is the uploader. An unexpected throw
  takes the same answer: come back, conclude nothing.
- **Enqueued under one name with `KEEP`, not `REPLACE`.** A busy pump enqueues a request per sale;
  `REPLACE` would cancel the run in flight and reset its backoff every time, so a forecourt on a bad
  link would restart the queue forever and never finish reporting anything. Constrained on
  `CONNECTED`, backed off 30 s doubling.
- **Hilt owns WorkManager's configuration**, because the worker needs the repository, the API client
  and the credential store. The manifest removes WorkManager's default initializer to match —
  leaving both is how an app works in debug and cannot instantiate its worker in the field.
  `assembleDebugProd` is what proves the graph resolves on the build that charges real cards.
- **Two test-fake defects fixed, both of the kind that let a real defect through.**
  `FakeTransactionRepository.getPendingSync` returned everything and mirrored none of the DAO's
  filter; `FakePaymentProcessor.succeed` defaulted the payment reference to null, so tests passed
  against records the upload could never use.
- **Incidental finding:** `PumpApiClient.uploadTransaction` already wraps the call in
  `retryingApiCall`, so a single blip is absorbed three attempts deep and never reaches the job.
  A fake that failed only the first call was testing nothing; failures are now keyed by transaction
  id, and the absorption has its own test.
- Verified: JVM **432 tests / 45 classes** green (was 388 / 42 at the start of the day);
  `compileDebugRealHwKotlin`, `lintDebug` and `assembleDebugProd` clean.

**Not yet proven on a device:**
The four new v4→v5 migration tests and the worker's real scheduling (constraint, backoff, survival
across a reboot) both need the tablet. They ride with 10g rather than being claimed here.

**Next:**
**10g — the gate.** A real small sale end to end through the customer UI on the tablet against
production and `SN-TEST-001`, QR scanned with a phone, sized like the #32 sitting. It now also has
to show the dispense arriving in the dashboard's Transactions view (**#49**), which is the only way
the figure can be read back at all — the API cannot return it (**#46**). Nothing merges until it
passes.

---

### Phase 10h — two rounds of review, and the defect that destroyed records
**Date:** 2026-09-20
**Status:** done (for this run — 5 of the re-review's 8 findings remain open and are boarded)
**Commit(s):** `202144e`, `c015bcf`, `28d8c03`, `e26246e`, plus four board commits; branch pushed at `94cff8f`

**Summary (plain language):**
No new features today. The whole day went on finding and fixing faults in what was already built —
first the three left over from yesterday's review, then a second review of the entire branch, which
found eight more. The worst of those would have quietly destroyed the station's records: if the
tablet's clock drifted overnight, every completed sale waiting to be reported to Balanceè was marked
"never send this" permanently, and putting the clock right did not bring them back. Fuel sold, money
taken, and no record of it — which is the exact outcome the reporting job exists to prevent. That is
fixed, along with three others: a pump crashing when the station had no price set, a customer being
charged twice by tapping "pay" twice, and the price on screen moving during start-up. The branch is
pushed and still not merged; five smaller findings are written down and waiting.

**Technical notes:**
- **Review #1's last three, all fixed in more places than the review named.**
  - **#7 (`202144e`)** — `/config` returning `pricePerUnit: 0` reached `litreStepMicrosFor`, whose
    `require` throws out of a `flow { }` on `viewModelScope`: process death at the pump. Screened at
    the boundary instead — `SyncedConfig.hasUsablePrice`. **The quieter half mattered more:** the 0
    was also being written through, wiping the last known good price and stopping **cash** sales,
    which consult no backend at all. New `PRICE_SYNC_REJECTED` event, logged once per rejected
    figure per app run (`/config` is fetched before every authorise).
  - **#5 (`c015bcf`)** — pre-pay and fill-up both hold their state for the whole authorise round
    trip, deliberately, so the `as?` state check that is mutual exclusion everywhere else guards
    nothing. A second tap cancelled a `process` mid-request and started another; the server does not
    un-create a transaction because we stopped listening. `authoriseJob` is a `Job` rather than a
    flag so cancellation reopens the gate for free — the failure mode of a guard like this is a pump
    that will not sell, and two tests cover exactly that. The review named the fill-up; pre-pay had
    it too.
  - **#6 (`28d8c03`)** — `syncPriceOnBoot` asked "may I move the price?" before `bootResume` had
    dispatched an answer. **The decision now waits on `bootResumed`; the fetch does not**, so the
    boot coroutine holding the relay-open invariant still waits on nothing a network can delay.
    A test asserts the overlap so a later tidy-up cannot serialise it.
- **The re-review (`/code-review high main`, whole branch) found 8; verified before acting, 7 held.**
- **R1 + R2 (`e26246e`) were one defect with two consumers.** The observed clock-skew 401 carries no
  `code`, so `safeApiCall` made it `Business(code = null)`, which the taxonomy called TERMINAL.
  `TransactionUploader` then wrote `uploadError`; `getPendingSync` filters `uploadError IS NULL` and
  **no query anywhere clears it**. The poll gave up on the same error, so NTP correcting a clock
  mid-payment showed a failure to a customer whose money was landing.
  - The proof it was wrong rather than merely strict was internal: `unauthorisedCopy` has always
    told an attendant this same 401 is `recoverable` and how to fix it.
  - `NOT_A_VERDICT` = {401, 408, 429}, consulted **before** the no-code rule. 403 deliberately
    excluded — a considered refusal to serve this caller. An envelope failure on a 2xx has no status
    and stays terminal, so unclassifiable refusals do not become infinite retries.
  - **The review proposed a second list of final codes for the poll; not needed.** `isPollTerminal`
    already defers to `retryPolicy`, so the one change fixed both and #45's "one place" holds.
- **Every fix was checked against the pre-fix file**, by restoring it from git and re-running: 5/8
  double-tap assertions fail without #5, 2 fail without #6 — including the one that previously
  *passed* — and all 8 new taxonomy assertions fail without R1, across three test classes.
- **Two test fakes were themselves defective, and that is the recurring lesson.**
  `FakePaymentProcessor` emitted `Pending` on the same tick, so no test could sit in the window the
  double-tap lives in; `FakePulseSource.awaitAdapterCount` answered instantly, so every boot test ran
  the resume *before* the sync — the opposite of production, and why #6's test asserted a guarantee
  the code did not make. `holdAuthorise()` and `holdAdapterCount()` open those windows.
- **Raised, not fixed:** **#50** (three screens read `uiState.priceKoboPerLitre` while holding a
  struck figure — `priceMayMoveFreely` is a guard doing a type's job) and **#51** (the R1 fix means a
  genuinely deauthorised pump retries forever with nothing in the log; `NotActivated` has had this
  shape since 10f, so the fix widened it rather than creating it).
- Verified: JVM **491 tests / 48 classes** green (was 459 at the start of the day);
  `lintDebug`, `assembleDebugProd` and `compileDebugRealHwKotlin` clean. Branch **pushed** —
  `origin/feature/phase-10-payments` = `94cff8f`.

**The pattern worth carrying out of this run:**
In **five consecutive rounds** a defect was fixed in one flow and left standing in a sibling. It has
now cost: the local-id bug (Flow 1 right, Flow 3 wrong, USSD wrong), the stale-price bug (four
readers, one fixed), #7 (crash fixed, write-through missed), #5 (fill-up named, pre-pay silent), and
R4 below (`ed77e00` fixed `Complete.txnId` in Flow 3 and left the expiry path alone). **Checking the
siblings is not diligence on this branch; it is the single highest-yield step there is.**

**Next:**
**#R4** — the fill-up abandon event logs the local `BLC-NNNNN` rather than the id `/authorise`
issued, which is `ed77e00`'s defect in the same flow's other method. Then **#R5**/**#R6**, two
unguarded Room writes that escape into `viewModelScope` (one of them introduced by `202144e` the
same morning). **#R3** and **#R8** are boarded as judgment calls. Then weigh a third review pass
against the fact that both rounds so far found defects in code written that same day — and only
then merge.

---

### Phase 10h (round 3) — the last three review findings, and a defect a fix created
**Date:** 2026-09-20
**Status:** done
**Commit(s):** `f5bf58c` (#R4), `99b3c9d` (#R5), `5b2731b` (#R6 + the cancellation correction)

**Summary (plain language):**
Three more problems found by the second review are now fixed, which closes every one that was
holding up the merge. The first meant that when a customer walked away from a fill-up QR without
paying, the pump wrote down a reference number the bank's system had never issued — so if that
customer later paid anyway, nobody could match the payment to the sale. The other two were the same
kind of problem in two places: the app wrote a line to its own log *before* doing something
important, and if that write failed — a full tablet, a damaged database — the important thing never
happened. In one case the customer never got a QR code for fuel they had already taken. In the
other, **the app could not start at all**, which on a forecourt also stops the pump taking cash.

There is one thing worth saying plainly. While fixing the second of these in the morning, we
introduced a new fault of our own, and found it in the afternoon while fixing the third. It never
left the working branch, and it is now covered by a test — but it is the second time this week that
a fix has created a problem, and that is the reason the plan ends with a verification gate rather
than with a merge.

**Technical notes:**
- **#R4 (`f5bf58c`)** — `startFillupDigitalExpiry` recorded `PAYMENT_ABANDONED` against the
  `FillupTankFull` it was launched from, whose `txnId` is the local `BLC-…` minted at
  attendant-authorise. The live `FillupDigitalAwaitingPayment` carries the server's id (put there by
  `onFillupDigitalPending`), which is what the pre-pay twin has always read. The amount had the same
  shape — the shutoff quote rather than what the still-live checkout page charges. **The cash
  fall-back deliberately still reads `source`** (tank litres at the pump's own price = what Flow 2
  collects for the same tank; the row it settles into is a cash sale nothing authorised), and a test
  pins that asymmetry so it is not "fixed" later. `txnRefFor`'s KDoc claimed every state carries a
  `BLC-NNNNN` — untrue since 10g downstream of an `/authorise` — and was corrected, because a
  sentence like that is how this defect kept coming back. **Third appearance of one defect**:
  `ed77e00` fixed it for `Complete.txnId` in this same flow.
- **#R5 (`99b3c9d`)** — `recordPriceRaceIfAny` runs *after* `/authorise` returns, so the sale exists
  on the server and its checkout page is payable; a Room throw there went through `flow { }` into
  the collector's `viewModelScope`. Guarded inside the method so the guarantee belongs to the
  method; the detail sentence goes to `Log.e` because `previousKoboPerLitre` is gone from the device
  the moment the sync overwrote it. **Sibling fixed with it:** `recordAbandonedPayment`, whose row
  is written *before* the `setState` that ends the sale — a failure stranded the pump on a dead QR
  screen. Checked and left: `TransactionUploader`'s write is already contained by the worker's
  catch-and-retry; `PumpConfigSync`'s two are #R6.
- **#R6 (`5b2731b`)** — `DeviceConfigSync.refresh` has always documented "failure must never be an
  exception"; `client.config()` honoured it, `writeThrough` touched Room five times unguarded, and
  review #7's fix added the fifth. Three distinctions now in the code: a failure to *store* is not a
  failure to *fetch* (the response still prices the sale); **an unreadable database is not an empty
  one** (`saveConfig` replaces the row, so a read that threw must not be treated as "nothing
  stored", or the operator's fields are wiped — the adapter's unknown-is-not-zero rule); and a row
  that was not written did not happen (`PRICE_SYNCED` only if the config write succeeded,
  `lastRejectedPrice` marked only on success so one full-disk moment cannot permanently silence the
  no-price warning).
- **The sibling #R6's own test exposed:** the `init` boot coroutine is the same bare launch in the
  same constructor, with `seedDefaultConfigIfMissing()`, `getConfig()` and `bootResume()` all
  unguarded. Contained. The relay-open assert is guarded **separately** and logged in its own words:
  a boot that cannot open the relay is a safety event, not a database problem, crashing would not
  open it either, and the firmware dead-man watchdog is the real backstop.
- **`domain/util/runCatchingCancellable`** — `runCatching` catches the `CancellationException` the
  coroutine machinery throws to unwind a cancelled job. `expiryJob` is cancelled the instant a
  payment succeeds and the expiry coroutine may be suspended inside the Room write at that moment,
  so the absorbed cancellation would have let the following `setState(Idle)` wipe a paid sale.
  Rethrow-then-catch is `safeApiCall`'s shape. Converted the three sites where an absorbed
  cancellation changes control flow on a money path; the ~20 inert ones are **#52**, deliberately
  not swept in.
- **Test discipline worth keeping:** every fix's assertions were re-run against the pre-fix file and
  confirmed to fail. That caught a test that was passing for the wrong reason — the boot test
  "passed" pre-fix while leaking an uncaught exception into the *next* test, which is a test proving
  nothing. It was rewritten until it failed in its own name.
- **Behaviour change to re-examine on a device:** a `bootResume()` that throws now leaves the pump
  at Idle with the relay open and a line in the log, where it previously crashed. Idle-and-safe
  beats a crash loop, but it means a partially-restored sale now fails *quietly*. Listed as step 1
  of the merge gate for that reason.
- Verified: JVM **505 tests / 48 classes** green (491 at the start of the round); `lintDebug`,
  `assembleDebugProd` and `compileDebugRealHwKotlin` clean, run in separate invocations. Branch
  pushed — `origin/feature/phase-10-payments` = `5b2731b`, 50 commits.

**Next:**
The **merge gate**, which is four steps and is written out in `TODO.md`: a tablet smoke test (the
`init` boot path changed, and the 10g gate ran against a build that no longer exists), a review pass
**scoped to this round's six fixes** rather than to the whole branch again, then merge, then the
improvements (#R3, #R8, #50, #51, #52) as small branches off `main`.


---

### Phase 10h (round 4) — the scoped review, and the expiry that cancelled itself
**Date:** 2026-09-20
**Status:** done
**Commit(s):** `802c1dc` (fix + harness); this board/log update

**Summary (plain language):**
Before merging, we re-read only the six fixes made in the last round — the newest code, which is
where this branch's defects have kept coming from. The six were sound. What the pass found instead
was in the half of the code those fixes did not touch: when a customer takes a pre-pay QR code and
never pays, the pump was supposed to give up, write a line in the log saying so, and go back to its
idle screen. On a real tablet it did neither. It sat on a dead QR code with the clock at zero, while
the payment link stayed live — so a customer could still pay it, and nothing at the pump would know
or have a record to check against. That is fixed, and the test harness that hid it is fixed with it.

**Technical notes:**
- **#R9 (`802c1dc`)** — `startExpiryCountdown` runs *inside* `expiryJob`, and on expiry called
  `cancelInFlightJobs()`, which cancels `expiryJob`. In a cancelled coroutine the next suspension
  point throws; the next call was `recordAbandonedPayment`, and `EventDao.insert` is a suspend Room
  DAO. So the write threw, `setState(TransactionState.Idle)` after it never ran, and the
  `CancellationException` unwound silently because the job was already cancelling. The three other
  jobs are now cancelled by name and `expiryJob` is left alone — it is ending anyway.
- **Introduced by `8e0a15c`**, the 10g fix that added the abandonment row: it inserted a suspension
  point between a self-cancel and a transition. Third defect on this branch created by a fix, and
  the question that catches all three is one question — *what runs after this, and is the coroutine
  still alive to run it?*
- **The pre-pay half of a pair, again.** #R4 and #R5 both landed on `startFillupDigitalExpiry`; the
  twin's `cancelInFlightJobs()` was never questioned because its tests passed. Every other
  `cancelInFlightJobs()` / `expiryJob.cancel()` call site was checked for the same shape: this was
  the only self-cancel. `onPaymentFailed` cancels its own `paymentJob` too, but `setState` does not
  suspend, so it survives — noted, not changed.
- **`FakeEventRepository.record` now `yield()`s first.** It returned without ever suspending, so no
  test in the suite could see a cancellation arrive at an audit write. Sequenced deliberately:
  harness first, then confirm three pre-pay tests fail — `an abandoned prepay is recorded with the
  transaction id it abandoned`, `prepay expiry auto-cancels an unpaid transaction back to Idle`, and
  #R5's own `a prepay still returns to Idle when the abandonment row cannot be written` — then the
  fix, then green. The fill-up twin's three passed throughout, which is what located the defect.
- **Checked and left alone:** `PumpConfigSync`'s never-throws contract holds (all five Room touches
  guarded, the read-failure early return correctly declines to write rather than wiping the
  operator's fields, and its new tests were confirmed to fail against the pre-fix file);
  `runCatchingCancellable`'s non-local return through `getOrElse` is sound; review #5's
  `authoriseJob = paymentJob` assigned after `launch` cannot wedge the pump shut, because a
  completed job is not `isActive`.
- **One wording defect, not fixed:** the boot guard logs *"Boot resume failed; the pump stays
  Idle"*, but `FixedDispensing`, `Complete` and `FillupDigitalAwaitingPayment` all `setState` before
  later steps that can fail, and the guard does not cover throws inside the jobs `bootResume`
  spawns. The sentence is narrower than the code. Left for whoever does step 1, since the tablet is
  where that path gets exercised.
- Verified: JVM **505 tests / 48 classes** green; `lintDebug`, `assembleDebugProd` and
  `compileDebugRealHwKotlin` clean, in separate invocations.

**Next:**
Merge-gate **step 1, the tablet smoke test** — now with one more thing to watch: let a pre-pay QR
expire unpaid and confirm the screen returns to Idle on its own and the operator log shows the
abandonment row. Then merge, then the improvements (#R3, #R8, #50, #51, #52) as small branches off
`main`.
