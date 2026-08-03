package com.drivool.iot.powertap

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.json.JSONObject

object DeviceState {
    const val STATE_AVAILABLE = 0
    const val STATE_STARTING = 1
    const val STATE_STARTED = 2
    const val STATE_CHARGING = 3
    const val STATE_STOPPING = 4
    const val STATE_STOPPED = 5
    const val STATE_POWER_FAIL = 6
    const val STATE_LOW_VOLTAGE = 7
    const val STATE_OVERLOAD = 8
    const val STATE_UNKNOWN_FAULT = 10
}

object FirebaseApiManager {
    private val database = FirebaseDatabase.getInstance()
    
    // Default credentials as in JS
    private var account = "mail4satya"
    private var key = ""

    fun setCredentials(account: String, key: String) {
        this.account = account
        this.key = key
    }

    fun executePowerTapAPI(
        deviceId: String,
        api: String,
        params: Map<String, Any>,
        onResult: (Map<String, Any>) -> Unit,
        onError: (String) -> Unit
    ) {
        val timeNow = System.currentTimeMillis()
        val rid = timeNow.toString()
        
        val cmd = mutableMapOf<String, Any>()
        cmd["api"] = api
        cmd["param"] = params
        cmd["rid"] = rid

        val commandToExecute = mutableMapOf<String, Any>()
        commandToExecute["key"] = key
        commandToExecute["id"] = account
        commandToExecute["cmd"] = cmd
        commandToExecute["time"] = timeNow

        // Listen for response
        val responseRef = database.getReference("Response/PTD/$deviceId/$account/$timeNow")
        responseRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val result = snapshot.value as? Map<String, Any>
                    if (result != null) {
                        onResult(result)
                        // Optional: remove listener after response
                        responseRef.removeEventListener(this)
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                onError("Response listener cancelled: ${error.message}")
            }
        })

        // Write command with concurrency check
        val commandRef = database.getReference("Commands/PTD/$deviceId")
        commandRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val existingCommand = snapshot.value as? Map<*, *>
                if (existingCommand == null) {
                    commandRef.setValue(commandToExecute)
                } else {
                    val existingId = existingCommand["id"] as? String
                    val existingTime = existingCommand["time"] as? Long ?: 0L
                    
                    if (existingId == account) {
                        commandRef.setValue(commandToExecute)
                    } else if (timeNow > existingTime + 5000) {
                        commandRef.setValue(commandToExecute)
                    } else {
                        onError("Device is being used by another user: $existingId")
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                onError("Failed to check existing command: ${error.message}")
            }
        })
    }

    fun startCharging(
        deviceId: String,
        mode: String,
        value: Int?,
        onResult: (Map<String, Any>) -> Unit,
        onError: (String) -> Unit
    ) {
        val params = mutableMapOf<String, Any>("mode" to mode)
        if (value != null) {
            if (mode == "time") params["time"] = value
            else if (mode == "units") params["units"] = value
        }
        executePowerTapAPI(deviceId, "RemoteStart", params, onResult, onError)
    }

    fun stopCharging(
        deviceId: String,
        transactionId: String,
        onResult: (Map<String, Any>) -> Unit,
        onError: (String) -> Unit
    ) {
        val params = mapOf("tid" to transactionId)
        executePowerTapAPI(deviceId, "RemoteStop", params, onResult, onError)
    }
}
