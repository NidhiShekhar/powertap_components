package com.drivool.iot.powertap.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import com.drivool.iot.powertap.ChargeSessionLogic
import com.drivool.iot.powertap.contract.ConnectionState
import com.drivool.iot.powertap.contract.DeviceTransport
import com.drivool.iot.powertap.contract.DiscoveredDevice
import com.drivool.iot.powertap.contract.PtContract
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BleTransport - a reusable BLE central that implements [DeviceTransport].
 *
 * Android delivers GATT callbacks on a binder thread, and a closed GATT can still
 * fire Connected/Disconnected after we have started a newer connection. Every
 * callback is therefore ignored unless it belongs to the current [gatt], and
 * UI-facing StateFlows are always posted on the main thread.
 */
class BleTransport(
    private val context: Context,
    private val log: (String) -> Unit = {},
) : DeviceTransport {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedAddress = MutableStateFlow<String?>(null)
    override val connectedAddress: StateFlow<String?> = _connectedAddress.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 32)
    override val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    private val _outgoing = MutableSharedFlow<String>(extraBufferCapacity = 32)
    override val outgoing: SharedFlow<String> = _outgoing.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var targetAddress: String? = null
    private var connectGeneration = 0
    private var waitingForCccd = false
    private var scanGeneration = 0

    private fun has(permission: String): Boolean =
        ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) has(Manifest.permission.BLUETOOTH_SCAN)
        else has(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) has(Manifest.permission.BLUETOOTH_CONNECT)
        else true

    private fun setState(state: ConnectionState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _connectionState.value = state
        } else {
            mainHandler.post { _connectionState.value = state }
        }
    }

    private fun setConnectedAddress(address: String?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _connectedAddress.value = address
        } else {
            mainHandler.post { _connectedAddress.value = address }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val rawAddress = device.address?.uppercase() ?: return
            val advertisedName = if (hasConnectPermission()) {
                device.name ?: result.scanRecord?.deviceName
            } else {
                result.scanRecord?.deviceName
            }
            val now = SystemClock.elapsedRealtime()
            val entry = DiscoveredDevice(advertisedName, rawAddress, result.rssi, now)
            val current = _discoveredDevices.value
            val idx = current.indexOfFirst {
                it.address.equals(rawAddress, ignoreCase = true)
            }
            _discoveredDevices.value = if (idx == -1) {
                current + entry
            } else {
                val previous = current[idx]
                val bestName = when {
                    scoreName(entry.name) >= scoreName(previous.name) -> entry.name ?: previous.name
                    else -> previous.name ?: entry.name
                }
                current.toMutableList().also {
                    it[idx] = previous.copy(
                        name = bestName,
                        rssi = entry.rssi,
                        address = rawAddress,
                        lastSeenElapsedMs = now,
                    )
                }
            }

            val wanted = targetAddress
            if (wanted != null && rawAddress.equals(wanted, ignoreCase = true)) {
                // Clear the target first so the next advertisement cannot start a
                // second connectGatt on top of this one.
                targetAddress = null
                log("Auto-connecting to target: $rawAddress")
                connect(rawAddress)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("BLE scan failed (code=$errorCode)")
            scanning = false
            setState(ConnectionState.Failed)
        }
    }

    private fun scoreName(name: String?): Int = when {
        name.isNullOrBlank() -> 0
        name.startsWith("PowerTap_", ignoreCase = true) -> 3
        else -> 2
    }

    override fun startScan(targetAddress: String?) {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            log("Enable Bluetooth before scanning")
            return
        }
        if (!hasScanPermission()) {
            log("Missing scan permission")
            return
        }
        if (targetAddress != null &&
            ChargeSessionLogic.shouldSkipDuplicateConnect(
                _connectionState.value,
                _connectedAddress.value,
                targetAddress,
            )
        ) {
            log("Scan skipped — already connected/connecting to $targetAddress")
            return
        }
        // Direct connectGatt without a *fresh* advertisement is why reconnect
        // after a mid-charge drop used to report Connected on a dead handle.
        if (targetAddress != null) {
            val now = SystemClock.elapsedRealtime()
            val cached = _discoveredDevices.value.firstOrNull {
                it.address.equals(targetAddress, ignoreCase = true)
            }
            if (cached != null &&
                ChargeSessionLogic.isScanResultFresh(cached.lastSeenElapsedMs, now)
            ) {
                log("Target $targetAddress advertised ${now - cached.lastSeenElapsedMs}ms ago — connecting now")
                connect(targetAddress)
                return
            }
        }
        this.targetAddress = targetAddress
        val thisScan = ++scanGeneration

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(PtContract.SERVICE_UUID)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
            setState(ConnectionState.Scanning)
            log("Scanning for ${PtContract.DEVICE_NAME}...")
            mainHandler.postDelayed({
                if (thisScan != scanGeneration) return@postDelayed
                if (scanning && _connectionState.value == ConnectionState.Scanning) {
                    log("Scan timed out for ${targetAddress ?: "any"}")
                    stopScan()
                    setState(ConnectionState.Failed)
                }
            }, 20_000)
        } catch (e: SecurityException) {
            log("Scan blocked: ${e.message}")
        }
    }

    override fun stopScan() {
        if (!scanning) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(scanCallback)
        } catch (e: SecurityException) { }
        scanning = false
        targetAddress = null
        scanGeneration++
    }

    override fun connect(address: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { connect(address) }
            return
        }
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (device == null) {
            log("Invalid device address: $address")
            return
        }
        if (!hasConnectPermission()) {
            log("Missing BLUETOOTH_CONNECT")
            return
        }
        if (ChargeSessionLogic.shouldSkipDuplicateConnect(
                _connectionState.value,
                _connectedAddress.value,
                address,
            ) && gatt != null && dataChar != null
        ) {
            log("Already connected to $address")
            return
        }
        if (_connectionState.value == ConnectionState.Connecting &&
            _connectedAddress.value.equals(address, ignoreCase = true)
        ) {
            log("Connect already in progress for $address")
            return
        }

        stopScan()
        val generation = ++connectGeneration
        val previous = gatt
        setState(ConnectionState.Connecting)
        setConnectedAddress(address)
        log("Connecting to $address...")
        mainHandler.postDelayed({
            if (generation != connectGeneration) return@postDelayed
            if (_connectionState.value == ConnectionState.Connecting) {
                log("Connect timed out for $address")
                val g = gatt
                gatt = null
                dataChar = null
                closeGatt(g)
                setConnectedAddress(null)
                setState(ConnectionState.Failed)
            }
        }, 15_000)

        if (previous != null) {
            try {
                if (hasConnectPermission()) previous.disconnect()
            } catch (e: SecurityException) { }
            // Closing immediately races the old GATT's DISCONNECTED callback
            // against the new connectGatt and leaves a zombie "Connected" UI.
            mainHandler.postDelayed({
                if (generation != connectGeneration) return@postDelayed
                closeGatt(previous)
                if (gatt === previous) gatt = null
                openGatt(device, address, generation)
            }, 400)
        } else {
            openGatt(device, address, generation)
        }
    }

    private fun openGatt(device: BluetoothDevice, address: String, generation: Int) {
        if (generation != connectGeneration) return
        dataChar = null
        waitingForCccd = false
        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
            log("GATT connect issued for $address")
        } catch (e: SecurityException) {
            log("Connect blocked: ${e.message}")
            setState(ConnectionState.Failed)
        }
    }

    override fun disconnect() {
        connectGeneration++
        stopScan()
        waitingForCccd = false
        val g = gatt
        try {
            if (hasConnectPermission()) g?.disconnect()
        } catch (e: SecurityException) { }
        mainHandler.postDelayed({
            closeGatt(g)
            if (gatt === g) {
                gatt = null
                dataChar = null
            }
        }, 300)
        setConnectedAddress(null)
        setState(ConnectionState.Disconnected)
    }

    private fun closeGatt(g: BluetoothGatt?) {
        if (g == null) return
        try {
            if (hasConnectPermission()) g.close()
        } catch (e: SecurityException) { }
    }

    private fun cleanupCurrent(from: BluetoothGatt?) {
        if (from != null && from !== gatt) {
            closeGatt(from)
            return
        }
        dataChar = null
        waitingForCccd = false
        val g = gatt
        gatt = null
        setConnectedAddress(null)
        closeGatt(g)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) {
                log("Ignoring stale GATT connection callback (status=$status state=$newState)")
                closeGatt(g)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    try {
                        if (hasConnectPermission()) g.requestMtu(PtContract.MTU)
                    } catch (e: SecurityException) { }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("BLE Disconnected (status: $status)")
                    cleanupCurrent(g)
                    setState(ConnectionState.Disconnected)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g !== gatt) return
            try {
                if (hasConnectPermission()) g.discoverServices()
            } catch (e: SecurityException) { }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setState(ConnectionState.Failed)
                return
            }

            val service = g.getService(PtContract.SERVICE_UUID)
            val characteristic = service?.getCharacteristic(PtContract.DATA_CHAR_UUID)

            if (characteristic == null) {
                setState(ConnectionState.Failed)
                return
            }

            dataChar = characteristic
            val canNotify = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            if (canNotify) {
                waitingForCccd = true
                enableNotifications(g, characteristic)
                mainHandler.postDelayed({
                    if (g !== gatt) return@postDelayed
                    if (waitingForCccd && _connectionState.value == ConnectionState.Connecting) {
                        waitingForCccd = false
                        log("CCCD write timed out — marking connected anyway")
                        setState(ConnectionState.Connected)
                    }
                }, 2_000)
            } else {
                setState(ConnectionState.Connected)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (g !== gatt) return
            val text = String(c.value ?: ByteArray(0), Charsets.UTF_8)
            _incoming.tryEmit(text)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (gatt !== this@BleTransport.gatt) return
            val text = String(value, Charsets.UTF_8)
            _incoming.tryEmit(text)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt) return
            waitingForCccd = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("CCCD write failed (status=$status) — still marking connected")
            }
            setState(ConnectionState.Connected)
        }
    }

    private fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        if (!hasConnectPermission()) return

        val canNotify = (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        if (!canNotify) return

        try {
            val success = g.setCharacteristicNotification(c, true)
            if (!success) return

            val cccd = c.getDescriptor(PtContract.CCCD_UUID) ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }
        } catch (e: SecurityException) { }
    }

    override fun send(payload: String): Boolean {
        val g = gatt
        val c = dataChar
        if (g == null || c == null || _connectionState.value != ConnectionState.Connected) {
            log("BLE Send failed: not ready (state=${_connectionState.value})")
            return false
        }
        if (!hasConnectPermission()) {
            log("BLE Send failed: Missing CONNECT permission")
            return false
        }
        val bytes = payload.toByteArray(Charsets.UTF_8)
        log("BLE Sending (${bytes.size} bytes): $payload")

        return try {
            val writeType = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            log("BLE WriteType: ${if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) "NO_RESPONSE" else "DEFAULT"}")

            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, bytes, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    c.writeType = writeType
                    c.value = bytes
                    g.writeCharacteristic(c)
                }
            }
            if (ok) {
                _outgoing.tryEmit(payload)
            } else {
                log("BLE Send failed: writeCharacteristic returned false/error")
            }
            ok
        } catch (e: SecurityException) {
            log("BLE Send failed: SecurityException ${e.message}")
            false
        } catch (e: Exception) {
            log("BLE Send failed: Exception ${e.message}")
            false
        }
    }
}
