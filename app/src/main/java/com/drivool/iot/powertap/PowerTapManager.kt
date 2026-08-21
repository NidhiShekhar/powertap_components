package com.drivool.iot.powertap

import android.content.Context
import com.drivool.iot.powertap.ble.BleTransport
import com.drivool.iot.powertap.contract.ConnectionState
import com.drivool.iot.powertap.mqtt.MqttConfig
import com.drivool.iot.powertap.mqtt.MqttTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

object PowerTapManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val bleTransport: BleTransport get() = (GatewayManager.bleTransport as BleTransport)

    private val _mqttTransport = GatewayManager.mqttTransport
    val mqttTransport: MqttTransport = _mqttTransport

    private val _powerData = MutableStateFlow<PowerData?>(null)
    val powerData: StateFlow<PowerData?> = _powerData.asStateFlow()

    fun init() {
        observeMqtt()
    }

    private fun observeMqtt() {
        scope.launch {
            _mqttTransport.incoming.collect { incoming ->
                parsePacket(incoming.payload)
            }
        }
    }

    private fun parsePacket(payload: String) {
        try {
            val arr = JSONArray(payload)
            val type = arr.optString(2)
            if (type == "HeartBeat" || type == "MeterValues") {
                val dataObj = arr.get(3)
                var data: JSONObject = if (dataObj is JSONObject) {
                    dataObj
                } else {
                    // Try to fix common formatting issues if it's a string
                    val raw = dataObj.toString()
                        .replace("'", "\"")
                        .replace(":,", ":0,") // handle empty values
                    
                    try {
                        JSONObject(raw)
                    } catch (e: Exception) {
                        // Manual extraction if JSON parsing still fails
                        val json = JSONObject()
                        regexExtract(raw, "v")?.let { json.put("v", it) }
                        regexExtract(raw, "c")?.let { json.put("c", it) }
                        regexExtract(raw, "p")?.let { json.put("p", it) }
                        regexExtract(raw, "e")?.let { json.put("e", it) }
                        json
                    }
                }

                // Handle nested meterValue if present
                if (data.has("meterValue")) {
                    data = data.getJSONObject("meterValue")
                }

                // Scaling based on typical PowerTap packets:
                // v: 234620 -> 234.6V  (divide by 1000)
                // c: 12880  -> 12.88A  (divide by 1000)
                // p: 3022759 -> 3.022kW (divide by 1000000 for kW)
                // e: 23079013 -> 23.07kWh (divide by 1000000 for kWh)
                val v = data.optDouble("v", 0.0) / 1000.0
                val c = data.optDouble("c", 0.0) / 1000.0
                val p = data.optDouble("p", 0.0) / 1000000.0 // kW
                val e = data.optDouble("e", 0.0) / 1000.0    // Wh

                _powerData.value = PowerData(
                    voltage = v,
                    current = c,
                    power = p,
                    energy = e
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("PowerTapManager", "Parse error: ${e.message}")
        }
    }

    private fun regexExtract(raw: String, key: String): Double? {
        val pattern = "\"$key\":\\s*\"?([0-9.]+)\"?".toRegex()
        return pattern.find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
    }
}

data class PowerData(
    val voltage: Double,
    val current: Double,
    val power: Double,
    val energy: Double
)
