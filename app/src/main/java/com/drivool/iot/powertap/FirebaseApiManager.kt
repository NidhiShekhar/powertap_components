package com.drivool.iot.powertap

import android.os.Handler
import android.os.Looper
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * Account key used across Commands/Response and session ownership.
     * Same derivation as the existing PowerTap cloud API: email local-part,
     * sanitised for Firebase paths.
     */
    val accountId: String
        get() = AuthManager.userEmail
            ?.substringBefore("@")
            ?.replace(Regex("[.#$\\[\\]]"), "_")
            ?.takeIf { it.isNotBlank() }
            ?: "guest"

    private val account: String get() = accountId

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

        val completed = AtomicBoolean(false)
        val mainHandler = Handler(Looper.getMainLooper())

        // Listen for response
        val responseRef = database.getReference("Response/PTD/$deviceId/$account/$timeNow")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                val result = snapshot.value as? Map<String, Any> ?: return
                if (!completed.compareAndSet(false, true)) return
                responseRef.removeEventListener(this)
                mainHandler.removeCallbacksAndMessages(null)
                onResult(result)
            }
            override fun onCancelled(error: DatabaseError) {
                if (!completed.compareAndSet(false, true)) return
                mainHandler.removeCallbacksAndMessages(null)
                onError("Response listener cancelled: ${error.message}")
            }
        }
        responseRef.addValueEventListener(listener)
        mainHandler.postDelayed({
            responseRef.removeEventListener(listener)
            // Do not call onError — a missing Response node is common. Home's
            // command timeout owns the UX so a late timeout cannot lock Stop.
        }, 20_000)

        // Write command directly to avoid double round-trip
        val commandRef = database.getReference("Commands/PTD/$deviceId")
        commandRef.setValue(commandToExecute)
            .addOnFailureListener { e ->
                if (completed.compareAndSet(false, true)) {
                    responseRef.removeEventListener(listener)
                    mainHandler.removeCallbacksAndMessages(null)
                    onError("Firebase write failed: ${e.message}")
                }
            }
    }

    fun startCharging(
        deviceId: String,
        mode: String,
        value: Int?,
        tid: String,
        onResult: (Map<String, Any>) -> Unit,
        onError: (String) -> Unit
    ) {
        val params = mutableMapOf<String, Any>("mode" to mode)
        params["tid"] = tid
        if (value != null) {
            if (mode == "time") params["time"] = value
            else if (mode == "units") params["units"] = value
        }
        // Bind the live session to this login before/while the command runs so
        // a reinstall can reclaim Stop without phone-local lease data.
        claimSessionOwnership(deviceId, tid)
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

    /**
     * Record that [accountId] owns the live charge on [deviceId].
     *
     * Written to the device monitor (so any phone looking at that charger can
     * see who owns it) and to [UserSessions]/account]/active] (so after app
     * data wipe the same login can find its session again).
     */
    fun claimSessionOwnership(deviceId: String, transactionId: String) {
        if (deviceId.isBlank() || transactionId.isBlank()) return
        val acct = account
        if (acct == "guest") return

        val ownerUid = AuthManager.userId.orEmpty()
        database.getReference("PowerTapMonitor/$deviceId").updateChildren(
            mapOf(
                "transactionId" to transactionId,
                "ownerAccount" to acct,
                "ownerUid" to ownerUid,
            ),
        )
        database.getReference("UserSessions/$acct/active").setValue(
            mapOf(
                "deviceId" to deviceId,
                "tid" to transactionId,
                "startedAt" to System.currentTimeMillis(),
                "ownerUid" to ownerUid,
            ),
        )
    }

    /**
     * Clear a leftover cloud session after the charger confirmed idle.
     * Also drops account ownership so the next driver is not blocked.
     */
    fun markSessionIdle(deviceId: String) {
        if (deviceId.isBlank()) return
        database.getReference("PowerTapMonitor/$deviceId").updateChildren(
            mapOf(
                "state" to DeviceState.STATE_AVAILABLE,
                "transactionId" to "",
                "ownerAccount" to "",
                "ownerUid" to "",
            ),
        )
        clearActiveUserSessionIfMatches(deviceId)
    }

    private fun clearActiveUserSessionIfMatches(deviceId: String) {
        val acct = account
        if (acct == "guest") return
        val ref = database.getReference("UserSessions/$acct/active")
        ref.get().addOnSuccessListener { snap ->
            if (!snap.exists()) return@addOnSuccessListener
            val activeDevice = snap.child("deviceId").getValue(String::class.java).orEmpty()
            if (activeDevice.equals(deviceId, ignoreCase = true) || activeDevice.isBlank()) {
                ref.removeValue()
            }
        }
    }
}
