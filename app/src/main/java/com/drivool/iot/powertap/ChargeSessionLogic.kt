package com.drivool.iot.powertap

import com.drivool.iot.powertap.contract.ConnectionState

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
        return leaseTransactionId == hardwareTransactionId
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
        if (currentAccount.isNullOrBlank() || currentAccount == "guest") return false
        if (ownerAccount.isNullOrBlank() || ownerAccount != currentAccount) return false
        if (transactionId.isNullOrBlank()) return false
        val charging = cloudState == DeviceState.STATE_CHARGING ||
            cloudState == DeviceState.STATE_STARTED ||
            cloudState == DeviceState.STATE_STARTING
        if (!charging) return false
        if (requireFreshHeartbeat && !heartbeatFresh) return false
        return true
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
