# Runbook — the activation gate (TODO #32)

How to run the gate on a tablet, and what to do with what comes back. Written 2026-09-16 against
`Test Pump 1` / `SN-TEST-001`, a throwaway pump on a dummy business account, on **production**.

**Read first:** TODO **#31** (settled — why production is the right target here), **#32** (the steps,
and why they must go through `PumpApiClient`).

**Status:** **all seven steps passed** on 2026-09-16/17, ending in a real paid ₦149 transaction.
The gate is closed; this file is kept as the way back in.

**One probe is built and UNRUN — the pre-pay precision probe** (added 2026-09-17 with phase 10b).
It is the only thing in here still owed an answer, and phase **10c** is waiting on it. Skip to
[Step 8](#step-8--pre-pay-precision--the-one-still-unrun) if that is why you are here; you still need
the install, activation and a `GET /config` first.

---

## What the panel can do

| probe | writes anything? | gated on |
|---|---|---|
| `GET /config` | no | activation |
| `GET /transactions/{id}` | no | activation |
| `GET /config` signed 10 min ago | no | activation |
| `POST /authorise` (+ amount+1, + decimal, + 4dp litres) | **yes — real Paystack initialisation** | the acknowledgement switch |
| `POST /transactions/upload` | **yes** | the switch, and a prior authorise |

The switch resets every time the panel is rebuilt. That is deliberate: a panel reopened next week
starts safe.

---

## Before you start

- [ ] Wi-Fi on the tablet, with real internet — this build talks to the live backend.
- [ ] USB free for `adb`. This build mocks the hardware, so the Arduino is not needed and the port is
      not contended. That is the whole reason 7h's bench session was painful.
- [ ] Confirm which app you are in: the panel header reads **API probe · LIVE SERVER** in gold and the
      Server line reads `https://api.balancee.app/`.

## Install

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew installDebugProd
```

---

## Step 1 — activate ✅ passed 2026-09-16

Operator settings (attendant PIN) → **Activation**. Recorded: pump
`3727aebf-3c77-4180-a818-4254cbeeae72`, device `ae2b7a83-…` matching the dashboard's Device ID
column, and credentials surviving a force-stop.

Outcomes are not interchangeable if you run it again: `Refused` means nothing was issued,
`Unreachable` means **unknown** and the same code should be retried rather than a fresh one,
`CredentialsLost` means the code is spent and the keys are gone.

## Step 2 — `GET /config` ✅ passed 2026-09-16

Captured at `docs/api-probes/2026-09-16-prod-config/`. The payload bore no resemblance to our DTO,
which has been rebuilt from those bytes. Re-run it at the start of any later session: the panel needs
a `/config` in hand before it will let you authorise anything, because the price has to come from the
server rather than from us.

## Step 6 (first half) — GET signing ✅ answered by step 2

A 200 on a body-less GET means `timestamp + "." + ""` is what the server verifies.

## Step 6 (second half) — the freshness window · **#15**

Press **GET /config signed 10 min ago**. Read the result backwards: **a refusal is the good outcome.**

- [ ] If refused — copy the `message` and any `code` **verbatim** into #15. Those strings have been
      guessed at since August (`Request timestamp is not fresh` vs `Invalid request timestamp`) and
      have never once been observed.
- [ ] If accepted — there is no freshness window, or it is wider than ten minutes, and #15's mapping
      is built around something that may not exist. Say so in the TODO rather than leaving it.

## Step 5 — `GET /transactions/{id}` · **#18d**

Leave the id blank to send `probe-not-a-real-id`, which is worth doing first: nobody has seen what
this endpoint says about a transaction that does not exist, and an authenticated 404 envelope is
fixture material of its own. Later, after an authorise, the field carries that transaction's id.

- [ ] Record every status string that appears. The real set has only ever been guessed
      (`PENDING_PAYMENT` → `PAID` → `DISPENSED`).

## Steps 3, 4 — `POST /authorise` · **#18c**, **#18f**

**These create real payment initialisations.** Before pressing anything here, the question in item 4
of `BOSS_CONFIRMATIONS_DRAFT.md` should have come back: does `/authorise` on production hit the live
Paystack? Do not scan any QR the URLs lead to.

Turn the switch on, set litres, and watch the amount line — it does the arithmetic **before** sending:

- **Both whole and fractional amounts send now.** Until phase 10b the panel *refused* on a fractional
  amount, because `amount` was a `Long` and the sale genuinely could not be expressed — that refusal
  is how #18c was first answered, and it is history rather than behaviour. `amount` is a `BigDecimal`
  as of 2026-09-17 and the amount line is now a label telling you which case you are on.

Then:

- [ ] **…amount +1** — expect a refusal. Record `message` **and** whether a `code` came with it; that
      is the whole of **#18f**, and matching interpolated prose breaks the day someone rewords it.
      *If it is accepted*, that is a bigger finding than the test: the exactness the Reference
      describes is not enforced.
- [ ] **…decimal amount** — sends a fractional `amount` as raw JSON, because our own DTO cannot carry
      one. If accepted, `amount` must become a decimal type before the payment flows (#8) are built.
      If refused, station pricing has to be constrained so `price × litres` is always whole — a
      business call, not a technical one.

## Step 7 — `POST /transactions/upload`

Needs the ids only a real authorise issues, so it lights up after step 3.

- [ ] A success is what the upload job (7e) has been waiting to be told — it means the ingest
      endpoint exists and works.

## Step 8 — pre-pay precision · **the one still unrun**

**Why it exists.** A pre-pay customer hands over a round sum. Litres quoted at 2dp cannot spend all
of it: ₦5,000 at ₦1,490/L buys 3.35 L, worth ₦4,991.50, and the server's check is **exact**, so
quoting the ₦5,000 actually tendered is a refused sale rather than a ₦8.50 discrepancy. Quoting finer
litres shrinks the customer's shortfall from up to `0.01 × price` (≈₦14.90 today) to **under a
kobo** — *if* the server accepts more than the one decimal place we have ever observed it take.

Phase **10c** cannot quote pre-pay litres until this is answered, and guessing wrong means either
giving fuel away or refusing sales.

**Prerequisites:** activated, a `GET /config` in hand, switch on, a litres figure entered.

Press **…4dp litres (pre-pay precision)**. The line under the button shows both scales before you
send — the tendered sum it is modelling, and the shortfall at 2dp and at 4dp.

- [ ] **Accepted** → the summary reads *ACCEPTED 4dp litres*. Record it, and **10c quotes pre-pay
      litres at 4dp.** The shortfall effectively disappears.
- [ ] **Refused** → the summary reads *REFUSED 4dp litres* as a **caution, not a failure**. That is
      half the answer, not a failed test: 10c quotes at 2dp and the customer's shortfall stands.
      Before settling, **re-run at 3dp** — change `scale = 4` in `AuthoriseVariant.Precision` and
      reinstall — because the boundary matters and two decimal places is the expensive answer.
      Copy the `message` and `code` verbatim; a refusal here is worth raising with the backend, since
      this is the difference between spending a customer's money and keeping some of it.
- [ ] Either way, record it against **#44** in `TODO.md` and under **10b** in the Phase 10 section.

**This creates a real Paystack initialisation, like every other write probe. Do not scan the QR.**

---

## Optional, and worth it while the pump is throwaway — revoke and re-activate

The dashboard has a **Revoke** button beside **Get code**. Nobody has tested what follows one, and it
matters beyond this sitting: the **signing cutover is a planned reinstall**, which wipes
`device_identity` prefs and mints a new `deviceId`, so the production build will activate as a
stranger to whatever the run build registered.

- [ ] Revoke, then `GET /config` again. Expect a 401 — record the exact message, the first time we
      will have seen a **revoked** credential rather than an absent or invalid one.
- [ ] **Get code**, clear the app's data (which also mints a fresh `deviceId` — exactly the cutover
      scenario), and redeem. The app refuses a second activation locally while it still holds
      credentials, which is why the data has to go first.
- [ ] Record whether the backend accepts a new `deviceId` for an already-activated pump. That is
      #31's second question, answered by observation.

---

## Getting the evidence off

**Save to file** after each probe, then:

```bash
# Git Bash rewrites a leading slash into a Windows path — MSYS_NO_PATHCONV=1 stops it.
MSYS_NO_PATHCONV=1 adb pull /sdcard/Android/data/app.balancee.smartpump.display.prod/files/api-captures/
```

Commit captures under `docs/api-probes/<date>-<what>/` **as bytes**, and build fixtures from the file
rather than from anyone's description of it — restating the shape is how #11 survived a green suite
for two months, and it is what made the `/config` DTO wrong for another two.

## When something goes wrong — logcat IS available here

7h's notes say logcat is unusable on this tablet. That was true **with the Arduino attached**: the
USB-C port cannot be an adb link and a USB host at the same time. This build mocks the hardware, so
the port is free and the ordinary tools work:

```bash
adb logcat -b crash -d          # the crash buffer, after the fact — this is how #42 was found
adb logcat -d | grep -i pump    # general
```

The crash buffer survives the app restarting, so it can be read calmly afterwards.

## After

Update **#32** in `TODO.md` with what each step returned, and log the sitting in `PROJECT_LOG.md`.
A shape that differs from ours is a finding, not a failure — it is the entire reason the gate exists.
