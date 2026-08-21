# Firmware changes for session integrity

Suggestions only — no firmware code in this repo has been modified. The code
actually running on the hardware differs from `esp/` here, so treat the snippets
below as intent plus reference implementations rather than patches to apply
blindly. Line references point at `esp/mqtt.cpp` and `esp/aug2.ino` as they stand
in this repo.

## Why this exists

The app was rewritten so that **the charger is the only authority on whether a
session is running**. The phone keeps a durable "session lease" (a persisted
claim on a transaction id) and reconciles it against what the charger reports;
cloud state can corroborate a session but can never end one. See
`app/src/main/java/com/drivool/iot/powertap/session/`.

That model closes the app-side half of the dangling-session problem. Two firmware
behaviours keep the other half open: the charger will happily stop a session the
caller did not name, and start a session on top of one already running. Until
those change, a session can still end up live with nobody able to stop it.

Priority order: **1 and 2 are correctness bugs.** 3 is a contract to preserve. 4
and 5 are the occupancy problem. 6 is a small unrelated bug found while reading.

---

## 1. `RemoteStop` must validate the transaction id (critical)

**Where:** `esp/mqtt.cpp`, the `RemoteStop` branch of `Receive_TaskHandler`
(~line 1042).

**Now:** the `tid` is parsed and then never used. Every `RemoteStop` answers
`Accepted` and stops whatever is running.

```cpp
const char* tid = payload["tid"];          // parsed, never compared
sprintf((char*)resBuf, "[3,\"%llu\",{\"status\":\"Accepted\"}]", msgId);
SendData(resBuf);
stopPowerTap(STOP_REASON_REMOTE);
```

**Two failure modes this creates**

- A phone holding a *stale* id sends Stop and gets `Accepted`. It cuts off a
  session that belongs to somebody else, and both phones now disagree with the
  charger.
- A phone whose session already finished sends Stop, gets `Accepted`, and
  believes it stopped something. Nothing was running.

**Wanted:** stop only the named session, and when refusing, say which session is
actually live so the caller can take it over. That `activeTid` is what lets the
app repair itself without the user understanding any of this.

```cpp
JsonObject payload     = arrPacket[3];
const char* tid        = payload["tid"] | "";
const char* activeTid  = gDeviceState.strTID;

uint8_t resBuf[192];   // fits msgId + reason + TID_MAX_LEN (32)

/* Nothing running: do not claim to have stopped it. */
if (gFlags.Relay == false) {
  sprintf((char*)resBuf,
          "[3,\"%llu\",{\"status\":\"Rejected\",\"reason\":\"NoSession\",\"activeTid\":\"\"}]",
          msgId);
  SendData(resBuf);
  return;
}

/* Wrong session named. Report the live one instead of stopping blindly.
 * A blank tid still means "stop whatever is running", so older app builds
 * that do not send one keep working. */
if (strlen(tid) != 0 && strlen(activeTid) != 0 && strcmp(tid, activeTid) != 0) {
  sprintf((char*)resBuf,
          "[3,\"%llu\",{\"status\":\"Rejected\",\"reason\":\"TidMismatch\",\"activeTid\":\"%s\"}]",
          msgId, activeTid);
  SendData(resBuf);
  return;
}

sprintf((char*)resBuf, "[3,\"%llu\",{\"status\":\"Accepted\",\"activeTid\":\"%s\"}]",
        msgId, activeTid);
SendData(resBuf);
stopPowerTap(STOP_REASON_REMOTE);
```

**App side.** A `Rejected` carrying `activeTid` is parsed in
`GatewayManager.handleCallResult` and delivered on `CommandAck.activeTid`.
`HomeFragment.onChargerNamedActiveSession` uses it only as proof the charger is
busy with a *foreign* session (or confirms the id we already own). The app does
**not** adopt a stranger's tid — ownership is the lease created at Start.

Keeping the blank-tid escape hatch matters: it is what stops this change from
bricking Stop for app versions already in the field.

## 2. `RemoteStart` must refuse when already charging (critical)

**Where:** `esp/mqtt.cpp`, the `RemoteStart` branch (~line 1001).

**Now:** a start is accepted unconditionally, and then:

```cpp
strcpy(gDeviceState.strTID, tid);   // overwrites the live transaction id
```

**Why this is the worst of the two.** If a session is running, that `strcpy`
replaces the id of the *running* session. The old id is gone from the charger, so
the phone that owns it can no longer name it — and no phone knows the new id
either, because the start that introduced it was for a different intent. The
charge keeps running and nothing can stop it. This is the single clearest source
of the "zombie session" reports.

**Wanted:** refuse, and name the running session.

```cpp
if (gFlags.Relay == true) {
  uint8_t resBuf[192];
  sprintf((char*)resBuf,
          "[3,\"%llu\",{\"status\":\"Rejected\",\"reason\":\"AlreadyCharging\",\"activeTid\":\"%s\"}]",
          msgId, gDeviceState.strTID);
  SendData(resBuf);
  return;
}
```

Place this after the existing `strlen(tid) == 0` guard and before the `Accepted`
response. A retry of the *same* tid is also rejected, which is correct and
harmless: the app sees `activeTid` equal to the id it already holds and simply
confirms its own session.

## 3. Keep the packet-type contract intact (no change, do not regress)

The app derives charging-versus-idle from the packet type alone, which works
because of how the periodic task in `esp/mqtt.cpp` is written:

| Packet | Meaning to the app | Requirement |
|---|---|---|
| `MeterValues` | Relay closed, and `transactionId` is the live session | Sent **only** while relay is on; must carry `transactionId` |
| `Heartbeat` | Relay open — positive proof of idle | Sent **only** while relay is off |
| `StartTransaction` | Session began, carries the id | Must carry `transactionId` |
| `StopTransaction` | Session ended | — |

`MeterValues` already includes `transactionId` (`payload["transactionId"] =
gDeviceState.strTID`), so nothing is needed here today. Two things to protect:

- **Do not start sending `Heartbeat` while the relay is closed**, and do not put
  a transaction id on a heartbeat. The app treats a heartbeat as proof of idle;
  a tid on it would be a *finished* session's id, and reading it as live is
  exactly how a stale session gets adopted. This is asserted in
  `HardwareSessionTest.heartbeatTransactionIdIsNeverTreatedAsLive`.
- **Do not drop `transactionId` from `MeterValues`.** Without it the app can hold
  a session but not verify which one, which degrades to "charging, identity
  unknown" — enough to keep a lease, not enough to take over a stray session.

## 4. BLE occupancy: advertising stops while connected

**Where:** `esp/aug2.ino`, `OnBTStateChange::onConnect` (line 67).

```cpp
void onConnect(BLEServer *pServer) {
  gFlags.BTConnected = true;
  if (pBTAdvertising != NULL) pBTAdvertising->stop();   // charger goes invisible
}
```

One connected phone makes the PowerTap invisible to every other phone. This is
why the app's convenience auto-connect had to go: a phone in a pocket could
silently occupy a public charger.

The app now only holds a link when it owns a live session, releases it when the
session ends, and drops an idle link after 90s
(`session/BleConnectionPolicy.kt`). That limits the damage but does not fix the
cause. Worth considering on the firmware side:

- Keep advertising while connected, so other phones can at least *see* the
  charger and show "in use" instead of "not found". Advertise a busy flag, or the
  active transaction id, in the scan response.
- Drop an idle GATT link from the charger side after a timeout, so a phone that
  crashes or walks away cannot hold the charger indefinitely. Do **not** drop a
  link while the relay is closed: that is the link the owner needs to stop with.

## 5. BLE is disabled entirely while WiFi is up

**Where:** `esp/aug2.ino` line 430.

```cpp
if (!gFlags.WiFiConnected) { ... }
  gFlags.WiFiConnected = true;
  if (pBTAdvertising != NULL) pBTAdvertising->stop();   // no BLE at all on WiFi
```

A charger on WiFi cannot be reached over BLE at all. If WiFi drops mid-session
the phone has no local path to stop the charge and has to wait for the cloud.
Worth confirming whether this is a deliberate RAM/coexistence trade-off; if it is
about memory, advertising a minimal payload may still be affordable and would
preserve BLE as the fallback control path.

## 6. Minor: the parsed `arc` flag is discarded

**Where:** `esp/mqtt.cpp`, `RemoteStart` branch.

```cpp
const bool isARC = payload["arc"] | true;   // parsed...
...
gDeviceState.isARC = true;                  // ...then ignored
```

Auto Resume Charging is always enabled regardless of what the caller asked for.
Presumably `gDeviceState.isARC = isARC;`.

## 7. Retries resend a stale packet type with fresh data (critical)

**Where:** `esp/mqtt.cpp`, `Tx_Start` (~line 825) and `Transmit_TaskHandler`
(~line 848).

The message id is stamped once, when a command leaves the queue:

```cpp
gActiveCmd->messageId = ++gMessageCounter;
```

The retry path then rebuilds the payload from the live meter globals and sends
it again under that same id, every `RETRY_DELAY_MS` (5s), until a matching
CallResult arrives:

```cpp
gMqttCmd[gActiveCmd->cmd].request(txBuf);
SendData(txBuf);
```

So the packet *type* reflects the moment the frame was queued while the *data*
reflects the moment it was sent. A `Heartbeat` queued while the relay was open
keeps going out after the relay closes — carrying live charging current. Since
the app reads charging-versus-idle from the packet type (section 3), every retry
looked like fresh proof that the session had ended.

Observed on hardware, log of 2026-08-21: `[2,"1048579","Heartbeat",{"v":234797,
"c":4467,"p":1039905,...}]` — 4.47 A at 234 V, roughly 1 kW, on the packet that
is supposed to mean "relay open". It arrived twice, 5s apart, under one id, and
closed a session 2.5s after it started.

**Wanted:** re-derive the frame at retry time, or freeze the payload at enqueue
time — but do not mix the two. Re-deriving is preferable: a `Heartbeat` that no
longer describes reality should be dropped and replaced by `MeterValues` rather
than re-sent.

The app now ignores a repeated (id, action) pair rather than re-timestamping it
(`session/RetransmitFilter.kt`), so this no longer ends live sessions. That is a
workaround, not a fix: while the queue is stuck the app simply has no current
evidence and falls back to "unknown".

## 8. `gStatus` never becomes `Charging`

**Where:** `esp/mqtt.cpp` line 44, and `StatusNotification_Request` (~line 446).

```cpp
Charger_Status gStatus = Available;
```

It is assigned only in `esp/aug2.ino` (lines 258 and 470), both times to
`Unavailable` on power-down. Nothing ever sets it to `Charging`, so
`StatusNotification` reports `"Available"` throughout a live session. Combined
with section 7 this produced repeated "Available" frames mid-charge.

Set it alongside `gFlags.Relay` in `startPowerTap` / `stopPowerTap`.

## 9. One unacknowledged frame blocks every other frame

**Where:** `esp/mqtt.cpp`, `Tx_Start` / `Receive_TaskHandler`.

The queue only advances when a type-3 with the matching id arrives, so a single
frame that never gets acknowledged stops everything behind it. In the log above
that starved `MeterValues` completely for the whole of two charging sessions —
and `MeterValues` is the only packet that tells the app which transaction id is
live, so the app could not adopt the running session or offer Stop for it.

Worth considering: a bounded wait before dropping a frame and moving on, or
letting `MeterValues` bypass the queue while the relay is closed. Note also that
`MAX_RETRY_COUNT` is 100, so a queue blocked for roughly eight minutes reboots
the ESP — mid-charge, with the relay closed.

---

## Suggested verification

Once 1 and 2 are in, these are the cases that used to strand a session:

1. **Stale stop.** Start a session, note its tid. Force the app to send Stop with
   a different tid. Expect `Rejected` + `TidMismatch` + the live `activeTid`, and
   the relay still closed. The app should then adopt and stop successfully with no
   user-visible error.
2. **Start while busy.** Start a session, then send another `RemoteStart` with a
   fresh tid. Expect `Rejected` + `AlreadyCharging` + the original `activeTid`,
   and `gDeviceState.strTID` unchanged. Confirm the original session is still
   stoppable afterwards — this is the regression that matters most.
3. **Stop when idle.** Send `RemoteStop` with the relay open. Expect `Rejected` +
   `NoSession`, not `Accepted`.
4. **Backward compatibility.** Send `RemoteStop` with `tid` absent or empty.
   Expect it to still stop the running session.
5. **Reboot mid-session.** Confirm whether `gDeviceState.strTID` survives a
   restart while the relay is on. If it does not, the charger comes back charging
   with no id and the app can only report "charging, identity unknown". If
   `strTID` is not already persisted to NVS alongside the rest of `DeviceState`,
   that is worth adding.

Response buffers: `TID_MAX_LEN` is 32, and the longest rejection above is roughly
120 bytes with a full-width msgId, so `resBuf[192]` is safe. The current
`resBuf[128]` is not large enough once `activeTid` is included.
