package com.drivool.iot.powertap.session

import org.json.JSONObject

/**
 * Where this phone believes its own session is in its lifecycle.
 *
 * [Requested] is the window between sending RemoteStart and the charger
 * confirming it. Hardware evidence recorded *before* a lease was requested is
 * stale and must not be used to close it — see [SessionReconciler].
 */
enum class LeaseState {
    Requested,
    Active,
    Stopping,
    Released,
}

/**
 * A claim by this phone on one charging session.
 *
 * This replaces the in-memory `transactionId` that Home used to keep, which was
 * lost on process death and overwritten by every Firebase snapshot. The lease is
 * the single answer to two questions:
 *
 *  - "Am I allowed to reconnect over BLE without the user asking?" (only to
 *    resume a session I own — otherwise we occupy a charger nobody is using)
 *  - "Which transaction id does Stop target?" (never the cloud's copy, which
 *    goes stale and leaves sessions dangling)
 *
 * Ownership is the Firebase account that started the charge. The local lease
 * caches that claim for BLE resume; if app data is wiped, matching
 * cloud ownerAccount to the signed-in account restores the lease. Another
 * account that merely sees the charger busy must not adopt it.
 */
data class SessionLease(
    val transactionId: String,
    val deviceId: String,
    val bleAddress: String,
    val mode: String,
    val startedAt: Long,
    val state: LeaseState,
    /** Last time hardware positively confirmed this transaction id was alive. */
    val lastConfirmedAt: Long,
    /**
     * True when the server ack renamed the tid we proposed at Start.
     * Not used for stranger take-over — that path no longer exists.
     */
    val adopted: Boolean = false,
) {
    /** Open leases authorise BLE resume and give Stop a target. */
    val isOpen: Boolean get() = state != LeaseState.Released

    fun ageMs(nowMs: Long): Long = nowMs - startedAt

    fun toJson(): String = JSONObject().apply {
        put(KEY_TID, transactionId)
        put(KEY_DEVICE_ID, deviceId)
        put(KEY_BLE, bleAddress)
        put(KEY_MODE, mode)
        put(KEY_STARTED_AT, startedAt)
        put(KEY_STATE, state.name)
        put(KEY_CONFIRMED_AT, lastConfirmedAt)
        put(KEY_ADOPTED, adopted)
    }.toString()

    companion object {
        private const val KEY_TID = "tid"
        private const val KEY_DEVICE_ID = "did"
        private const val KEY_BLE = "ble"
        private const val KEY_MODE = "mode"
        private const val KEY_STARTED_AT = "st"
        private const val KEY_STATE = "state"
        private const val KEY_CONFIRMED_AT = "ct"
        private const val KEY_ADOPTED = "adopted"

        /** Returns null for absent or corrupt data rather than throwing into UI code. */
        fun fromJson(json: String?): SessionLease? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                val tid = obj.optString(KEY_TID).takeIf { it.isNotBlank() } ?: return null
                SessionLease(
                    transactionId = tid,
                    deviceId = obj.optString(KEY_DEVICE_ID),
                    bleAddress = obj.optString(KEY_BLE),
                    mode = obj.optString(KEY_MODE, "full"),
                    startedAt = obj.optLong(KEY_STARTED_AT),
                    state = runCatching { LeaseState.valueOf(obj.optString(KEY_STATE)) }
                        .getOrDefault(LeaseState.Active),
                    lastConfirmedAt = obj.optLong(KEY_CONFIRMED_AT),
                    adopted = obj.optBoolean(KEY_ADOPTED, false),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
