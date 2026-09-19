# Runbook — the payment gate (TODO #10g)

**Written 2026-09-19.** The #32 gate proved the *endpoints*, by hand, through the probe panel.
This one proves the *app*: a real customer walking up to the tablet, paying with a phone, and
getting fuel — with nobody touching a debug control at any point.

Modelled on [`GATE_32_RUNBOOK.md`](GATE_32_RUNBOOK.md), which is worth reading first if you have
not run one of these. Same tablet, same pump (`SN-TEST-001`), same live backend, same discipline:
**a shape that differs from ours is a finding, not a failure.**

Nothing on `feature/phase-10-payments` merges until this passes.

---

## ⚠️ Read this before you plug anything in

**Do not uninstall the `.prod` app.** The tablet is carrying the **schema v4** database that the
#32 gate wrote, including the real ₦149 transaction. Installing this build over it runs
`MIGRATION_4_5` **on real production data** — three `ALTER TABLE`s against a row written by an
older build. That is the only chance this project will ever get to test that migration on data it
did not create itself, and `adb uninstall` throws it away in one command.

It also sets up the best available test of 10f's queue filter. That ₦149 row is **pre-10f**: it
has `syncedAt` null but `paymentReference` null too, because the field did not exist when it was
written. `getPendingSync` filters on `paymentReference IS NOT NULL`, so the row must be **ignored
by the upload job forever**. If the pump log shows it being uploaded — or refused — the filter is
wrong in a way no JVM test would have caught, because every fixture in the suite was written by
code that knew about the column.

---

## Before you start

- [ ] Wi-Fi on the tablet, real internet. This build talks to `https://api.balancee.app/`.
- [ ] USB free for `adb`. `debugProd` mocks the hardware, so the Arduino is **not** needed.
- [ ] A phone that can scan a QR and actually pay — a real bank app, real money.
- [ ] Small amounts. Every `/authorise` here creates a live Paystack initialisation. ₦100–₦200.
- [ ] Confirm which app you are in before touching anything: application id ends **`.prod`**.

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew installDebugProd        # installs OVER the #32 install — do not uninstall first
```

### The one rule for the whole sitting

**Do not open the debug hotspot.** The top-left 40 dp long-press opens payment *force-resolve* and
live price editing (`MainActivity.kt:182`, `BuildConfig.DEBUG`). Both exist in this build because
it is `initWith(debug)`. Force-resolving a payment here does not prove a payment; it proves the
force-resolve button. If you need it to get unstuck, the step has **failed** — write down what
happened and say so.

---

## Step 0 — the migration, which has already happened by now

It ran when the app first opened after install. Nothing to do but confirm it survived.

```bash
adb logcat -b crash -d | head -40      # empty is the pass
```

- [ ] App opens to the idle screen rather than crashing.
- [ ] The pump log still shows the **#32 history** — a migration that silently dropped the table
      would give you a clean, plausible, empty log, which is the failure that looks like success.

**Pass:** app runs, old rows still there.

**Fail and stop:** a `SQLiteException` in the crash buffer. Do not reinstall to "fix" it — capture
the database first, because it is the evidence:

```bash
MSYS_NO_PATHCONV=1 adb exec-out run-as app.balancee.smartpump.display.prod \
  cat databases/smartpump.db > gate10g-v4.db
```

---

## Step 1 — credentials survived, and the price came from the server

- [ ] Operator settings (attendant PIN) → the pump is **still activated** from #32. It should not
      ask you to activate again; the credentials are in the KeyStore and the reinstall kept them.
- [ ] **The price on the idle screen is the server's, not the seed.** This build self-seeds a
      ₦870/L placeholder (`seedDefaultConfigIfMissing`, debug-only). 10c-bis's `PumpConfigSync`
      should have fetched `/config` on boot and overwritten it.
- [ ] The pump log carries a **`PRICE_SYNCED`** entry — written only when the number actually moved.

**This is 10c-bis's whole reason for existing.** Before it, the price the customer read and the
price `/authorise` was checked against were unrelated numbers. If the screen still shows ₦870/L,
stop: every amount below would be authorised against a price nobody agreed to.

**Record:** the price the screen shows, and the price `/config` returned.

---

## Step 2 — Flow 1, pre-pay digital: the sale this phase exists for

The main event. Through the customer UI, start to finish, no shortcuts.

1. [ ] Idle → **Pay** → enter a small naira amount (₦100–₦200) → confirm.
2. [ ] A **QR appears**, and it is a Paystack checkout URL — not the old fabricated
       `nip://transfer?account=…` payload, which no scanner resolves and no bank honours.
3. [ ] **Scan it with the phone and actually pay.**
4. [ ] Within ~10 s of the payment clearing, the tablet moves off the waiting screen **on its own**
       — that is 10d's poll over `GET /transactions/{id}` terminating on `PAID`. Nobody taps
       anything.
5. [ ] Fuel dispenses (mock pulses) and stops at the paid-for volume.
6. [ ] The completion screen shows the **struck price**, and it matches the receipt (#37).

**Record:** the naira amount, the litres, the `transactionId`, the `paymentReference`, and **how
long** the poll took to notice. That last number is one nobody has: every poll figure in this
project so far came from a mock with a fixed delay.

**Watch the 5 ml question.** 10d fixed the pump stopping short of what was paid for — the quote's
payable litre step and `DeviceConfig.litresCutoff` disagreed. Check litres delivered against
litres quoted, to the millilitre.

---

## Step 3 — the upload job, on a real record

10f's first contact with the live server, and the first dispense this app has ever been *able* to
report.

- [ ] Shortly after the sale, the record uploads. `syncedAt` is set.
- [ ] **No red `DISPENSE_UPLOAD_FAILED`** in the pump log.
- [ ] **The #32 ₦149 row was never offered** — see the warning at the top.

```bash
adb shell dumpsys jobscheduler | grep -A 5 -i smartpump   # the worker's real scheduling, unrun until now
```

Then the ordinary check, which is also the #48 check — pull the database and look:

```bash
MSYS_NO_PATHCONV=1 adb exec-out run-as app.balancee.smartpump.display.prod \
  sqlite3 databases/smartpump.db \
  "SELECT id, amount, paymentReference, syncedAt, uploadError FROM transactions ORDER BY createdAt DESC LIMIT 5;"
```

**Pass:** today's row has a `syncedAt` and a `paymentReference`, no `uploadError`; the ₦149 row is
untouched, `syncedAt` still null, and nothing ever tried.

---

## Step 4 — pull the plug, which is the point of a durable queue

10f is a WorkManager job rather than a call at the end of a sale for exactly one reason: a
forecourt loses its internet. Prove it.

1. [ ] Run another small sale to the point of **fuel delivered**.
2. [ ] **Airplane mode on** before the upload goes out.
3. [ ] Confirm the record sits there — `syncedAt` null, `uploadError` null. Waiting, not condemned.
4. [ ] **Force-stop the app:** `adb shell am force-stop app.balancee.smartpump.display.prod`
5. [ ] **Airplane mode off.** Do not reopen the app.
6. [ ] The queue drains **without the app being opened** — that is the durable half, and the part
      a JVM test cannot show.

**Pass:** `syncedAt` gets set while the app is closed.

---

## Step 5 — the boot-resume trap, and the only step that can cost a customer money

10d's reason for being its own deliverable. `CustomerViewModel:1095` restarts a `process()` call
after a restart; against a mock that is free, against a real server it **authorises a second sale
for a customer who has already paid**.

1. [ ] Start a pre-pay sale and get to the QR screen. **Note the `transactionId`.**
2. [ ] **Do not pay yet.**
3. [ ] Force-stop the app, then reopen it.
4. [ ] It comes back to the waiting screen **on the same `transactionId`**, with the original
       `expiresAt` — resumed, not re-granted (`resume(ref, request, deadline)`).
5. [ ] **Now pay.** It should complete normally.

**Fail — and stop the sitting — if:** a second `transactionId` appears, a second Paystack
initialisation is created, or the restored screen shows a fresh countdown. Any of those means a
customer can pay once and be charged twice, and nothing below matters until it is fixed.

---

## Step 6 — Flow 3, fill-up digital

Shorter, because 10d fixed it alongside Flow 1 and the mechanism is shared.

- [ ] Fill-up to tank-full, then **pay digital**.
- [ ] The screen **holds** on `FillupTankFull` until the checkout URL arrives, then shows the QR.
- [ ] Pay it. It completes and uploads as in step 2.

---

## Step 7 — collect one real failure, on purpose

10e keyed its error copy on the server's `code` and **never on a string nobody has seen**. Three
Catalogue A rows are **parked** pending exactly one observation each. This is the cheapest chance
to un-park one.

Easiest to provoke: let a QR **expire** without paying, and read what comes back.

- [ ] The customer line is one plain sentence — **not** the server's raw prose.
- [ ] The attendant line carries the diagnostic, with the status and the `code`.
- [ ] Capture the code and the message **verbatim**, as bytes.

Anything unrecognised falls to the catalogue's last row and shows the server's own words. That is
working as designed — but write the code down, because it is a row we can un-park later.

---

## Step 8 — the instrumented tests, which have never run on a device

Carried over from 10f. Run these **last**: the Room tests build their own databases, and you do
not want them anywhere near the real one before step 3 has read it.

```bash
./gradlew connectedDebugProdAndroidTest
```

- [ ] 11 migration tests (4 of them new in 10f) green
- [ ] 5 Keystore + 2 deviceId tests green

---

## Getting the evidence off

```bash
MSYS_NO_PATHCONV=1 adb pull /sdcard/Android/data/app.balancee.smartpump.display.prod/files/api-captures/
```

Commit under `docs/api-probes/<date>-10g/` **as bytes**. Build any fixture from the file, never
from a description of it — restating the shape is how #11 survived a green suite for two months,
and it is what made the `/config` DTO wrong for another two.

## When something goes wrong

logcat works here — the Arduino is not attached, so the USB-C port is not being asked to be an adb
link and a USB host at the same time.

```bash
adb logcat -b crash -d
adb logcat -d | grep -i -E "pump|upload|payment"
```

## After

- Update **#10g** in `TODO.md` with what each step returned.
- Log the sitting in `PROJECT_LOG.md`.
- Only then merge `feature/phase-10-payments` to `main`.

---

## What a pass actually licenses

A pass means the payment flows work, on real money, against the real backend, on the real tablet.
It does **not** mean the app is ready for the parallel run. Still outstanding after this, and none
of it in scope here:

- **The K-factor is still a placeholder.** Every litre figure in this gate is nominal. Step 2's
  millilitre check proves the *arithmetic* is consistent, not that it matches a meter.
- **No build type is both real hardware and production** (noted in TODO under 10d). This gate runs
  real payments on mock pulses; 7h's bench gate ran real pulses against the dev backend. The
  release build will be the first time the two run together — that should be a deliberate step,
  not a discovery on a forecourt.
- **This is a debug build.** It self-seeds config and carries the hotspot. `V1_BLOCKERS.md` says
  why that disqualifies it from the run.
