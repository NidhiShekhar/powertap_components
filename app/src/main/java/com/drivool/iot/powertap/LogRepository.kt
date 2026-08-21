// kotlin
package com.drivool.iot.powertap

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object LogRepository {
    private const val MAX_LOGS = 150
    private val _logs = MutableLiveData<List<String>>(emptyList())
    val logs: LiveData<List<String>> = _logs

    fun append(message: String) {
        android.util.Log.d("PowerTap", message)
        val entry = "${System.currentTimeMillis()}: $message"
        val current = _logs.value ?: emptyList()
        _logs.postValue((listOf(entry) + current).take(MAX_LOGS))
    }

    fun clear() {
        _logs.postValue(emptyList())
    }
}
