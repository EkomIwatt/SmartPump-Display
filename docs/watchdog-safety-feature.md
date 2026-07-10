# Comms-Loss Safety Watchdog — Feature Summary

**Prepared:** 2026-07-10
**Status:** Built, verified on the tablet + Arduino rig, and merged to `main` (merge commit `9b76f42`).
**Audience:** non-technical — this explains *what* the feature is, *why* it exists, and *how we proved
it works*. Engineering detail lives in `PROJECT_LOG.md` (Phase 7a-hardening entries) and the code.

---

## In one sentence

We added a safety cut-off that guarantees **fuel stops flowing if the pump display ever loses contact
with the pump hardware mid-sale** — even if the tablet app freezes, crashes, or the data cable drops —
so fuel can never keep pouring while nobody is in control.

---

## Why this wasn't in the original plan

The original design materials (the screen designs, the transaction flows, the state machine) describe
what the **customer sees and does**: tap to pay, choose an amount, watch the litres count, get a
receipt. They assume the hardware underneath is always talking to the app.

The watchdog is a **hardware-safety layer**, not a customer flow — so it wasn't part of that original
spec. The need for it only became visible once we had **real hardware on the bench** (Phase 7a, June
2026). During bench testing we asked the obvious safety question: *"What happens to the fuel if the
app or the cable fails in the middle of a live dispense?"* On the original build, the honest answer was
"nothing stops it on its own." That is not acceptable for a device that controls a fuel pump, so we
scoped this hardening pass to close it. It is tracked as **Open Question #21** in the project's decision
log.

---

## The hazard it addresses

A fuel dispense is the app telling the pump "open the valve, fuel is authorised." The danger is any
situation where the valve is **open** but the app has **lost control**:

- the tablet app freezes or crashes while fuel is flowing;
- the USB data cable between the tablet and the pump adapter is knocked loose or drops;
- the tablet reboots mid-sale.

Without a safeguard, the valve could stay open — over-pouring fuel that nobody is measuring or billing.

---

## What we built (three parts, plain language)

1. **A "still-alive" signal from the tablet.** While a sale is running, the app sends the pump adapter
   a tiny heartbeat message about **once a second** — effectively "I'm still here, keep going."

2. **A dead-man switch on the pump adapter.** The little board at the pump listens for that heartbeat.
   If it goes quiet for **~3 seconds**, the board assumes contact is lost and **shuts the fuel valve
   on its own** — using its own electronics, without needing any further instruction from the tablet.
   Crucially, in production this board is powered from the station's **UPS (battery backup)**, not from
   the tablet, so it stays alive and in control even if the tablet or the data link dies. Once it has
   cut fuel this way, it will **never turn fuel back on by itself** — the app has to explicitly
   re-authorise. That's the safe default: when in doubt, stay off.

3. **Automatic recovery from a brief glitch.** If the link drops only for a moment and comes right
   back, the app notices the reconnection and **re-authorises the valve automatically**, so a
   momentary hiccup doesn't ruin a legitimate sale. The litre count picks up where it left off.

The net rule is simple and easy to state to a regulator or a station owner: **"Lose contact, stop
dispensing."**

---

## How we verified it (on the real rig, 2026-07-10)

Tested on the actual tablet (Samsung Galaxy Tab A7 Lite) driving an Arduino pump adapter:

- **The safety case — passed.** With fuel actively flowing, we force-killed the tablet app to
  simulate a freeze/crash. The app died instantly, and **~3 seconds later the pump relay physically
  clicked off on its own** — exactly as designed. Fuel stopped with no app in control.
- **Normal operation — unaffected.** Six sales back-to-back (fill-ups and a pre-pay) all ran normally
  to completion, with the heartbeat holding steady the entire time. The safety layer adds no
  interference to ordinary dispensing.
- **Reconnect — works.** Unplugging and replugging the cable produced a clean recovery, and the next
  sale ran fine.

An earlier bench session had shown sales cutting off early; we traced that to an **unstable power
quirk of the test bench** (the bare Arduino browning out when powered from the tablet's own USB port),
**not** a fault in the feature. Production avoids it entirely by powering the adapter from the UPS, and
even that glitch fails *safe* (it stops fuel, never over-pours). On a correctly powered build the early
cut-offs did not recur.

---

## Known limitation (documented, not a safety risk)

We simplified one part after it caused bugs: the app no longer shows a dedicated "pump disconnected"
screen. Under the current assumption that **the USB cable is fixed inside the kiosk** (it can't be
yanked; the only realistic failure is a power cut, which the UPS and the app's power-cut recovery
already handle), this is fine.

The one residual edge case: if a **pre-paid** sale suffered a *permanent* mid-dispense disconnect, the
customer screen would sit on "dispensing" indefinitely instead of showing an error — **though the fuel
is physically off the whole time, so it is safe, just visually stuck.** Whether V1 needs a tidy on-screen
recovery for that (vs. relying on the fixed-cable assumption) is logged as an open decision —
**Open Question #22** — for a product call.

---

## Bottom line for sign-off

- The pump **cannot keep dispensing fuel while the controlling app is dead or disconnected** — proven
  on the real hardware.
- Normal sales are **unaffected**.
- It is an addition beyond the original screen/flow spec, prompted by real-hardware safety testing, and
  is now merged and part of the product.
