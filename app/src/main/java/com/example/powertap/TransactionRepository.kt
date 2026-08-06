package com.drivool.iot.powertap

import android.content.Context
import com.drivool.iot.powertap.contract.ChargingSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

object TransactionRepository {
    private const val PREFS_NAME = "charging_history"
    private const val KEY_SESSIONS = "sessions"

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

    fun addMeterValue(transactionId: String, currentMeter: Float) {
        val current = _sessions.value.toMutableList()
        val index = current.indexOfFirst { it.transactionId == transactionId }
        if (index != -1) {
            val updated = current[index].copy(meterStop = currentMeter)
            current[index] = updated
            _sessions.value = current
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
