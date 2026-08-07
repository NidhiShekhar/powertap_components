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

    private var context: Context? = null

    private val _currentDeviceId = MutableStateFlow<String>("")
    val currentDeviceId: StateFlow<String> = _currentDeviceId.asStateFlow()

    private val _isDeviceOnline = MutableStateFlow(false)
    val isDeviceOnline: StateFlow<Boolean> = _isDeviceOnline.asStateFlow()

    private var lastHeartbeatTime = 0L

    /** Device ID from QR/manual setup; preferred over BLE MAC guessing on connect. */
    private var preferredDeviceId: String? = null

    /**
     * Connect to a PowerTap using details from a scanned QR code.
     * Saves BLE + MQTT identity, then starts a targeted BLE scan/connect.
     * @return false if Bluetooth is unavailable/disabled
     */
    fun connectFromQr(qr: PowerTapQr): Boolean {
        val ctx = context ?: return false
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            LogRepository.append("Gateway: QR connect aborted — Bluetooth off")
            return false
        }

        preferredDeviceId = qr.deviceId
        _currentDeviceId.value = qr.deviceId

        val config = MqttPrefs.load(ctx)
        MqttPrefs.save(ctx, config, qr.deviceId)
        BlePrefs.saveLastDevice(ctx, qr.bleAddress, qr.displayName)

        LogRepository.append("Gateway: QR connect → BLE ${qr.bleAddress}, deviceId ${qr.deviceId}")
        _mqttTransport.connect(config, qr.deviceId)

        // Same path as auto-connect: scan until this MAC is seen, then GATT connect.
        bleTransport.stopScan()
        bleTransport.startScan(qr.bleAddress)
        return true
    }

    fun init(context: Context) {
        this.context = context.applicationContext
        TransactionRepository.init(context.applicationContext)
        if (_bleTransport == null) {
            _bleTransport = BleTransport(context.applicationContext, log = LogRepository::append)
            setupBridge()

            // Auto-connect BLE if enabled and last device exists
            if (BlePrefs.isAutoConnectEnabled(context)) {
                BlePrefs.getLastDeviceAddress(context)?.let { lastAddr ->
                    LogRepository.append("Gateway: Auto-connecting BLE to $lastAddr...")
                    _bleTransport?.startScan(lastAddr)
                }
            }
            
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

            // Watchdog to mark offline if no heartbeat for 45s
            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(5000)
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
                "Heartbeat" -> dataObj
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

    private fun handleOcppPacket(payload: String) {
        try {
            val arr = JSONArray(payload)
            if (arr.length() < 4) return
            val msgType = arr.getString(2)
            val data = arr.getJSONObject(3)
            val deviceId = if (arr.length() >= 5) arr.getString(4) else _currentDeviceId.value

            when (msgType) {
                "RemoteStart" -> {
                    pendingMode = data.optString("mode", "full")
                    pendingTid = data.optString("tid")
                }
                "StartTransaction" -> {
                    val tid = data.getString("transactionId")
                    val mStart = data.getDouble("meterStart").toFloat()
                    val mode = if (tid == pendingTid) pendingMode else "full"
                    TransactionRepository.startSession(
                        ChargingSession(
                            transactionId = tid,
                            deviceId = deviceId,
                            startTime = System.currentTimeMillis(),
                            meterStart = mStart,
                            mode = mode,
                            status = "Active"
                        )
                    )
                    _bridgeDetectedState.value = 3 // STATE_CHARGING
                }
                "StopTransaction" -> {
                    val tid = data.getString("transactionId")
                    val mStop = data.getDouble("meterStop").toFloat()
                    TransactionRepository.updateSession(tid, mStop, System.currentTimeMillis(), "Completed")
                    _bridgeDetectedState.value = 0 // STATE_AVAILABLE
                }
                "MeterValues" -> {
                    val tid = data.optString("transactionId")
                    if (tid.isNotEmpty()) {
                        val mv = data.optJSONObject("meterValue")
                        val mCurrent = mv?.optDouble("e")?.toFloat() ?: 0f
                        TransactionRepository.addMeterValue(tid, mCurrent)
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
