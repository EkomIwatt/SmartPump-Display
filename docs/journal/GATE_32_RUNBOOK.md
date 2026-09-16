# Runbook — the activation gate (TODO #32)

How to run the gate on a tablet, and what to do with what comes back. Written 2026-09-16, when the
only code we hold is a **production** one for `Test Pump 1` / `SN-TEST-001` on a dummy business
account created by the backend team.

**Read first:** TODO **#31** (settled — why production is the right target here), **#32** (the steps,
and why they must go through `PumpApiClient`).

---

## What exists after stage 9d-1

- A **`debugProd`** build type: the `debug` app — mock hardware, debuggable, self-seeding config —
  pointed at `https://api.balancee.app/`. Application id `app.balancee.smartpump.display.prod`, so
  it installs beside the dev app and keeps its own credentials and deviceId.
- An **API probe panel** on the operator settings screen, below activation, debug builds only. It
  prints the server it is talking to, runs `GET /config` through the real `PumpApiClient`, and shows
  the **literal response bytes**.
- A **capture file** written to external files storage, so evidence leaves the tablet by `adb pull`
  rather than logcat — which this tablet has already proved unreliable (7h).

Steps 3–7 of #32 (`/authorise`, the deliberate mismatch, the decimal amount, the status poll, the
upload) are **not built yet** — stage 9d-2. Building them is cleared; **pressing** the authorise
button waits on one question, because `/authorise` returns a Paystack checkout URL and on production
that is presumably the live Paystack (item 4 of `BOSS_CONFIRMATIONS_DRAFT.md`). Nothing in the steps
below is affected.

---

## Before you start

- [ ] Wi-Fi on the tablet, with real internet — this build talks to the live backend.
- [ ] USB free for `adb`. This build mocks the hardware, so the Arduino is not needed and the port
      is not contended. That is the whole reason 7h's bench session was painful.
- [ ] The activation code to hand, and the dashboard open on the pump row — after activation its
      **Device ID** column should fill in, which is an independent check on the echo the app makes.

## Install

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew installDebugProd
```

Confirm on the tablet that you opened the right app: the launcher will show two (or three) SmartPump
icons. The probe panel's header reads **API probe · LIVE SERVER** in gold, and the Server line reads
`https://api.balancee.app/`. If it does not, you are in the dev app.

## Step 1 — activate

Attendant overlay → operator settings (attendant PIN) → **Activation**.

Type the code, press **Activate this pump**. Then:

- [ ] Record what the panel says, verbatim. The outcomes are not interchangeable: `Refused` means
      nothing was issued, `Unreachable` means **unknown** and the same code must be retried rather
      than a fresh one, `CredentialsLost` means the code is spent and the keys are gone.
- [ ] Check the dashboard: status should leave *Pending activation*, and **Device ID** should equal
      the Device ID shown in the panel. A disagreement is `IdentityMismatch` and worth stopping for.
- [ ] **Kill the app and reopen it.** The panel must still read *activated*. This is #32 step 1's
      real content — a credential set that does not survive a process restart is not stored.

## Step 2 — `GET /config`

Probe panel → **GET /config**.

- [ ] Read the summary. **A green "200 OK" is not the finish line.** The case to watch for is
      *"200 OK, but zero prices parsed"*: our `prices` field defaults to an empty map, so a server
      that names it anything else parses cleanly into nothing. That is defect #11's exact shape.
- [ ] **Save to file**, then pull it:

```bash
adb pull /sdcard/Android/data/app.balancee.smartpump.display.prod/files/api-captures/
```

(The panel prints the exact path and the `adb pull` line after saving.)

- [ ] Commit the capture under `docs/api-probes/<date>-prod-config/` **as bytes**. Build the fixture
      from that file, never from a restatement of it — restating the shape is how #11 survived a
      green suite for two months.

## Optional, and worth it while the pump is throwaway — revoke and re-activate

The dashboard has a **Revoke** button beside **Get code**. Nobody has ever tested what happens after
one, and the answer matters beyond this sitting: the **signing cutover is a planned reinstall**,
which wipes `device_identity` prefs and mints a new `deviceId`, so the production build will
activate as a stranger to whatever the run build registered. This is the cheapest chance to find out
what that looks like.

- [ ] Revoke on the dashboard, then `GET /config` again from the app. Expect a 401 — record the exact
      message, because this is the first time we will have seen a **revoked** credential rather than
      an absent or invalid one.
- [ ] **Get code**, redeem it in the panel. The app refuses a second activation **locally** while it
      still holds credentials (`PumpActivationRepositoryImpl`), so clear the app data first — that
      also mints a fresh `deviceId`, which is exactly the cutover scenario.
- [ ] Record whether the backend accepts a new `deviceId` for a pump that has already been activated
      once. That is TODO #31's second question, answered by observation rather than by asking.

## When something goes wrong — logcat IS available here

7h's notes say logcat is unusable on this tablet. That was true **with the Arduino attached**: the
USB-C port cannot be an adb link and a USB host at the same time. This build mocks the hardware, so
the port is free and the ordinary tools work:

```bash
adb logcat -b crash -d          # the crash buffer, after the fact — this is how #42 was found
adb logcat -d | grep -i pump    # general
```

The crash buffer survives the app restarting, so it can be read calmly after the fact rather than
being captured live.

## After

Update **#32** in `TODO.md` with what each step returned, and log the sitting in `PROJECT_LOG.md`.
If `/config`'s shape differs from `PumpConfigResponse`, that is a finding, not a failure — it is the
entire reason the gate exists, and 7b's second half has been waiting on it.
