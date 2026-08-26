package com.drivool.iot.powertap

import android.content.Context
import com.drivool.iot.powertap.ble.BlePrefs
import com.drivool.iot.powertap.ble.BleTransport
import com.drivool.iot.powertap.contract.DeviceTransport
import com.drivool.iot.powertap.contract.ConnectionState
import com.drivool.iot.powertap.mqtt.MqttPrefs
import com.drivool.iot.powertap.mqtt.MqttTransport
import com.drivool.iot.powertap.contract.MeterData
import com.drivool.iot.powertap.contract.ChargingSession
import com.drivool.iot.powertap.session.BleConnectionPolicy
import com.drivool.iot.powertap.session.HardwareSession
import com.drivool.iot.powertap.session.RetransmitFilter
import com.drivool.iot.powertap.session.SessionLeaseStore
import com.drivool.iot.powertap.session.SessionReconciler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * GatewayManager is a singleton that holds the BLE and MQTT transports
 * and provides a bridge between them.
 */
object GatewayManager {
    /** How often the reconnect supervisor re-checks the link while it waits. */
    private const val RECONNECT_TICK_MS = 1_000L

    /** Delay before a reconnect triggered by a queued command we must deliver. */
    private const val IMMEDIATE_RECONNECT_MS = 400L

    /** Beyond this the backoff is already at its ceiling; stop counting. */
    private const val MAX_RECONNECT_ATTEMPT = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var _bleTransport: DeviceTransport? = null
    val bleTransport: DeviceTransport
        get() = _bleTransport ?: throw IllegalStateException("GatewayManager not initialized")

    private val _mqttTransport = MqttTransport(log = LogRepository::append)
    val mqttTransport: MqttTransport = _mqttTransport

    private val _isBridgeEnabled = MutableStateFlow(true)
    val isBridgeEnabled: StateFlow<Boolean> = _isBridgeEnabled

    private val _latestMeterData = MutableStateFlow<MeterData?>(null)
    val latestMeterData: StateFlow<MeterData?> = _latestMeterData.asStateFlow()

    private val _meterHistory = MutableStateFlow<List<MeterData>>(emptyList())
    val meterHistory: StateFlow<List<MeterData>> = _meterHistory.asStateFlow()

    private val _bridgeDetectedState = MutableStateFlow<Int?>(null)
    val bridgeDetectedState: StateFlow<Int?> = _bridgeDetectedState.asStateFlow()

    /**
     * Last CallResult (type-3 ACK) from the charger.
     * UX only: firmware replies `[3,"<msgId>",{"status":"Accepted"}]` immediately.
     * A billed session is committed only when Firebase `PowerTapMonitor/{id}/state` changes.
     */
    private val _commandAck = MutableStateFlow<CommandAck?>(null)
    val commandAck: StateFlow<CommandAck?> = _commandAck.asStateFlow()

    private val _chargingStartedAt = MutableStateFlow<Long?>(null)
    val chargingStartedAt: StateFlow<Long?> = _chargingStartedAt.asStateFlow()

    /**
     * What the charger itself last said about its relay and transaction id.
     * This is the authority for whether a session is running — cloud state and
     * heartbeat age only corroborate it.
     */
    private val _hardwareSession = MutableStateFlow<HardwareSession>(HardwareSession.Unknown)
    val hardwareSession: StateFlow<HardwareSession> = _hardwareSession.asStateFlow()

    /** True when the user tapped Disconnect this process — skip auto-reconnect until they pick a device. */
    var userRequestedDisconnect: Boolean = false
        private set

    /**
     * Last deliberate user interaction with the charger. Drives the courtesy
     * release so a forgotten connection does not keep the PowerTap occupied.
     */
    private var lastUserActionAt = 0L

    /**
     * Timestamp of the last courtesy release, so Home can explain it once.
     *
     * Cleared by [acknowledgeIdleRelease] as soon as it has been shown. Home is
     * rebuilt from scratch on every navigation, and a StateFlow replays its
     * current value to each new collector, so anything left here comes back as
     * if it had just happened.
     */
    private val _idleReleased = MutableStateFlow(0L)
    val idleReleased: StateFlow<Long> = _idleReleased.asStateFlow()

    /** The user has been told about the release; do not tell them again. */
    fun acknowledgeIdleRelease() {
        _idleReleased.value = 0L
    }

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var pendingChargerCommand: String? = null
    private var lastForwardedMqtt: String? = null
    private var lastForwardedMqttAt = 0L
    /** Last packet that arrived over GATT, not MQTT. Dead-handle detection. */
    private var lastBlePacketAt = 0L
    /** When GATT last reached Connected. Reconnect-grace for queued idle frames. */
    private var linkRestoredAt = 0L

    private var lastCommandMsgId: String? = null
    private var lastCommandAction: String? = null

    private val retransmits = RetransmitFilter()

    private var context: Context? = null

    private val _currentDeviceId = MutableStateFlow<String>("")
    val currentDeviceId: StateFlow<String> = _currentDeviceId.asStateFlow()

    private val _isDeviceOnline = MutableStateFlow(false)
    val isDeviceOnline: StateFlow<Boolean> = _isDeviceOnline.asStateFlow()

    private var lastHeartbeatTime = 0L

    /** Device ID from QR/manual setup; preferred over BLE MAC guessing on connect. */
    private var preferredDeviceId: String? = null

    /**
     * Pair/connect to a PowerTap over BLE.
     *
     * Android often fails a first-time [BleTransport.connect] unless the charger has
     * just been seen in a scan. Home, QR, and Add Device all go through this path
     * so a first-time user can tap a device on Home and still pair.
     *
     * @param scanFirst true = scan until this MAC is advertised, then GATT connect
     *                  (required from Home / QR). false = connect immediately because
     *                  we already have a fresh scan result (Add Device list).
     * @param userInitiated true when a tap caused this. Resuming an owned session
     *                  passes false so it cannot postpone the courtesy release.
     * @return false if Bluetooth is unavailable/disabled
     */
    fun connectToBle(
        bleAddress: String,
        deviceId: String? = null,
        displayName: String? = null,
        scanFirst: Boolean = true,
        userInitiated: Boolean = true,
    ): Boolean {
        val ctx = context ?: return false
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            LogRepository.append("Gateway: BLE connect aborted — Bluetooth off")
            return false
        }

        userRequestedDisconnect = false
        if (userInitiated) {
            noteUserAction()
            // Only a tap cancels the supervisor. Cancelling it from a resume
            // attempt would cancel the coroutine making that very call.
            reconnectJob?.cancel()
            reconnectAttempt = 0
        }

        val resolvedId = deviceId?.takeIf { it.isNotBlank() }
            ?: DeviceIdentity.deviceIdFromBle(bleAddress)
            ?: DeviceIdentity.cleanHex(bleAddress)
            ?: ""

        if (resolvedId.isNotEmpty()) {
            preferredDeviceId = resolvedId
            _currentDeviceId.value = resolvedId
            val config = MqttPrefs.load(ctx)
            MqttPrefs.save(ctx, config, resolvedId)
            _mqttTransport.connect(config, resolvedId)
        }

        val name = displayName ?: if (resolvedId.length == 12) "PowerTap_$resolvedId" else null
        BlePrefs.saveLastDevice(ctx, bleAddress, name)

        val current = bleTransport.connectedAddress.value
        val state = bleTransport.connectionState.value
        if (ChargeSessionLogic.shouldSkipDuplicateConnect(state, current, bleAddress)) {
            if (userInitiated && ChargeSessionLogic.shouldRestartAttemptForUser(state)) {
                // The session-resume retry holds the transport in Scanning or
                // Connecting for most of its cycle, so this branch is where a
                // tap normally lands. Reporting success and doing nothing left
                // the user with a Connect button that never worked.
                LogRepository.append(
                    "Gateway: User asked again during $state — restarting attempt on $bleAddress",
                )
                bleTransport.disconnect()
            } else {
                LogRepository.append("Gateway: Already on $bleAddress ($state) — not tearing GATT down")
                return true
            }
        }

        LogRepository.append(
            "Gateway: BLE connect → $bleAddress, deviceId $resolvedId, scanFirst=$scanFirst",
        )

        if (current != null && !current.equals(bleAddress, ignoreCase = true)) {
            bleTransport.disconnect()
        }

        bleTransport.stopScan()
        if (scanFirst) {
            // Scan until this MAC is seen, then GATT connect. Direct connectGatt
            // without a recent advertisement is why Home used to fail the first time.
            bleTransport.startScan(bleAddress)
        } else {
            bleTransport.connect(bleAddress)
        }
        return true
    }

    /**
     * Connect to a PowerTap using details from a scanned QR code.
     * @return false if Bluetooth is unavailable/disabled
     */
    fun connectFromQr(qr: PowerTapQr): Boolean =
        connectToBle(qr.bleAddress, qr.deviceId, qr.displayName, scanFirst = true)

    fun markUserDisconnect() {
        val keepSession = SessionLeaseStore.hasOpenLease
        userRequestedDisconnect = true
        noteUserAction()
        reconnectJob?.cancel()
        _isReconnecting.value = false
        // A queued RemoteStop is the user's only way to end a charge they own,
        // so it outlives a manual disconnect.
        if (!keepSession) pendingChargerCommand = null
        _bleTransport?.disconnect()
        LogRepository.append(
            if (keepSession) {
                "Gateway: User dropped BLE mid-session — resume re-arms next time they open the app"
            } else {
                "Gateway: User disconnected BLE — will not reconnect until asked"
            },
        )
    }

    /**
     * The user is back in front of the app with a session they still own.
     *
     * Home tells them "we'll reconnect automatically when you come back", and
     * only the phone holding the lease can stop the charge — so a manual
     * disconnect has to mean "not right now", never "not ever". Leaving the flag
     * latched was what stranded a user who tapped Disconnect and walked away:
     * every reconnect rule short-circuits on it, so nothing tried again and the
     * charger kept charging with no way to stop it.
     */
    fun rearmSessionResume() {
        if (!SessionLeaseStore.hasOpenLease) return
        if (!userRequestedDisconnect) return
        userRequestedDisconnect = false
        reconnectAttempt = 0
        LogRepository.append("Gateway: Back with an open session — auto-reconnect re-armed")
    }

    /**
     * "Try now" from the connection card. Resets the backoff and pre-empts an
     * attempt already in flight so the tap has a visible effect.
     */
    fun retrySessionLinkNow(): Boolean {
        if (_bleTransport == null) return false
        if (!SessionLeaseStore.hasOpenLease) return false
        userRequestedDisconnect = false
        noteUserAction()
        reconnectAttempt = 0
        val state = _bleTransport?.connectionState?.value
        if (state != null && ChargeSessionLogic.shouldRestartAttemptForUser(state)) {
            _bleTransport?.disconnect()
        }
        // Cancel the supervisor's backoff so a tap is not sat out until the
        // next 1/2/4/15s tick. Immediate re-arm is what "Retry now" means.
        reconnectJob?.cancel()
        scheduleReconnect(immediate = true)
        return true
    }

    /** Record deliberate interaction so the idle release timer restarts. */
    fun noteUserAction() {
        lastUserActionAt = System.currentTimeMillis()
    }

    /**
     * Hand the charger back so another driver can see and use it.
     *
     * The firmware stops advertising while a phone is connected, so this is what
     * actually un-occupies the PowerTap. Unlike [markUserDisconnect] this does
     * not block a later session resume — it just ends the current link.
     */
    fun releaseLink(reason: String) {
        reconnectJob?.cancel()
        _isReconnecting.value = false
        pendingChargerCommand = null
        _hardwareSession.value = HardwareSession.Unknown
        _bleTransport?.disconnect()
        LogRepository.append("Gateway: Released BLE link — $reason")
    }

    /**
     * Reconnect on app open or when Bluetooth returns — but only to resume a
     * session this phone owns.
     *
     * This used to fire for any previously seen charger, which meant a phone in
     * range silently connected, stopped the charger advertising, and made it
     * invisible to the next driver.
     */
    fun resumeSessionLink(): Boolean {
        val ctx = context ?: return false
        val lease = SessionLeaseStore.open
        val state = _bleTransport?.connectionState?.value ?: return false

        if (!BleConnectionPolicy.shouldResumeLink(
                hasOpenLease = lease != null && BlePrefs.isSessionResumeEnabled(ctx),
                userRequestedDisconnect = userRequestedDisconnect,
                state = state,
            )
        ) {
            if (lease == null && !BleConnectionPolicy.isLinkBusy(state)) {
                LogRepository.append("Gateway: No session to resume — staying disconnected")
            }
            return BleConnectionPolicy.isLinkBusy(state)
        }

        val address = lease!!.bleAddress.takeIf { it.isNotBlank() }
            ?: BlePrefs.getLastDeviceAddress(ctx)
            ?: return false
        val known = BlePrefs.getKnownDevices(ctx).firstOrNull { (_, addr) ->
            addr.equals(address, ignoreCase = true)
        }
        LogRepository.append(
            "Gateway: Resuming session ${lease.transactionId} — reconnecting to $address",
        )
        return connectToBle(
            address,
            deviceId = lease.deviceId.takeIf { it.isNotBlank() },
            displayName = known?.first,
            scanFirst = true,
            userInitiated = false,
        )
    }

    /**
     * Write to the charger over BLE. If GATT is down, queue the payload and
     * start reconnecting so a mid-session RemoteStop is not dropped.
     */
    fun sendToCharger(payload: String): Boolean {
        val transport = _bleTransport
        if (transport != null && ChargeSessionLogic.isBleReady(transport.connectionState.value)) {
            val ok = transport.send(payload)
            if (ok) {
                pendingChargerCommand = null
                return true
            }
            LogRepository.append("Gateway: BLE write failed on Connected GATT — dropping link to reconnect")
            pendingChargerCommand = payload
            transport.disconnect()
            return false
        }
        pendingChargerCommand = payload
        LogRepository.append("Gateway: BLE not ready — queued command, reconnecting")
        if (!userRequestedDisconnect) {
            scheduleReconnect(immediate = true)
        }
        return false
    }

    private fun flushPendingCommand() {
        val payload = pendingChargerCommand ?: return
        val transport = _bleTransport ?: return
        if (!ChargeSessionLogic.isBleReady(transport.connectionState.value)) return
        if (transport.send(payload)) {
            LogRepository.append("Gateway: Flushed queued command after reconnect")
            pendingChargerCommand = null
        }
    }

    /**
     * Retry the link after a drop. Gated on owning a session: losing Bluetooth
     * mid-charge must not cost the user the ability to stop, but a dropped idle
     * link is left alone so the charger stays available to others.
     */
    private fun scheduleReconnect(immediate: Boolean = false) {
        if (userRequestedDisconnect) return
        if (!SessionLeaseStore.hasOpenLease) {
            LogRepository.append("Gateway: Not reconnecting — no session owned by this phone")
            _isReconnecting.value = false
            return
        }
        if (reconnectJob?.isActive == true) {
            if (!immediate) return
            // A queued command — usually the RemoteStop that ends the charge —
            // is waiting on the link, so it must not sit out a backoff.
            reconnectJob?.cancel()
            reconnectAttempt = 0
        }
        _isReconnecting.value = true
        reconnectJob = scope.launch { superviseReconnect(immediate) }
    }

    /**
     * Keep trying to get the link back for as long as this phone owns a session.
     *
     * Deliberately a loop that re-arms itself, rather than one attempt chained to
     * the next ConnectionState change. A StateFlow only emits on a *change*, and
     * several ways an attempt can end leave the state exactly where it already
     * was: Failed → Failed after a scan window expires, or startScan() returning
     * early because Bluetooth was toggled off or a scan permission was revoked.
     * Every one of those used to retire auto-reconnect for the rest of the
     * process — in exactly the situation it has to survive, because a user who
     * has walked out of range fails every attempt until they walk back.
     */
    private suspend fun superviseReconnect(immediate: Boolean) {
        var nextAttemptAt = System.currentTimeMillis() + if (immediate) {
            IMMEDIATE_RECONNECT_MS
        } else {
            ChargeSessionLogic.reconnectDelayMs(reconnectAttempt)
        }
        var attemptInFlight = false
        var busySince = 0L
        try {
            while (true) {
                if (userRequestedDisconnect) break
                if (!SessionLeaseStore.hasOpenLease) break
                val state = _bleTransport?.connectionState?.value ?: break
                if (state == ConnectionState.Connected) break

                val now = System.currentTimeMillis()
                if (BleConnectionPolicy.isLinkBusy(state)) {
                    attemptInFlight = true
                    if (busySince == 0L) busySince = now
                    if (ChargeSessionLogic.reconnectAttemptNeedsRestart(state, busySince, now)) {
                        LogRepository.append(
                            "Gateway: Session reconnect stuck in $state — restarting attempt",
                        )
                        _bleTransport?.disconnect()
                        busySince = 0L
                        attemptInFlight = false
                        nextAttemptAt = now + ChargeSessionLogic.reconnectDelayMs(reconnectAttempt)
                    }
                } else if (attemptInFlight) {
                    // Start the backoff from when the attempt gave up, not from
                    // when it began — a scan window alone outlasts the ceiling.
                    attemptInFlight = false
                    busySince = 0L
                    nextAttemptAt = now + ChargeSessionLogic.reconnectDelayMs(reconnectAttempt)
                } else if (now >= nextAttemptAt) {
                    reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(MAX_RECONNECT_ATTEMPT)
                    nextAttemptAt = now + ChargeSessionLogic.reconnectDelayMs(reconnectAttempt)
                    LogRepository.append(
                        "Gateway: Session reconnect attempt $reconnectAttempt" +
                            " for tid=${SessionLeaseStore.open?.transactionId}",
                    )
                    resumeSessionLink()
                }
                delay(RECONNECT_TICK_MS)
            }
        } finally {
            _isReconnecting.value = false
        }
    }

    fun init(context: Context) {
        this.context = context.applicationContext
        TransactionRepository.init(context.applicationContext)
        SessionLeaseStore.init(context.applicationContext)
        if (_bleTransport == null) {
            _bleTransport = BleTransport(context.applicationContext, log = LogRepository::append)
            setupBridge()

            // Only reconnects if a session survived the app being killed.
            SessionLeaseStore.open?.let {
                LogRepository.append("Gateway: Found saved session ${it.transactionId} (${it.state})")
            }
            resumeSessionLink()
            
            // Auto-connect MQTT if config exists
            val config = MqttPrefs.load(context)
            val savedId = MqttPrefs.loadDeviceId(context).ifBlank { "" }
            _currentDeviceId.value = savedId
            
            if (config.host.isNotBlank()) {
                LogRepository.append("Auto-connecting MQTT to ${config.host}...")
                _mqttTransport.connect(config, savedId.ifBlank { null })
            }

            // Monitor MQTT for heartbeats to update online status
            scope.launch {
                _mqttTransport.incoming.collect { incoming ->
                    val currentId = _currentDeviceId.value
                    if (currentId.isNotEmpty() && incoming.topic.contains(currentId)) {
                        lastHeartbeatTime = System.currentTimeMillis()
                        if (!_isDeviceOnline.value) {
                            LogRepository.append("Gateway: Device $currentId is ONLINE via MQTT")
                            _isDeviceOnline.value = true
                        }
                    }
                }
            }

            // Watchdog: MQTT-only path times out after 45s of silence.
            // If GATT is still up, keep the device online so the slider doesn't grey out
            // during relay switching / brief packet gaps.
            scope.launch {
                while (true) {
                    delay(5000)
                    val bleConnected = _bleTransport?.connectionState?.value == ConnectionState.Connected
                    if (bleConnected) {
                        if (!_isDeviceOnline.value) _isDeviceOnline.value = true
                        continue
                    }
                    val now = System.currentTimeMillis()
                    if (_isDeviceOnline.value && now - lastHeartbeatTime > 45000) {
                        LogRepository.append("Gateway: Device ${_currentDeviceId.value} is OFFLINE (timeout 45s)")
                        _isDeviceOnline.value = false
                    }
                }
            }

            // Charger evidence expires. Going to Unknown is deliberately *not*
            // the same as going idle: an unreachable charger tells us nothing
            // about whether it is still charging.
            scope.launch {
                while (true) {
                    delay(5_000)
                    val now = System.currentTimeMillis()
                    val observed = _hardwareSession.value.observedAtOrZero
                    if (observed > 0L && now - observed > HardwareSession.STALE_AFTER_MS) {
                        LogRepository.append("Gateway: Charger evidence stale — hardware state unknown")
                        _hardwareSession.value = HardwareSession.Unknown
                    }
                    val bleState = _bleTransport?.connectionState?.value
                    if (ChargeSessionLogic.shouldDropSilentGatt(
                            bleReady = bleState == ConnectionState.Connected,
                            hasOpenLease = SessionLeaseStore.hasOpenLease,
                            lastBlePacketAtMs = lastBlePacketAt,
                            nowMs = now,
                        )
                    ) {
                        LogRepository.append(
                            "Gateway: GATT connected but silent during owned session — " +
                                "dropping dead handle to reconnect",
                        )
                        _bleTransport?.disconnect()
                    }
                }
            }

            // Courtesy release: a connected phone makes the charger invisible to
            // everyone else, so an idle link is given back automatically.
            scope.launch {
                while (true) {
                    delay(5_000)
                    val state = _bleTransport?.connectionState?.value ?: continue
                    if (BleConnectionPolicy.shouldReleaseIdleLink(
                            state = state,
                            hasOpenLease = SessionLeaseStore.hasOpenLease,
                            lastUserActionAtMs = lastUserActionAt,
                            nowMs = System.currentTimeMillis(),
                        )
                    ) {
                        lastUserActionAt = 0L
                        _idleReleased.value = System.currentTimeMillis()
                        releaseLink("idle for ${BleConnectionPolicy.IDLE_RELEASE_MS / 1000}s with no session")
                    }
                }
            }
        }
    }

    private fun setupBridge() {
        scope.launch {
            bleTransport.connectionState.collect { state ->
                if (state == ConnectionState.Disconnected || state == ConnectionState.Failed) {
                    // Link is gone, so we no longer know what the charger is doing
                    // over GATT. MQTT idle is ignored separately while the lease
                    // is open, so this Unknown holds the session instead of ending it.
                    _hardwareSession.value = HardwareSession.Unknown
                }
                if (BleConnectionPolicy.shouldReconnectAfterDrop(
                        hasOpenLease = SessionLeaseStore.hasOpenLease,
                        userRequestedDisconnect = userRequestedDisconnect,
                        state = state,
                    )
                ) {
                    LogRepository.append("Bridge: BLE $state during owned session — scheduling reconnect")
                    scheduleReconnect()
                    return@collect
                }
                if (state == ConnectionState.Scanning || state == ConnectionState.Connecting) {
                    // Supervisor is still working. Do not clear isReconnecting:
                    // that made the card flicker to "session still running" and
                    // look like auto-reconnect had given up.
                    if (reconnectJob?.isActive == true) _isReconnecting.value = true
                    return@collect
                }
                if (state != ConnectionState.Connected) {
                    if (reconnectJob?.isActive != true) _isReconnecting.value = false
                    return@collect
                }

                reconnectAttempt = 0
                reconnectJob?.cancel()
                _isReconnecting.value = false
                linkRestoredAt = System.currentTimeMillis()
                lastBlePacketAt = linkRestoredAt

                // The charger re-sends the frame it was waiting to have acked
                // when we vanished — same queue entry, so same message id and
                // action. That resend is the only evidence we get that a session
                // is still running, and the retransmit filter was throwing it
                // away as a duplicate, leaving the charger state permanently
                // Unknown after every reconnect.
                retransmits.reset()

                val address = bleTransport.connectedAddress.value
                if (address != null) {
                    LogRepository.append("Bridge: BLE Connected ($address)")
                    lastHeartbeatTime = System.currentTimeMillis()
                    _isDeviceOnline.value = true
                    flushPendingCommand()

                    // Save for auto-connect
                    val discoveredName = bleTransport.discoveredDevices.value
                        .find { it.address == address }?.name
                    val ctx = context!!

                        // Prefer QR / explicit device ID over MAC guessing.
                        // ESP32 BLE MAC is often Base MAC (deviceId) + 1.
                        val macClean = address.replace(":", "").lowercase()
                        val guessedId = try {
                            val lastChar = macClean.last()
                            val base = macClean.dropLast(1)
                            val lastDigit = lastChar.toString().toInt(16)
                            if (lastDigit > 0) {
                                base + (lastDigit - 1).toString(16)
                            } else {
                                macClean
                            }
                        } catch (e: Exception) {
                            macClean
                        }

                        val savedId = preferredDeviceId
                            ?: MqttPrefs.loadDeviceId(ctx).takeIf { it.length == 12 }
                        val deviceId = when {
                            savedId != null && savedId.length == 12 -> {
                                LogRepository.append("Bridge: Using known Device ID: $savedId")
                                savedId
                            }
                            else -> {
                                LogRepository.append("Bridge: Guessing Device ID from MAC: $guessedId")
                                guessedId
                            }
                        }
                        preferredDeviceId = null

                        val deviceName = discoveredName
                            ?: if (deviceId.length == 12) "PowerTap_$deviceId" else null
                        BlePrefs.saveLastDevice(ctx, address, deviceName)
                        BlePrefs.markPaired(ctx, address)

                        _currentDeviceId.value = deviceId
                        val config = MqttPrefs.load(ctx)
                        _mqttTransport.connect(config, deviceId)
                        MqttPrefs.save(ctx, config, deviceId)
                    }
            }
        }

        scope.launch {
            bleTransport.incoming.collect { payload ->
                lastHeartbeatTime = System.currentTimeMillis()
                lastBlePacketAt = lastHeartbeatTime
                if (!_isDeviceOnline.value) {
                    LogRepository.append("Gateway: Device ${_currentDeviceId.value} is ONLINE via BLE")
                    _isDeviceOnline.value = true
                }

                if (_isBridgeEnabled.value) {
                    val trimmed = payload.trim()
                    if (!ChargeSessionLogic.isHighFrequencyPacket(trimmed)) {
                        LogRepository.append("Bridge: BLE -> MQTT: $trimmed")
                    }
                    
                    // Auto-detection of Device ID from Packet Footer
                    try {
                        val arr = JSONArray(trimmed)
                        if (arr.length() >= 5) {
                            val detectedId = arr.optString(4).lowercase()
                            val currentId = MqttPrefs.loadDeviceId(context!!)
                            if (detectedId.isNotEmpty() && detectedId != currentId && detectedId.length == 12) {
                                LogRepository.append("Bridge: Packet Sniffed New Device ID: $detectedId")
                                _currentDeviceId.value = detectedId
                                val config = MqttPrefs.load(context!!)
                                MqttPrefs.save(context!!, config, detectedId)
                                _mqttTransport.connect(config, detectedId)
                            }
                        }
                    } catch (e: Exception) { /* Not an OCPP packet with ID */ }

                    // Update Meter Data State
                    parseMeterData(payload)?.let { data ->
                        _latestMeterData.value = data
                        val current = _meterHistory.value.toMutableList()
                        current.add(0, data)
                        if (current.size > 100) current.removeAt(current.size - 1)
                        _meterHistory.value = current
                    }

                    handleOcppPacket(payload)

                    // Forward to MQTT - handle ACKs (type 3) and packets (type 2)
                    if (trimmed.startsWith("[3,")) {
                        _mqttTransport.publishAck(payload) { ok ->
                            if (!ok) LogRepository.append("Error: Failed to publish ACK to MQTT")
                        }
                    } else {
                        _mqttTransport.publishPacket(payload) { ok ->
                            if (!ok) LogRepository.append("Error: Failed to publish Packet to MQTT")
                        }
                    }
                }
            }
        }

        scope.launch {
            bleTransport.outgoing.collect { payload ->
                noteOutgoingCommand(payload)
            }
        }

        scope.launch {
            _mqttTransport.incoming.collect { incoming ->
                if (!_isBridgeEnabled.value) return@collect
                handleOcppPacket(incoming.payload)
                // Commands plus CSMS CallResults (BootNotification/Heartbeat ACK).
                // Do not echo MeterValues — that saturates GATT.
                if (!ChargeSessionLogic.mqttShouldForwardToBle(incoming.payload)) return@collect
                val now = System.currentTimeMillis()
                if (ChargeSessionLogic.isDuplicateMqttForward(
                        incoming.payload, lastForwardedMqtt, lastForwardedMqttAt, now,
                    )
                ) {
                    LogRepository.append("Bridge: skip duplicate MQTT->BLE")
                    return@collect
                }
                lastForwardedMqtt = incoming.payload
                lastForwardedMqttAt = now
                LogRepository.append("Bridge: MQTT -> BLE: ${incoming.payload}")
                if (!bleTransport.send(incoming.payload)) {
                    LogRepository.append("Error: Failed to send MQTT command to BLE")
                    pendingChargerCommand = incoming.payload
                }
            }
        }
    }

    private fun parseMeterData(payload: String): MeterData? {
        return try {
            val arr = JSONArray(payload)
            if (arr.length() < 4) return null
            
            val messageType = arr.getString(2)
            val dataObj = arr.getJSONObject(3)
            
            val meterValue = when (messageType) {
                "MeterValues" -> dataObj.optJSONObject("meterValue")
                "Heartbeat", "HeartBeat" -> dataObj
                "StartTransaction" -> {
                    // Initialize with start energy
                    val e = dataObj.optDouble("meterStart", 0.0)
                    JSONObject().put("e", e)
                }
                "StopTransaction" -> {
                    val e = dataObj.optDouble("meterStop", 0.0)
                    JSONObject().put("e", e)
                }
                else -> null
            } ?: return null

            MeterData(
                voltage = meterValue.optDouble("v", 0.0).toFloat() / 1000f,
                current = meterValue.optDouble("c", 0.0).toFloat() / 1000f,
                power = meterValue.optDouble("p", 0.0).toFloat() / 1000f,
                energy = meterValue.optDouble("e", 0.0).toFloat() / 1000f,
                frequency = meterValue.optDouble("f", 0.0).toFloat()
            )
        } catch (e: Exception) {
            null
        }
    }

    private var pendingMode: String? = null
    private var pendingTid: String? = null
    private val sessionMeterData = mutableMapOf<String, MutableList<MeterData>>()

    private fun noteOutgoingCommand(payload: String) {
        try {
            val arr = JSONArray(payload.trim())
            if (arr.length() < 3) return
            val frameType = when (val raw = arr.opt(0)) {
                is Number -> raw.toInt()
                else -> arr.optString(0).toIntOrNull() ?: -1
            }
            if (frameType != 2 || arr.length() < 3) return
            val action = arr.optString(2)
            if (action == "RemoteStart" || action == "RemoteStop") {
                lastCommandMsgId = arr.optString(1)
                lastCommandAction = action
                LogRepository.append("Gateway: Outgoing $action id=$lastCommandMsgId")
                handleOcppPacket(payload)
            }
        } catch (_: Exception) { }
    }

    private fun handleCallResult(arr: JSONArray) {
        val msgId = arr.optString(1)
        val payload = arr.optJSONObject(2) ?: return
        val status = payload.optString("status", "Unknown")
        val accepted = status.equals("Accepted", ignoreCase = true)
        if (!ChargeSessionLogic.callResultMatchesCommand(msgId, lastCommandMsgId)) {
            LogRepository.append("Gateway: Ignoring CallResult id=$msgId (not our RemoteStart/Stop)")
            return
        }
        val action = lastCommandAction
        LogRepository.append("Gateway: ACK $status for ${action ?: "command"} (id=$msgId)")

        // A rejection that names the running session is the fastest way out of a
        // dangling-session standoff. It travels on the ack rather than a separate
        // flow so one collector decides what to do: with two, whichever resumed
        // first decided whether the session got repaired.
        val activeTid = if (accepted) {
            null
        } else {
            payload.optString("activeTid").trim().takeIf { it.isNotBlank() }
        }
        if (activeTid != null) {
            LogRepository.append("Gateway: Charger is running tid=$activeTid")
            _hardwareSession.value = HardwareSession.Charging(
                activeTid,
                System.currentTimeMillis(),
            )
        }
        _commandAck.value = CommandAck(
            messageId = msgId,
            accepted = accepted,
            status = status,
            action = action,
            activeTid = activeTid,
        )
        if (!accepted) {
            if (action == "RemoteStart") {
                _bridgeDetectedState.value = DeviceState.STATE_AVAILABLE
                _chargingStartedAt.value = null
            } else if (action == "RemoteStop") {
                _bridgeDetectedState.value = DeviceState.STATE_CHARGING
            }
        }
    }

    /** The charger quotes its message id as a string on some frames, a number on others. */
    private fun frameMessageId(arr: JSONArray): String = when (val raw = arr.opt(1)) {
        is Number -> raw.toLong().toString()
        else -> arr.optString(1)
    }

    /**
     * Record what the charger just told us about its own relay. Works for frames
     * arriving over BLE or MQTT, so a charger on Wi-Fi is just as observable as
     * one the phone is bridging.
     *
     * A frame we have already counted is dropped rather than re-timestamped: the
     * charger re-sends unacknowledged frames verbatim, and treating a retry as a
     * fresh reading is what let a heartbeat queued before Start close the session
     * it was supposed to precede.
     */
    private fun recordHardwareEvidence(arr: JSONArray) {
        val action = arr.optString(2)
        if (action == "BootNotification") {
            // The charger restarted, so its message counter did too and every id
            // we remember is about to be handed out again.
            retransmits.reset()
        }
        val data = arr.optJSONObject(3) ?: return
        val observed = HardwareSession.fromAction(
            action = action,
            transactionId = data.optString("transactionId").trim(),
            status = data.optString("status"),
            observedAt = System.currentTimeMillis(),
        ) ?: return
        if (!retransmits.accept(messageId = frameMessageId(arr), action = action)) return

        val previous = _hardwareSession.value
        val now = System.currentTimeMillis()
        val bleReady = _bleTransport?.connectionState?.value == ConnectionState.Connected
        // Heartbeat/StatusNotification idle right after StartTransaction is often a
        // queued frame or a low-current settle reading (c≈0 while the relay closes).
        // Treating it as proof the session ended is what showed "Charging finished"
        // / disconnected the phone a second after Start. StopTransaction is definite.
        if (observed is HardwareSession.Idle &&
            ChargeSessionLogic.shouldHoldThroughIdleEvidence(
                leaseState = SessionLeaseStore.open?.state,
                bleReady = bleReady,
                isStopTransaction = action == "StopTransaction",
                msSinceReconnect = now - linkRestoredAt,
                reconnectGraceMs = SessionReconciler.START_GRACE_MS,
            )
        ) {
            LogRepository.append(
                "Gateway: Ignoring $action idle — holding owned session " +
                    "(bleReady=$bleReady, ${now - linkRestoredAt}ms since reconnect)",
            )
            return
        }
        if (observed is HardwareSession.Idle &&
            previous is HardwareSession.Charging &&
            action != "StopTransaction" &&
            now - previous.observedAt < SessionReconciler.START_GRACE_MS
        ) {
            LogRepository.append(
                "Gateway: Ignoring $action idle — StartTransaction was ${now - previous.observedAt}ms ago",
            )
            return
        }

        _hardwareSession.value = observed
        val changed = when {
            previous is HardwareSession.Charging && observed is HardwareSession.Charging ->
                previous.transactionId != observed.transactionId
            else -> previous::class != observed::class
        }
        if (changed) {
            LogRepository.append("Gateway: Charger reports $observed")
        }
    }

    private fun handleOcppPacket(payload: String) {
        try {
            val arr = JSONArray(payload)
            if (arr.length() < 3) return
            recordHardwareEvidence(arr)
            val frameType = when (val raw = arr.opt(0)) {
                is Number -> raw.toInt()
                else -> arr.optString(0).toIntOrNull() ?: -1
            }
            if (frameType == 3) {
                handleCallResult(arr)
                return
            }
            if (arr.length() < 4) return
            val msgType = arr.getString(2)
            val data = arr.getJSONObject(3)
            val deviceId = if (arr.length() >= 5) arr.getString(4) else _currentDeviceId.value

            when (msgType) {
                "RemoteStart" -> {
                    pendingMode = data.optString("mode", "full")
                    pendingTid = data.optString("tid")
                    lastCommandAction = "RemoteStart"
                    lastCommandMsgId = arr.optString(1)
                }
                "RemoteStop" -> {
                    lastCommandAction = "RemoteStop"
                    lastCommandMsgId = arr.optString(1)
                }
                "StartTransaction" -> {
                    val tid = data.getString("transactionId")
                    val mStart = data.getDouble("meterStart").toFloat()
                    val mode = if (tid == pendingTid) pendingMode else "full"
                    val startedAt = System.currentTimeMillis()
                    TransactionRepository.startSession(
                        ChargingSession(
                            transactionId = tid,
                            deviceId = deviceId,
                            startTime = startedAt,
                            meterStart = mStart,
                            mode = mode,
                            status = "Active"
                        )
                    )
                    sessionMeterData[tid] = mutableListOf()
                    _chargingStartedAt.value = startedAt
                    // Hardware hint only — Home must not treat this as a billed session.
                    // Firebase PowerTapMonitor.state is the source of truth for CHARGING.
                    _bridgeDetectedState.value = DeviceState.STATE_CHARGING
                    LogRepository.append("Gateway: StartTransaction tid=$tid — hardware started, waiting for server state")
                }
                "StopTransaction" -> {
                    val tid = data.getString("transactionId")
                    val mStop = data.getDouble("meterStop").toFloat()
                    TransactionRepository.updateSession(tid, mStop, System.currentTimeMillis(), "Completed")
                    
                    sessionMeterData[tid]?.let { list ->
                        TransactionRepository.saveMeterDataList(tid, list)
                    }
                    sessionMeterData.remove(tid)
                    
                    _chargingStartedAt.value = null
                    _bridgeDetectedState.value = DeviceState.STATE_AVAILABLE
                    LogRepository.append("Gateway: StopTransaction tid=$tid — hardware stopped, waiting for server state")
                }
                "Heartbeat", "HeartBeat" -> {
                    val currentMilli = data.optDouble("c", 0.0)
                    val powerMilli = data.optDouble("p", 0.0)
                    // After a power-cycle the charger boots idle and only heartbeats.
                    // Cloud can still say CHARGING; hardware wins here.
                    // Do not flip AVAILABLE over a settle-window heartbeat while we
                    // still hold Charging evidence — that raced finishSession on start.
                    val charging = _hardwareSession.value as? HardwareSession.Charging
                    val settleMs = charging?.let { System.currentTimeMillis() - it.observedAt }
                    if (currentMilli < 300.0 && powerMilli < 50_000.0 &&
                        (settleMs == null || settleMs >= SessionReconciler.START_GRACE_MS)
                    ) {
                        _chargingStartedAt.value = null
                        _bridgeDetectedState.value = DeviceState.STATE_AVAILABLE
                    }
                }
                "StatusNotification" -> {
                    val status = data.optString("status")
                    if (status.equals("Available", ignoreCase = true) ||
                        status.equals("Finishing", ignoreCase = true)
                    ) {
                        _chargingStartedAt.value = null
                        _bridgeDetectedState.value = DeviceState.STATE_AVAILABLE
                    }
                }
                "MeterValues" -> {
                    val tid = data.optString("transactionId")
                    if (tid.isNotEmpty()) {
                        _bridgeDetectedState.value = DeviceState.STATE_CHARGING
                        val mv = data.optJSONObject("meterValue")
                        val mCurrent = mv?.optDouble("e")?.toFloat() ?: 0f
                        TransactionRepository.addMeterValue(tid, mCurrent)
                        
                        parseMeterData(payload)?.let { meterData ->
                            if (!sessionMeterData.containsKey(tid)) {
                                sessionMeterData[tid] = mutableListOf()
                            }
                            sessionMeterData[tid]?.add(meterData)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LogRepository.append("Error handling OCPP: ${e.message}")
        }
    }

    fun toggleBridge(enabled: Boolean) {
        _isBridgeEnabled.value = enabled
        LogRepository.append("Bridge ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    /** Snapshot of meter samples collected for the live charging session. */
    fun sessionMeterSamples(transactionId: String?): List<MeterData> {
        if (transactionId.isNullOrEmpty()) return emptyList()
        return sessionMeterData[transactionId]?.toList().orEmpty()
    }
}

data class CommandAck(
    val messageId: String,
    val accepted: Boolean,
    val status: String,
    val action: String?,
    /**
     * Set when the charger rejected the command and named the session it is
     * really running, which lets the caller take that session over instead of
     * leaving it with no owner.
     */
    val activeTid: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)
