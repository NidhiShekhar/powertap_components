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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
     * Firmware replies `[3,"<msgId>",{"status":"Accepted"}]` immediately on RemoteStart/Stop,
     * then later sends `StartTransaction` / `StopTransaction` when the relay actually switches.
     */
    private val _commandAck = MutableStateFlow<CommandAck?>(null)
    val commandAck: StateFlow<CommandAck?> = _commandAck.asStateFlow()

    private val _chargingStartedAt = MutableStateFlow<Long?>(null)
    val chargingStartedAt: StateFlow<Long?> = _chargingStartedAt.asStateFlow()

    /** True when the user tapped Disconnect this process — skip auto-reconnect until they pick a device. */
    var userRequestedDisconnect: Boolean = false
        private set

    private var lastCommandMsgId: String? = null
    private var lastCommandAction: String? = null

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
     * @return false if Bluetooth is unavailable/disabled
     */
    fun connectToBle(
        bleAddress: String,
        deviceId: String? = null,
        displayName: String? = null,
        scanFirst: Boolean = true,
    ): Boolean {
        val ctx = context ?: return false
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            LogRepository.append("Gateway: BLE connect aborted — Bluetooth off")
            return false
        }

        userRequestedDisconnect = false

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

        LogRepository.append(
            "Gateway: BLE connect → $bleAddress, deviceId $resolvedId, scanFirst=$scanFirst",
        )

        val current = bleTransport.connectedAddress.value
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
        userRequestedDisconnect = true
        _bleTransport?.disconnect()
        LogRepository.append("Gateway: User disconnected BLE — auto-connect paused for this session")
    }

    /**
     * Reconnect the last PowerTap on app open (or when Bluetooth turns back on).
     * Skipped if the user explicitly disconnected this session.
     */
    fun tryAutoConnect(): Boolean {
        val ctx = context ?: return false
        if (userRequestedDisconnect) {
            LogRepository.append("Gateway: Auto-connect skipped — user disconnected")
            return false
        }
        if (!BlePrefs.isAutoConnectEnabled(ctx)) return false
        val lastAddr = BlePrefs.getLastDeviceAddress(ctx) ?: return false
        val state = _bleTransport?.connectionState?.value ?: return false
        if (state == ConnectionState.Connected ||
            state == ConnectionState.Connecting ||
            state == ConnectionState.Scanning
        ) {
            return true
        }
        val known = BlePrefs.getKnownDevices(ctx).firstOrNull { (_, addr) ->
            addr.equals(lastAddr, ignoreCase = true)
        }
        LogRepository.append("Gateway: Auto-connecting BLE to $lastAddr...")
        return connectToBle(lastAddr, displayName = known?.first, scanFirst = true)
    }

    fun init(context: Context) {
        this.context = context.applicationContext
        TransactionRepository.init(context.applicationContext)
        if (_bleTransport == null) {
            _bleTransport = BleTransport(context.applicationContext, log = LogRepository::append)
            setupBridge()

            tryAutoConnect()
            
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
                    kotlinx.coroutines.delay(5000)
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
        }
    }

    private fun setupBridge() {
        scope.launch {
            bleTransport.connectionState.collect { state ->
                if (state == ConnectionState.Connected) {
                    val address = bleTransport.connectedAddress.value
                    if (address != null) {
                        LogRepository.append("Bridge: BLE Connected ($address)")
                        lastHeartbeatTime = System.currentTimeMillis()
                        _isDeviceOnline.value = true
                        
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
        }

        scope.launch {
            bleTransport.incoming.collect { payload ->
                LogRepository.append("Gateway: Received BLE packet, marking online")
                lastHeartbeatTime = System.currentTimeMillis()
                if (!_isDeviceOnline.value) {
                    LogRepository.append("Gateway: Device ${_currentDeviceId.value} is ONLINE via BLE")
                    _isDeviceOnline.value = true
                }

                if (_isBridgeEnabled.value) {
                    val trimmed = payload.trim()
                    LogRepository.append("Bridge: BLE -> MQTT: $trimmed")
                    
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
                if (_isBridgeEnabled.value) {
                    LogRepository.append("Bridge: MQTT -> BLE: ${incoming.payload}")
                    handleOcppPacket(incoming.payload)
                    // Forward directly to BLE without throttle as firmware handles duplicates now
                    if (!bleTransport.send(incoming.payload)) {
                        LogRepository.append("Error: Failed to send MQTT message to BLE")
                    }
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
        val action = lastCommandAction
        LogRepository.append("Gateway: ACK $status for ${action ?: "command"} (id=$msgId)")
        _commandAck.value = CommandAck(
            messageId = msgId,
            accepted = accepted,
            status = status,
            action = action,
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

    private fun handleOcppPacket(payload: String) {
        try {
            val arr = JSONArray(payload)
            if (arr.length() < 3) return
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
                    _bridgeDetectedState.value = DeviceState.STATE_CHARGING
                    LogRepository.append("Gateway: StartTransaction tid=$tid — charging confirmed")
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
                    LogRepository.append("Gateway: StopTransaction tid=$tid — charging stopped")
                }
                "MeterValues" -> {
                    val tid = data.optString("transactionId")
                    if (tid.isNotEmpty()) {
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
}

data class CommandAck(
    val messageId: String,
    val accepted: Boolean,
    val status: String,
    val action: String?,
    val timestamp: Long = System.currentTimeMillis(),
)
