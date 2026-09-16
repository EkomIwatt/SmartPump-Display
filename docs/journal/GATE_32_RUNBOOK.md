# Runbook — the activation gate (TODO #32)

How to run the gate on a tablet, and what to do with what comes back. Written 2026-09-16, when the
only code we hold is a **production** one for `Test Pump 1` / `SN-TEST-001` on a dummy business
account created by the backend team.

**Read first:** TODO **#31** (why the environment matters), **#32** (the steps and why they must go
through `PumpApiClient`), **#41** (what is still missing if dev ever issues a key pair instead).

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
upload) are **not built**. They create transactions, and nothing should create one until it is
settled which server is acceptable to dirty.

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

## After

Update **#32** in `TODO.md` with what each step returned, and log the sitting in `PROJECT_LOG.md`.
If `/config`'s shape differs from `PumpConfigResponse`, that is a finding, not a failure — it is the
entire reason the gate exists, and 7b's second half has been waiting on it.
