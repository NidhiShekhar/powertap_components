package com.drivool.iot.powertap

import com.drivool.iot.powertap.contract.ConnectionState
import com.drivool.iot.powertap.session.LeaseState

/**
 * Pure rules for charging + BLE so Home/Gateway cannot drift out of sync.
 *
 * The charger is the source of truth for "is energy flowing". The phone is only
 * a remote. Bluetooth dropping must never look like "session ended", and a
 * stale cloud STOPPING must never lock the slider forever.
 */
object ChargeSessionLogic {
    const val COMMAND_TIMEOUT_MS = 15_000L
    const val SCAN_CACHE_FRESH_MS = 8_000L
    const val MAX_RECONNECT_DELAY_MS = 15_000L
    const val HEARTBEAT_STALE_MS = 45_000L

    /**
     * A scan window is 20s and a GATT attempt 15s. If the transport is still
     * Scanning or Connecting past this, the attempt is wedged (timeout never
     * fired) and the supervisor must tear it down rather than wait forever.
     */
    const val RECONNECT_ATTEMPT_STALE_MS = 25_000L

    fun isHeartbeatFresh(
        heartbeatMs: Long,
        nowMs: Long,
        windowMs: Long = HEARTBEAT_STALE_MS,
    ): Boolean {
        if (heartbeatMs <= 0L) return false
        val age = nowMs - heartbeatMs
        return age in 0 until windowMs
    }

    /**
     * Cloud CHARGING with a dead heartbeat is leftover from a power-cycle.
     * Reinstalling the app cannot clear Firebase, so we must ignore it.
     *
     * [hasOpenLease] is the exception: if this phone owns a session, an
     * unreachable charger means "unknown", not "available". Downgrading here
     * would hide the Stop control for a session that is still running.
     */
    fun effectiveServerState(
        serverState: Int,
        heartbeatFresh: Boolean,
        bleReady: Boolean,
        commandInFlight: Boolean,
        hasOpenLease: Boolean = false,
    ): Int {
        if (commandInFlight) return serverState
        if (hasOpenLease) return serverState
        if (heartbeatFresh || bleReady) return serverState
        return if (isChargingUi(serverState)) DeviceState.STATE_AVAILABLE else serverState
    }

    fun isBleReady(state: ConnectionState): Boolean =
        state == ConnectionState.Connected

    fun isChargingUi(state: Int): Boolean = state == DeviceState.STATE_STARTING ||
        state == DeviceState.STATE_STARTED ||
        state == DeviceState.STATE_CHARGING ||
        state == DeviceState.STATE_STOPPING

    /**
     * A second connect() to the same MAC while a session is alive tears down the
     * live GATT object. Android then reports Connected on a dead handle.
     */
    fun shouldSkipDuplicateConnect(
        currentState: ConnectionState,
        currentAddress: String?,
        targetAddress: String,
    ): Boolean {
        if (currentAddress.isNullOrBlank()) return false
        if (!currentAddress.equals(targetAddress, ignoreCase = true)) return false
        return currentState == ConnectionState.Connected ||
            currentState == ConnectionState.Connecting ||
            currentState == ConnectionState.Scanning
    }

    /**
     * A tap must never be swallowed by an attempt the app started on its own.
     *
     * [shouldSkipDuplicateConnect] exists to protect a *live* GATT from being
     * torn down, but the session-resume retry parks the transport in Scanning or
     * Connecting for most of its cycle. Reporting "already connecting" there
     * makes the Connect button do nothing at all, which is indistinguishable
     * from the app being broken — and it is the state the user is most likely to
     * be tapping in, because they only reach for the button when a reconnect is
     * visibly failing. A half-open attempt is worth restarting; a Connected link
     * is not.
     */
    fun shouldRestartAttemptForUser(state: ConnectionState): Boolean =
        state == ConnectionState.Scanning || state == ConnectionState.Connecting

    /**
     * Which transaction id the lease should hold once the cloud acknowledges our
     * Start.
     *
     * The charger copies the `tid` out of RemoteStart and stores it verbatim
     * (`strcpy(gDeviceState.strTID, tid)` in esp/mqtt.cpp), then quotes that same
     * id back on StartTransaction, MeterValues and StopTransaction. It is the
     * only id that can ever be matched against hardware.
     *
     * So a cloud ack naming a *different* id is not a rename we are able to
     * honour — the charger never heard about it. Adopting it produces a lease no
     * charger will ever confirm, which the reconciler correctly reads as
     * "someone else's session" and drops. Losing the lease loses both Stop and
     * the authority to reconnect, so a mid-charge walk-away becomes permanent.
     *
     * The server id is only followed when the charger is demonstrably already
     * running it.
     */
    fun leaseTransactionIdAfterAck(
        proposedTid: String,
        serverTid: String?,
        hardwareTid: String?,
    ): String {
        val server = serverTid?.trim().orEmpty()
        if (server.isEmpty() || server == proposedTid) return proposedTid
        return if (server == hardwareTid?.trim()) server else proposedTid
    }

    fun isScanResultFresh(
        lastSeenElapsedMs: Long,
        nowElapsedMs: Long,
        windowMs: Long = SCAN_CACHE_FRESH_MS,
    ): Boolean {
        val age = nowElapsedMs - lastSeenElapsedMs
        return age in 0 until windowMs
    }

    /**
     * While we have an in-flight command, ignore a stale replica of the *previous*
     * server state. Always accept the terminal states we are waiting for.
     */
    fun shouldAcceptServerState(
        localState: Int,
        serverState: Int,
        commandInFlight: Boolean,
    ): Boolean {
        if (!commandInFlight) return true
        val serverCharging = serverState == DeviceState.STATE_CHARGING ||
            serverState == DeviceState.STATE_STARTED ||
            serverState == DeviceState.STATE_STARTING
        val serverIdle = serverState == DeviceState.STATE_AVAILABLE ||
            serverState == DeviceState.STATE_STOPPED
        return when (localState) {
            DeviceState.STATE_STARTING -> !serverIdle
            DeviceState.STATE_STOPPING -> !serverCharging
            else -> true
        }
    }

    fun isCommandTimedOut(
        commandStartTime: Long,
        now: Long,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
    ): Boolean = commandStartTime > 0L && now - commandStartTime > timeoutMs

    fun stateAfterCommandTimeout(localState: Int): Int = when (localState) {
        DeviceState.STATE_STARTING -> DeviceState.STATE_AVAILABLE
        DeviceState.STATE_STOPPING -> DeviceState.STATE_CHARGING
        else -> localState
    }

    /**
     * Lock the slider only while *this phone* is waiting on a command we sent.
     * Cloud state STOPPING with no local in-flight command is a stale replica —
     * treat it as charging so the user can slide to stop again.
     */
    fun sliderLocked(localState: Int, commandInFlight: Boolean): Boolean =
        commandInFlight &&
            (localState == DeviceState.STATE_STARTING || localState == DeviceState.STATE_STOPPING)

    /**
     * What the slider should render, which is not always what the app last
     * heard.
     *
     * Stop is only for the phone that owns the session lease (the transaction
     * id created at Start). Cloud CHARGING or seeing the charger busy must not
     * offer Stop to a stranger — that was the take-control hole. An open lease
     * or our own in-flight command are what make Stop real.
     */
    fun sliderState(
        localState: Int,
        hasOpenLease: Boolean,
        hardwareCharging: Boolean,
        commandInFlight: Boolean,
    ): Int {
        // hardwareCharging is retained in the signature so call sites stay
        // honest about what they know, but it must not unlock Stop alone.
        @Suppress("UNUSED_PARAMETER")
        val ignoredBusyEvidence = hardwareCharging
        if (hasOpenLease || commandInFlight) return localState
        return if (isChargingUi(localState)) DeviceState.STATE_AVAILABLE else localState
    }

    /**
     * True when this phone's lease names the same session the charger is
     * running. Used for auto-reconnect confirmation and to refuse Stop/Start
     * against someone else's charge.
     */
    fun ownsRunningSession(leaseTransactionId: String?, hardwareTransactionId: String?): Boolean {
        if (leaseTransactionId.isNullOrBlank()) return false
        if (hardwareTransactionId.isNullOrBlank()) return false
        return leaseTransactionId.trim() == hardwareTransactionId.trim()
    }

    /**
     * This login started the live charge, regardless of whether the charger's
     * current transaction id still matches the one we proposed at Start.
     *
     * Firmware overwrites `strTID` on every RemoteStart, and the cloud copy of
     * the id can lag that. Treating a rename as "someone else's session" is what
     * released the lease mid-charge, which is the only authority auto-reconnect
     * and Stop have. Heartbeat freshness is ignored: a walk-away is exactly when
     * the cloud heartbeat goes stale, and that must not cost us the session.
     */
    fun accountOwnsLiveSession(
        currentAccount: String?,
        ownerAccount: String?,
        cloudState: Int,
    ): Boolean {
        if (currentAccount.isNullOrBlank() || currentAccount == "guest") return false
        if (ownerAccount.isNullOrBlank() || ownerAccount != currentAccount) return false
        return cloudState == DeviceState.STATE_CHARGING ||
            cloudState == DeviceState.STATE_STARTED ||
            cloudState == DeviceState.STATE_STARTING
    }

    /**
     * Cloud says [ownerAccount] started [transactionId]. Used to reclaim a
     * session after reinstall when the local lease is gone.
     */
    fun accountOwnsCloudSession(
        currentAccount: String?,
        ownerAccount: String?,
        transactionId: String?,
        cloudState: Int,
        heartbeatFresh: Boolean,
        requireFreshHeartbeat: Boolean = true,
    ): Boolean {
        if (!accountOwnsLiveSession(currentAccount, ownerAccount, cloudState)) return false
        if (transactionId.isNullOrBlank()) return false
        if (requireFreshHeartbeat && !heartbeatFresh) return false
        return true
    }

    /**
     * Whether Disconnect / the connection card should treat this phone as mid-charge.
     *
     * An open lease alone is not enough: Start claims the lease *before* the
     * charger accepts, and a failed Start used to leave that claim around. The
     * Disconnect button then warned "session still running" for a phone that was
     * only paired — which is the bug this guards against.
     */
    fun isLiveOwnedSession(
        leaseState: LeaseState?,
        hardwareCharging: Boolean,
        localChargingUi: Boolean,
    ): Boolean {
        if (leaseState == null || leaseState == LeaseState.Released) return false
        if (hardwareCharging) return true
        if (leaseState == LeaseState.Stopping) return true
        // Active or still waiting on Start: only "live" while the UI is still
        // in a charging state. AVAILABLE + open lease means the claim is stale.
        if (leaseState == LeaseState.Active || leaseState == LeaseState.Requested) {
            return localChargingUi
        }
        return false
    }

    /**
     * Heartbeat / StatusNotification idle is not proof the session ended when we
     * cannot see the charger over GATT *and* we already confirmed a charge.
     *
     * Only [Active]/[Stopping] leases hold through idle — a Requested lease that
     * never started must be ended by the first idle heartbeat, otherwise an
     * idle Connect→Disconnect cycle claims "session still running".
     *
     * [StopTransaction] is definite and is never held. After GATT comes back,
     * hold through [reconnectGraceMs] so a queued idle cannot race MeterValues.
     */
    fun shouldHoldThroughIdleEvidence(
        leaseState: LeaseState?,
        bleReady: Boolean,
        isStopTransaction: Boolean,
        msSinceReconnect: Long,
        reconnectGraceMs: Long,
    ): Boolean {
        if (isStopTransaction) return false
        val confirmed = leaseState == LeaseState.Active || leaseState == LeaseState.Stopping
        if (!confirmed) return false
        if (!bleReady) return true
        return msSinceReconnect in 0 until reconnectGraceMs
    }

    /**
     * A session-resume attempt parked in Scanning/Connecting with no timeout
     * firing. The supervisor must disconnect and try again rather than treat
     * "already connecting" as success.
     */
    fun reconnectAttemptNeedsRestart(
        state: ConnectionState,
        busySinceMs: Long,
        nowMs: Long,
        staleAfterMs: Long = RECONNECT_ATTEMPT_STALE_MS,
    ): Boolean {
        if (state != ConnectionState.Scanning && state != ConnectionState.Connecting) return false
        if (busySinceMs <= 0L) return false
        return nowMs - busySinceMs >= staleAfterMs
    }

    /**
     * GATT reports Connected but no charger packet has arrived. A walk-away
     * sometimes leaves Android holding a dead handle; the supervisor then
     * stops because it thinks the link is up, and even a tap looks like
     * "already connected".
     */
    fun shouldDropSilentGatt(
        bleReady: Boolean,
        hasOpenLease: Boolean,
        lastBlePacketAtMs: Long,
        nowMs: Long,
        silentAfterMs: Long = HEARTBEAT_STALE_MS,
    ): Boolean {
        if (!bleReady || !hasOpenLease) return false
        if (lastBlePacketAtMs <= 0L) return false
        return nowMs - lastBlePacketAtMs >= silentAfterMs
    }

    /**
     * RemoteStop must name the id the charger is actually running. After a
     * firmware rename that is the hardware id, not the one we proposed.
     */
    fun stopTargetTransactionId(leaseTid: String, hardwareTid: String?): String {
        val live = hardwareTid?.trim().orEmpty()
        return live.ifBlank { leaseTid }
    }

    fun treatAsCharging(localState: Int, commandInFlight: Boolean): Boolean =
        localState == DeviceState.STATE_CHARGING ||
            localState == DeviceState.STATE_STARTED ||
            (localState == DeviceState.STATE_STOPPING && !commandInFlight) ||
            (localState == DeviceState.STATE_STARTING && !commandInFlight)

    fun reconnectDelayMs(attempt: Int): Long {
        val exp = 1_000L shl attempt.coerceIn(0, 4)
        return exp.coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    /**
     * Cloud CallResults (type 3) must reach the charger — BootNotification,
     * Heartbeat, StartTransaction all wait on them. Type-2 frames are only
     * forwarded when they are commands. Echoing MeterValues saturates GATT.
     */
    fun mqttShouldForwardToBle(payload: String): Boolean {
        val trimmed = payload.trimStart()
        if (trimmed.startsWith("[3")) return true
        if (!trimmed.startsWith("[2")) return false
        return trimmed.contains("\"RemoteStart\"") ||
            trimmed.contains("\"RemoteStop\"") ||
            trimmed.contains("\"DataTransfer\"")
    }

    /** Only apply a CallResult to RemoteStart/Stop if the message id matches. */
    fun callResultMatchesCommand(ackMsgId: String, lastCommandMsgId: String?): Boolean {
        if (ackMsgId.isBlank() || lastCommandMsgId.isNullOrBlank()) return false
        return ackMsgId == lastCommandMsgId
    }

    @Suppress("UNCHECKED_CAST")
    fun transactionIdFromServerAck(result: Map<String, Any>): String? {
        val resp = result["resp"]
        if (resp is Map<*, *>) {
            val tid = resp["transactionId"] ?: resp["tid"]
            tid?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return result["transactionId"]?.toString()?.takeIf { it.isNotBlank() }
    }

    /**
     * After 15s: revert only if nobody confirmed. Hardware StartTransaction
     * means the charger is already running — do not lie and snap back to Start.
     */
    fun startTimeoutShouldRevert(hardwareStarted: Boolean, serverAcked: Boolean): Boolean =
        !hardwareStarted && !serverAcked

    fun isHighFrequencyPacket(payload: String): Boolean {
        return payload.contains("MeterValues") ||
            payload.contains("Heartbeat") ||
            payload.contains("HeartBeat")
    }

    /** Same CallResult delivered twice (two MQTT clients / QoS retry). */
    fun isDuplicateMqttForward(payload: String, lastPayload: String?, lastAtMs: Long, nowMs: Long): Boolean {
        if (lastPayload.isNullOrEmpty()) return false
        if (payload != lastPayload) return false
        return nowMs - lastAtMs in 0..2_000L
    }

    data class AxisWindow(val min: Float, val max: Float, val decimals: Int)

    /**
     * Keep nearly-flat live series readable. 1.009 kW vs 1.006 kW must not
     * collapse to a single "1.0" tick, and cubic overshoot is handled in the view.
     */
    fun yAxisWindow(values: List<Float>): AxisWindow {
        if (values.isEmpty()) return AxisWindow(0f, 1f, 1)
        val lo = values.minOrNull() ?: 0f
        val hi = values.maxOrNull() ?: 1f
        val span = (hi - lo).coerceAtLeast(0f)
        val pad = when {
            span < 0.5f -> maxOf(span * 0.5f, 0.05f)
            else -> span * 0.2f
        }
        val decimals = when {
            span < 0.5f -> 2
            span < 5f -> 2
            else -> 1
        }
        return AxisWindow(lo - pad, hi + pad, decimals)
    }
}
