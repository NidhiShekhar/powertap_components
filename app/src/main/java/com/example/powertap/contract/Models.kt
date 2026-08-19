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

/** Data extracted from MeterValues packets. Scaled from firmware milli-units. */
data class MeterData(
    val voltage: Float,     // V  (raw / 1000)
    val current: Float,     // A  (raw / 1000)
    val power: Float,       // W  (raw / 1000) — display as kW at 1000+
    val energy: Float,      // Wh (raw / 1000) — display as kWh at 1000+
    val frequency: Float,   // Hz
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
