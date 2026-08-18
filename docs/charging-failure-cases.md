# PowerTap — Charging & Connection Failure Cases

Checklist of user-facing failure cases to handle, message, and test.  
Work top-down; mark status as you go: `[ ]` todo · `[~]` in progress · `[x]` done.

**UX principle (recommended):**  
Do not rely on a short Toast alone. For failures that block charging or connecting, show a clear dialog or persistent banner with:

1. **What happened** (plain language)  
2. **Likely why** (when we know)  
3. **What to do next** (primary action button)

Map firmware/Firebase/BLE outcomes to a small set of `UserFacingError` codes so the UI never shows raw stack traces.

---

## Suggested error presentation

| Severity | UI | Example |
|----------|----|---------|
| Soft warning | Toast / snackbar | “Device may be offline — retrying…” |
| Blocking action failure | Dialog + reset slider | “Couldn’t start charging” + reason |
| Device unsafe / fault | Banner on Home (stays until cleared) | “Overload detected — charging stopped” |
| Someone else using it | Dialog | “This PowerTap is already in use” |

---

## A. Before start (preflight — block slide or warn first)

| ID | Case | How to detect today | Suggested user message | Next action | Status | Test notes |
|----|------|---------------------|------------------------|-------------|--------|------------|
| A1 | No device selected / empty device ID | `deviceId.isEmpty()` | “Select or add a PowerTap first.” | Open add-device menu | [ ] | Empty Home + slide |
| A2 | Device offline (no BLE, no recent Firebase heartbeat) | `!isOnline` already disables slider | “Device is offline. Move closer or check power/Wi‑Fi.” | Retry / open scan | [ ] | Power off charger |
| A3 | BLE connected but Firebase path missing | Monitor node `!exists` | “Device not found on the network. Check Device ID.” | Re-add via QR / enter ID | [ ] | Wrong ID saved |
| A4 | Wrong device ID (BLE MAC used as ID, e.g. `…3dd` vs `…3dc`) | Monitor empty / no heartbeats while BLE up | “Device ID looks wrong. Reconnect with QR or correct ID.” | Re-pair | [ ] | Save BLE MAC as ID |
| A5 | Bluetooth off on phone | Adapter disabled | “Turn on Bluetooth to control this PowerTap.” | Open BT settings | [ ] | Toggle BT off |
| A6 | Missing BT / location permissions | Permission checks | “Bluetooth permission is required.” | Request permission | [ ] | Deny permission |
| A7 | Phone has no internet (Firebase command path) | Network callback | “No internet. Start may still work over Bluetooth if connected.” | Continue / wait | [ ] | Airplane mode + BLE on |
| A8 | Slider used while already STARTING/STOPPING | `currentState` | “Please wait — command in progress.” | Disable slider | [ ] | Double-slide quickly |

---

## B. Start charging fails / times out

| ID | Case | How to detect | Suggested user message | Next action | Status | Test notes |
|----|------|---------------|------------------------|-------------|--------|------------|
| B1 | Start command timeout (no state change) | 15s `COMMAND_TIMEOUT`, still `STATE_STARTING` | “Start timed out. The charger didn’t confirm. Check cable, power, and try again.” | Reset to Available | [ ] | Disconnect BLE mid-start; block Firebase |
| B2 | Firebase write failed | `onFailure` on Commands path | “Couldn’t send start command (cloud error).” | Retry | [ ] | Rules deny / offline |
| B3 | Firebase ack never arrives (listener hangs) | Same as timeout; listen has no timeout today | Same as B1; also cancel response listener | Retry | [ ] | Write succeeds, device dead |
| B4 | Device rejects RemoteStart | Parse CallResult `status` ≠ Accepted (or Rejected/Blocked) | “Charger rejected start: {reason}.” | Show reason | [ ] | Needs firmware Rejected statuses |
| B5 | Missing / invalid transaction id | Firmware logs missing `tid` | “Start failed — invalid session. Try again.” | Retry | [ ] | Send bad payload |
| B6 | Connector / cable not plugged | If firmware exposes fault / state | “Plug in the vehicle cable, then try again.” | Retry | [ ] | Unplugged EV |
| B7 | Fault states on start | `STATE_POWER_FAIL`, `LOW_VOLTAGE`, `OVERLOAD`, `UNKNOWN_FAULT` | Specific copy per state (see F*) | Don’t allow start | [ ] | Induce fault if possible |
| B8 | BLE send failed while “online” | `bleTransport.send` false | “Couldn’t reach charger over Bluetooth. Reconnecting…” | Rescan / reconnect | [ ] | Kill BLE during send |

---

## C. Already in use / multi-user / session ownership

| ID | Case | How to detect | Suggested user message | Next action | Status | Test notes |
|----|------|---------------|------------------------|-------------|--------|------------|
| C1 | Second phone tries to **BLE connect** while first holds GATT | Connect fails / disconnects other / never reaches Connected | “Another phone is already connected to this PowerTap.” | Wait / use cloud if allowed | [ ] | 2 phones, one connected |
| C2 | Device already **charging** (any user) | Firebase/BLE `state` = CHARGING/STARTED before local start | “This PowerTap is already charging. You can’t start a new session.” | Show live status; Stop only if owner | [ ] | Phone A charging, Phone B opens app |
| C3 | Device in STARTING/STOPPING by someone else | Remote state transient, local idle | “Charger is busy starting/stopping. Try again in a moment.” | Wait | [ ] | Race two starts |
| C4 | Stop by non-owner | Local `transactionId` null / tid mismatch | “You didn’t start this session, so you can’t stop it from this phone.” | Contact owner / wait end | [ ] | Phone B tries stop |
| C5 | Same account, two phones | Same Firebase user, two devices | Prefer: later connect wins + notify first; or session lock by `tid`+`account` | Define product rule | [ ] | Same login, 2 phones |
| C6 | Stale “charging” UI after other user stopped | Heartbeat/state lag | Refresh from monitor; don’t trust local-only state | Auto-sync | [ ] | Stop on A, watch B |
| C7 | Session lock in cloud | Optional: `PowerTapMonitor/{id}/sessionOwner` | “In use by {masked user} since {time}.” | — | [ ] | Requires backend field |

**Product recommendation for C1–C4:**  
Treat charger state as source of truth. Before `RemoteStart`, if `state ∈ {STARTING, STARTED, CHARGING}`, **block** and show C2/C3.  
For BLE exclusivity, on connect failure after timeout show C1.  
Only allow Stop if this app’s `transactionId` matches the active session (or account is session owner).

---

## D. Stop charging fails

| ID | Case | How to detect | Suggested user message | Next action | Status | Test notes |
|----|------|---------------|------------------------|-------------|--------|------------|
| D1 | Stop timeout | 15s still STOPPING | “Stop timed out. If charging continues, try again or unplug safely.” | Revert UI to Charging | [ ] | Drop link mid-stop |
| D2 | No transaction ID locally | `transactionId == null` | “No active session on this phone to stop.” | Refresh state | [ ] | Already partially handled |
| D3 | Stop rejected by device | CallResult Rejected | “Couldn’t stop: {reason}.” | Retry | [ ] | Firmware |
| D4 | Stop succeeded on device but UI stuck | State not updated | Sync from Firebase monitor | Force refresh | [ ] | Kill app mid-ack |

---

## E. Connection / pairing (add device & stay connected)

| ID | Case | How to detect | Suggested user message | Next action | Status | Test notes |
|----|------|---------------|------------------------|-------------|--------|------------|
| E1 | Scan finds nothing | Empty list after scan window | “No PowerTaps found. Move closer and scan again.” | Rescan / QR | [ ] | Far away / powered off |
| E2 | OS-bonded (classic paired) blocks app GATT | Existing bonded dialog | Keep current “Unpair in Bluetooth settings” flow | Settings | [ ] | Pair in system BT first |
| E3 | QR invalid | Parse fails | “Invalid PowerTap QR code.” | Retry | [ ] | Already partly handled |
| E4 | Connect timeout after scan/QR | Connecting never → Connected | “Couldn’t connect over Bluetooth.” | Retry scan | [ ] | Out of range after QR |
| E5 | Dropped mid-session | `ConnectionState.Disconnected` while charging UI | “Connection lost. Status may still update over the cloud.” | Reconnect | [ ] | Walk away |
| E6 | Duplicate / wrong IDs in list | Deduped prefs/scan | Should show one `PowerTap_<correctId>` | — | [~] | Recently improved |
| E7 | Auto-connect to wrong last device | Last address stale | “Connected to {name}. Switch device?” | Selector | [ ] | Two chargers |

---

## F. Device fault / runtime states (show even if not starting)

From `DeviceState` in app:

| ID | State | Suggested user message | Allow start? | Status | Test notes |
|----|-------|------------------------|--------------|--------|------------|
| F0 | AVAILABLE (0) | Ready | Yes | [ ] | |
| F1 | STARTING (1) | “Starting…" | No | [ ] | |
| F2 | STARTED (2) | “Charging started” | No (show Stop) | [ ] | |
| F3 | CHARGING (3) | “Charging” | No (show Stop) | [ ] | |
| F4 | STOPPING (4) | “Stopping…” | No | [ ] | |
| F5 | STOPPED (5) | “Stopped — ready” | Yes | [ ] | |
| F6 | POWER_FAIL (6) | “Power failure on charger. Check supply.” | No | [ ] | |
| F7 | LOW_VOLTAGE (7) | “Voltage too low to charge safely.” | No | [ ] | |
| F8 | OVERLOAD (8) | “Overload detected. Charging stopped.” | No | [ ] | |
| F10 | UNKNOWN_FAULT (10) | “Charger fault. Power cycle the device.” | No | [ ] | |

---

## G. Auth / account / cloud

| ID | Case | Suggested user message | Status | Test notes |
|----|------|------------------------|--------|------------|
| G1 | Logged out mid-flow | Return to login; don’t leave half UI | [ ] | |
| G2 | Firebase rules deny Commands write | “Not allowed to control this device.” | [ ] | |
| G3 | Clock / serverTimeOffset weird → false offline | Prefer BLE online bit; widen heartbeat window carefully | [ ] | |

---

## Implementation order (suggested)

1. **B1 + structured timeout dialog** (you already toast; upgrade copy + reset slider reliably)  
2. **C2 preflight** — refuse start if monitor state already charging  
3. **C1 BLE busy** — connect timeout message  
4. **C4 stop ownership** — don’t offer Stop without matching `tid`  
5. **B4 parse Rejected** from OCPP/Firebase response body  
6. **F6–F10 banners** for fault states  
7. **C7 session owner** in Firebase if multi-user is a real product requirement  

---

## Minimal code shape (for later)

```text
sealed class ChargeBlocker {
  data object NoDevice : ChargeBlocker()
  data object Offline : ChargeBlocker()
  data object AlreadyCharging : ChargeBlocker()
  data object BusyTransition : ChargeBlocker()
  data object BleInUseByOtherPhone : ChargeBlocker()
  data class Timeout(val action: StartOrStop) : ChargeBlocker()
  data class Rejected(val reason: String) : ChargeBlocker()
  data class Fault(val state: Int) : ChargeBlocker()
}

fun message(blocker): Pair<title, body>
fun preflightStart(): ChargeBlocker?
```

Call `preflightStart()` before sending RemoteStart; on timeout/reject map to the same dialog helper.

---

## Current gaps (as of app today)

- Timeout only shows Toast: `"Start Command Timed Out"` — no reason breakdown.  
- Offline only warns Toast: `"Device is offline. Command might fail."` then still sends.  
- Firebase `onResult` treats any response as success (`"Start Command Sent"`) — does not inspect Accepted vs Rejected.  
- No check that charger is already CHARGING before start (C2).  
- No explicit “another phone holds BLE” messaging (C1).  
- Stop without tid is handled; stop-by-other-user is not clearly owned.  
- Fault states exist in constants but Home UI mostly lumps them in `else`.

Use this file as the backlog: pick an ID, implement detection + message + test, check the box.
