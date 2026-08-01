// kotlin
package com.example.powertap

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object LogRepository {
    private val _logs = MutableLiveData<List<String>>(emptyList())
    val logs: LiveData<List<String>> = _logs

    fun append(message: String) {
        android.util.Log.d("PowerTap", message)
        val entry = "${System.currentTimeMillis()}: $message"
        val updated = listOf(entry) + (_logs.value ?: emptyList())
        _logs.postValue(updated)
    }

    fun clear() {
        _logs.postValue(emptyList())
    }
}
