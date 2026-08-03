package com.drivool.iot.powertap.contract

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * DeviceTransport - the small interface that decouples "talk to the device" from
 * "how" (BLE today; could be USB, classic BT, etc. tomorrow).
 */
interface DeviceTransport {
    /** Current connection lifecycle state. */
    val connectionState: StateFlow<ConnectionState>

    /** Address of the currently connected (or connecting) device. */
    val connectedAddress: StateFlow<String?>

    /** Devices seen in the current/last scan (deduped by address). */
    val discoveredDevices: StateFlow<List<DiscoveredDevice>>

    /** Payloads received from the device (e.g. ESP notifications). */
    val incoming: SharedFlow<String>

    /** Payloads sent to the device. */
    val outgoing: SharedFlow<String>

    fun startScan()
    fun stopScan()
    fun connect(address: String)
    fun disconnect()

    /** Send a payload to the device. Returns true if it was queued. */
    fun send(payload: String): Boolean
}
