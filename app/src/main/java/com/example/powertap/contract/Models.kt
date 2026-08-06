package com.drivool.iot.powertap.contract

/**
 * Connection lifecycle states shared by every transport (BLE now, MQTT later).
 * The UI observes this to show a single, consistent status indicator.
 */
enum class ConnectionState {
    Disconnected,
    Scanning,
    Connecting,
    Connected,
    Failed,
}

/** A device found during a BLE scan. */
data class DiscoveredDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
) {
    val displayName: String get() = name ?: "(unknown)"
}

/** Data extracted from MeterValues packets. */
data class MeterData(
    val voltage: Float,     // v
    val current: Float,     // c
    val power: Float,       // p
    val energy: Float,      // e
    val frequency: Float,   // f
    val timestamp: Long = System.currentTimeMillis()
)

/** Represents a single charging session. */
data class ChargingSession(
    val transactionId: String,
    val deviceId: String,
    val startTime: Long,
    var stopTime: Long? = null,
    val meterStart: Float,
    var meterStop: Float? = null,
    var mode: String? = "full",
    var status: String = "Active"
) {
    val energyConsumed: Float
        get() = (meterStop ?: meterStart) - meterStart
}
