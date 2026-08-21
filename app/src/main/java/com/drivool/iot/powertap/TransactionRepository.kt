package com.drivool.iot.powertap

import android.content.Context
import com.drivool.iot.powertap.contract.ChargingSession
import com.drivool.iot.powertap.contract.MeterData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object TransactionRepository {
    private const val PREFS_NAME = "charging_history"
    private const val KEY_SESSIONS = "sessions"
    private const val METER_DATA_DIR = "meter_data"

    private val _sessions = MutableStateFlow<List<ChargingSession>>(emptyList())
    val sessions: StateFlow<List<ChargingSession>> = _sessions.asStateFlow()
    
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs?.getString(KEY_SESSIONS, null)
        if (json != null) {
            val list = mutableListOf<ChargingSession>()
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(ChargingSession(
                    transactionId = obj.getString("tid"),
                    deviceId = obj.getString("did"),
                    startTime = obj.getLong("st"),
                    stopTime = if (obj.has("et")) obj.getLong("et") else null,
                    meterStart = obj.getDouble("ms").toFloat(),
                    meterStop = if (obj.has("me")) obj.getDouble("me").toFloat() else null,
                    mode = obj.optString("mo", "full"),
                    status = obj.optString("stat", "Completed")
                ))
            }
            _sessions.value = list
        }
    }

    fun startSession(session: ChargingSession) {
        val current = _sessions.value.toMutableList()
        if (current.none { it.transactionId == session.transactionId }) {
            current.add(0, session)
            updateAndSave(current)
        }
    }

    fun updateSession(transactionId: String, meterStop: Float, stopTime: Long, status: String = "Completed") {
        val current = _sessions.value.toMutableList()
        val index = current.indexOfFirst { it.transactionId == transactionId }
        if (index != -1) {
            val updated = current[index].copy(
                meterStop = meterStop,
                stopTime = stopTime,
                status = status
            )
            current[index] = updated
            updateAndSave(current)
        }
    }

    /**
     * Close out a session we turned out not to own.
     *
     * Happens when the charger reports a different transaction id than the one we
     * were holding — ours never really existed on the charger (or was overwritten
     * by a later start), so it is recorded as superseded rather than left showing
     * as active forever.
     */
    fun markSuperseded(transactionId: String) {
        val current = _sessions.value.toMutableList()
        val index = current.indexOfFirst { it.transactionId == transactionId }
        if (index == -1) return
        val existing = current[index]
        if (existing.status != "Active") return
        current[index] = existing.copy(
            status = "Superseded",
            stopTime = existing.stopTime ?: System.currentTimeMillis(),
        )
        updateAndSave(current)
    }

    fun addMeterValue(transactionId: String, currentMeter: Float) {
        val current = _sessions.value.toMutableList()
        val index = current.indexOfFirst { it.transactionId == transactionId }
        if (index != -1) {
            val updated = current[index].copy(meterStop = currentMeter)
            current[index] = updated
            _sessions.value = current
        }
    }

    fun saveMeterDataList(transactionId: String, dataList: List<MeterData>) {
        val context = appContext ?: return
        val dir = File(context.filesDir, METER_DATA_DIR)
        if (!dir.exists()) dir.mkdirs()
        
        val file = File(dir, "$transactionId.json")
        val arr = JSONArray()
        dataList.forEach {
            val obj = JSONObject()
            obj.put("v", it.voltage.toDouble())
            obj.put("c", it.current.toDouble())
            obj.put("p", it.power.toDouble())
            obj.put("e", it.energy.toDouble())
            obj.put("f", it.frequency.toDouble())
            obj.put("t", it.timestamp)
            arr.put(obj)
        }
        file.writeText(arr.toString())
    }

    fun getMeterDataList(transactionId: String): List<MeterData> {
        val context = appContext ?: return emptyList()
        val file = File(context.filesDir, "$METER_DATA_DIR/$transactionId.json")
        if (!file.exists()) return emptyList()
        
        return try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<MeterData>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(MeterData(
                    voltage = obj.getDouble("v").toFloat(),
                    current = obj.getDouble("c").toFloat(),
                    power = obj.getDouble("p").toFloat(),
                    energy = obj.getDouble("e").toFloat(),
                    frequency = obj.getDouble("f").toFloat(),
                    timestamp = obj.getLong("t")
                ))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun updateAndSave(list: List<ChargingSession>) {
        _sessions.value = list
        saveToDisk()
    }

    private fun saveToDisk() {
        val context = appContext ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        _sessions.value.forEach {
            val obj = JSONObject()
            obj.put("tid", it.transactionId)
            obj.put("did", it.deviceId)
            obj.put("st", it.startTime)
            if (it.stopTime != null) obj.put("et", it.stopTime)
            obj.put("ms", it.meterStart.toDouble())
            if (it.meterStop != null) obj.put("me", it.meterStop!!.toDouble())
            obj.put("mo", it.mode)
            obj.put("stat", it.status)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_SESSIONS, arr.toString()).apply()
    }
}
