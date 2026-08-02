package com.example.powertap

import android.content.Context
import com.example.powertap.ble.BleTransport
import com.example.powertap.contract.DeviceTransport
import com.example.powertap.contract.ConnectionState
import com.example.powertap.mqtt.MqttPrefs
import com.example.powertap.mqtt.MqttTransport
import com.example.powertap.contract.MeterData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

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

    private var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext
        if (_bleTransport == null) {
            _bleTransport = BleTransport(context.applicationContext, log = LogRepository::append)
            setupBridge()
            
            // Auto-connect MQTT if config exists
            val config = MqttPrefs.load(context)
            val deviceId = MqttPrefs.loadDeviceId(context).ifBlank { null }
            if (config.host.isNotBlank()) {
                LogRepository.append("Auto-connecting MQTT to ${config.host}...")
                _mqttTransport.connect(config, deviceId)
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
                        // We don't overwrite the ID here anymore because the MAC might differ from the ESP's Internal ID
                        // Instead, we wait for a message from the ESP to "sniff" the correct ID
                    }
                }
            }
        }

        scope.launch {
            bleTransport.incoming.collect { payload ->
                if (_isBridgeEnabled.value) {
                    LogRepository.append("Bridge: BLE -> MQTT: $payload")
                    
                    // Update Meter Data State
                    parseMeterData(payload)?.let { data ->
                        _latestMeterData.value = data
                        val current = _meterHistory.value.toMutableList()
                        current.add(0, data)
                        if (current.size > 100) current.removeAt(current.size - 1)
                        _meterHistory.value = current
                    }

                    // Forward to MQTT: ACKs go to specific topic, others to general packet topic
                    if (payload.startsWith("[3,")) {
                        _mqttTransport.publishAck(payload)
                    } else {
                        _mqttTransport.publishPacket(payload)
                    }
                }
            }
        }

        scope.launch {
            _mqttTransport.incoming.collect { incoming ->
                if (_isBridgeEnabled.value) {
                    LogRepository.append("Bridge: MQTT -> BLE: ${incoming.payload}")
                    bleTransport.send(incoming.payload)
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

    fun toggleBridge(enabled: Boolean) {
        _isBridgeEnabled.value = enabled
        LogRepository.append("Bridge ${if (enabled) "ENABLED" else "DISABLED"}")
    }
}
