package com.drivool.iot.powertap.session

import org.json.JSONArray

/**
 * What the charger itself last told us about its relay and transaction id.
 *
 * The firmware gives us a free oracle here: it sends MeterValues *only* while the
 * relay is on and Heartbeat *only* while it is off (esp/mqtt.cpp, the
 * `gFlags.Relay` branch in the periodic task). So the packet type alone
 * distinguishes charging from idle, and MeterValues carries the live
 * transactionId.
 *
 * Corollary worth remembering: any transaction id on a Heartbeat is the *last*
 * session's, not a running one, because a heartbeat proves the relay is open. It
 * must never be read as a live session or we would adopt a finished one.
 */
sealed interface HardwareSession {
    /**
     * Relay is on. [transactionId] is blank on firmware that does not report it,
     * which means "charging, identity unknown" — enough to hold a lease, not
     * enough to adopt or supersede one.
     */
    data class Charging(val transactionId: String, val observedAt: Long) : HardwareSession

    /** Relay is off. This is the only positive proof a session has ended. */
    data class Idle(val observedAt: Long) : HardwareSession

    /** No packet recently. Absence of evidence — never treat this as "ended". */
    data object Unknown : HardwareSession

    val observedAtOrZero: Long
        get() = when (this) {
            is Charging -> observedAt
            is Idle -> observedAt
            Unknown -> 0L
        }

    companion object {
        /** Charger considered unreachable after this long without a packet. */
        const val STALE_AFTER_MS = 45_000L

        /**
         * Derive a hardware view from one OCPP frame, or null if the frame says
         * nothing about the relay. Works for frames arriving over BLE or MQTT.
         */
        fun fromOcppPacket(payload: String, observedAt: Long): HardwareSession? {
            val arr = try {
                JSONArray(payload.trim())
            } catch (_: Exception) {
                return null
            }
            if (arr.length() < 4) return null
            val data = arr.optJSONObject(3) ?: return null

            return fromAction(
                action = arr.optString(2),
                transactionId = data.optString("transactionId").trim(),
                status = data.optString("status"),
                observedAt = observedAt,
            )
        }

        /**
         * The packet-type to relay-state mapping, kept free of JSON so it can be
         * exercised directly. [transactionId] and [status] may be blank when the
         * frame does not carry them.
         */
        fun fromAction(
            action: String,
            transactionId: String,
            status: String,
            observedAt: Long,
        ): HardwareSession? = when (action) {
            "MeterValues" -> Charging(transactionId, observedAt)

            "StartTransaction" -> Charging(transactionId, observedAt)

            "StopTransaction" -> Idle(observedAt)

            // A heartbeat means the relay is open, so it is proof of idle.
            // Any tid it carries is the finished session's — ignore it.
            "Heartbeat", "HeartBeat" -> Idle(observedAt)

            "StatusNotification" ->
                if (status.equals("Available", ignoreCase = true) ||
                    status.equals("Finishing", ignoreCase = true)
                ) {
                    Idle(observedAt)
                } else {
                    null
                }

            else -> null
        }
    }
}
