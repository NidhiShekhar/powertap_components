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
    
    private val account: String
        get() = AuthManager.userEmail?.substringBefore("@")?.replace(Regex("[.#$\\[\\]]"), "_") ?: "guest"

    private var key = ""

    fun setCredentials(key: String) {
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

        // Write command directly to avoid double round-trip
        val commandRef = database.getReference("Commands/PTD/$deviceId")
        commandRef.setValue(commandToExecute)
            .addOnFailureListener { e -> onError("Firebase write failed: ${e.message}") }
    }

    fun startCharging(
        deviceId: String,
        mode: String,
        value: Int?,
        onResult: (Map<String, Any>) -> Unit,
        onError: (String) -> Unit
    ) {
        val params = mutableMapOf<String, Any>("mode" to mode)
        params["tid"] = "T" + System.currentTimeMillis().toString() // Add transaction ID
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
