# Runbook — the activation gate (TODO #32)

How to run the gate on a tablet, and what to do with what comes back. Written 2026-09-16 against
`Test Pump 1` / `SN-TEST-001`, a throwaway pump on a dummy business account, on **production**.

**Read first:** TODO **#31** (settled — why production is the right target here), **#32** (the steps,
and why they must go through `PumpApiClient`).

**Status:** steps 1 and 2 **passed** on 2026-09-16 and their findings are logged. Steps 3–7 are built
(stage 9d-2) and unrun.

---

## What the panel can do

| probe | writes anything? | gated on |
|---|---|---|
| `GET /config` | no | activation |
| `GET /transactions/{id}` | no | activation |
| `GET /config` signed 10 min ago | no | activation |
| `POST /authorise` (+ amount+1, + decimal) | **yes — real Paystack initialisation** | the acknowledgement switch |
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

- **A whole-naira amount** (e.g. 2.0 L × 1490 = 2980) → press **POST /authorise**.
- **A fractional amount** (e.g. 2.35 L × 1490 = 3501.5) → the panel refuses to send and says so. This
  is #18c answered by arithmetic rather than by asking: `amount` is a `Long`, the server checks
  `amount == litres × price` **exactly**, so a rounded figure is refused rather than accepted a few
  kobo out. Every metered fill-up lands here.

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
