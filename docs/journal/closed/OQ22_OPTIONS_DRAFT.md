# Stuck fixed-flow sales — options for OQ #22

_Drafted 2026-09-15 so OQ #22 can be settled by picking, the way OQ #17 was._

**Status: DECIDED 2026-09-15 — Option 1**, with the recommended record (litres flowed, amount paid,
audit note) and the draft customer line. Built on `feature/oq22-end-sale`. The options below are
kept as the reasoning for the choice.

---

## What actually happens today

Checked against the code on `main` (`d29098e`). Two corrections to the OQ #22 text are in bold.

A fixed sale is pre-pay, USSD or cash-fixed: the customer has paid for a set amount and the pump
stops at that litre target. There are exactly **two** ways out of `FixedDispensing` /
`CashFixedDispensing`:

1. The pulse count reaches the target → `Complete`, audit row written.
2. Nothing else. The dispensing screen has no cancel button, and the attendant panel offers no
   action while a fixed sale is running (`AttendantOverlay.kt` only enables its cards on `Idle`,
   `FillupAwaitingAttendantAuth` and `FillupAwaitingCashConfirm`). **"Clears on attendant action" is
   not true.**

**A power cycle does not clear it either.** Boot resume restores `FixedDispensing`, finds the target
not met, and restarts the dispense with the relay commanded on (`CustomerViewModel.kt` boot resume,
`FixedDispensing` branch). If whatever stopped the pulses is still true, the pump is stuck again.
In practice the only exits are the target, the debug hotspot, or clearing app data.

### Two ways to get stuck

| | A. Link lost | B. Flow stopped before target |
|---|---|---|
| What happens | USB link to the adapter drops and stays down | Link is fine; the tank fills first, or the customer stops early |
| Relay | **Off** — the firmware watchdog drops it within 3 s of the PINGs stopping | **Still on** — the app keeps PINGing and never sends `RLY:0` |
| Frames arriving | None — no `PULSE`, no `HB` | `HB` every ~2 s, no `PULSE` |
| How common | Rare under the fixed-cable kiosk assumption | **Routine** — anyone who over-prepays |
| Money | Customer paid for more than they got | Same |

**B is the one OQ #22 did not name, and it is the more important one.** It needs no fault at all:
a customer pre-pays ₦10,000, their tank takes ₦8,000, and the pump sits in `FixedDispensing` for
good. While the relay is on, whoever lifts the nozzle next can draw the remaining litres. (That last
point only matters if the relay gates real fuel on day one, which `BRANCH_7G_SUMMARY.md` recommends
against. The stuck screen matters either way.)

Fill-up is not affected: it already has a 3 s no-pulse watchdog that moves to `FillupTankFull` and
bills what flowed, which is correct for an open-ended sale.

`docs/state-machine.md`'s Universal table says a hardware disconnect should go to
`Error(recoverable=true)`. The code has not done that since `PumpDisconnected` was removed on
2026-07-08, and doing it now would be wrong: `Error` writes no audit row, so a prepaid sale would
vanish from the records. Whatever is chosen, that row gets corrected.

---

## The options

All three end a stuck sale the same way. They differ only in **what decides to end it**.

**Ending a sale early** means: send `RLY:0`, then `Complete` with **litres = what actually flowed**
and **amount = what was paid**, plus an audit note that it ended short. That record is honest, it
matches the receipt's struck-price rule from #37 (₦10,000 · 8.00 L · ₦1,000/L), and it is exactly
what `/transactions/upload` wants to carry: `actualLitresDispensed`. The backend learns the
shortfall from data it already expects.

### Option 1 — attendant "End sale" button

A fourth state-dependent action in the attendant panel, enabled only during a fixed sale, behind
the existing PIN. Tapping it ends the sale early as above.

- **Covers:** A and B.
- **False positives:** none. A person decides.
- **Size:** small. One ViewModel function, one enabled flag, one test file.
- **Weakness:** relies on the attendant noticing. On a Nigerian forecourt the attendant is holding
  the nozzle, so in practice they will.

### Option 2 — automatic timeout

The dispense loop ends the sale early on its own:

- **A:** no frames of any kind for **~10 s**. `HB` arrives every ~2 s, so that is five missed
  beats; long enough to ride out a USB re-enumeration, short enough that nobody waits long.
- **B:** `HB` still arriving but no `PULSE` for **~60 s**. It has to be long, because a customer
  pausing mid-fill must not be cut off early.

- **Covers:** A and B.
- **False positives:** real ones. A 60 s pause, or a USB drop that would have self-healed at 12 s,
  ends a paid sale early. That is the software short-changing a customer, which is worse than a
  stuck screen.
- **Size:** medium. Two timers, and both numbers are guesses until the bench measures them.
- **Weakness:** the B threshold trades a stuck screen for an early cut-off, with no right value.

### Option 3 — both

The attendant button, plus the **link-loss timer only** (A, ~10 s). No flow timer for B.

- **Covers:** A automatically; B by the attendant.
- **False positives:** only a USB drop longer than ~10 s, and fuel is already off by then.
- **Size:** Option 1 plus one timer.

**Not offered: automatic refund.** "Resume-else-refund" needs the backend's refund policy (OQ #7,
still open on their side). Ending the sale with an honest record is what the pump can do alone.

---

## Recommendation: Option 1

It fixes the case that will actually happen (B) with no timer to get wrong, and it fixes A too,
since an attendant standing at a frozen screen will press it. A itself is rare under the fixed-cable
assumption. If field use shows attendants missing link-loss cases, the Option 3 timer can be added
later without changing anything Option 1 builds.

---

## Decisions needed

1. **Which option.** Recommended: 1.
2. **The record.** Recommended: litres = dispensed, amount = paid, audit note
   `"Ended by attendant at 8.00 of 10.00 L"`. The alternative, amount = litres × price, would make
   the record claim the customer paid less than they did.
3. **Customer copy on the completion screen.** Draft: *"Sale ended early — 8.00 of 10.00 L. Please
   see the attendant."* ⚠️ No design screen covers this, so the line is invention and needs
   approval, same flag as the error screen.
4. **What happens to the shortfall** is out of the pump's hands: cash back from the attendant for
   cash-fixed, backend policy (OQ #7) for digital. Worth confirming the attendant knows that's
   theirs to settle.
