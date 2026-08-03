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
import android.os.ParcelUuid
import androidx.core.app.ActivityCompat
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
 */
class BleTransport(
    private val context: Context,
    private val log: (String) -> Unit = {},
) : DeviceTransport {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

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

    private fun has(permission: String): Boolean =
        ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) has(Manifest.permission.BLUETOOTH_SCAN)
        else has(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) has(Manifest.permission.BLUETOOTH_CONNECT)
        else true

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = if (hasConnectPermission()) device.name ?: result.scanRecord?.deviceName else null
            val entry = DiscoveredDevice(name, device.address, result.rssi)
            val current = _discoveredDevices.value
            val idx = current.indexOfFirst { it.address == entry.address }
            _discoveredDevices.value =
                if (idx == -1) current + entry
                else current.toMutableList().also { it[idx] = entry }
        }

        override fun onScanFailed(errorCode: Int) {
            log("BLE scan failed (code=$errorCode)")
            scanning = false
            _connectionState.value = ConnectionState.Failed
        }
    }

    override fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            log("Enable Bluetooth before scanning")
            return
        }
        if (!hasScanPermission()) {
            log("Missing scan permission")
            return
        }
        _discoveredDevices.value = emptyList()

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(PtContract.SERVICE_UUID)).build(),
            ScanFilter.Builder().setDeviceName(PtContract.DEVICE_NAME).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
            _connectionState.value = ConnectionState.Scanning
            log("Scanning for ${PtContract.DEVICE_NAME}...")
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
    }

    override fun connect(address: String) {
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
        stopScan()
        cleanup()
        _connectionState.value = ConnectionState.Connecting
        _connectedAddress.value = address
        log("Connecting to $address...")
        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: SecurityException) {
            log("Connect blocked: ${e.message}")
        }
    }

    override fun disconnect() {
        try {
            if (hasConnectPermission()) gatt?.disconnect()
        } catch (e: SecurityException) { }
        cleanup()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun cleanup() {
        dataChar = null
        _connectedAddress.value = null
        try {
            if (hasConnectPermission()) gatt?.close()
        } catch (e: SecurityException) { }
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    try {
                        if (hasConnectPermission()) g.requestMtu(PtContract.MTU)
                    } catch (e: SecurityException) { }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("BLE Disconnected (status: $status)")
                    cleanup()
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            try {
                if (hasConnectPermission()) g.discoverServices()
            } catch (e: SecurityException) { }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ConnectionState.Failed
                return
            }

            val service = g.getService(PtContract.SERVICE_UUID)
            val characteristic = service?.getCharacteristic(PtContract.DATA_CHAR_UUID)
            
            if (characteristic == null) {
                _connectionState.value = ConnectionState.Failed
                return
            }

            dataChar = characteristic
            enableNotifications(g, characteristic)

            _connectionState.value = ConnectionState.Connected
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val text = String(c.value ?: ByteArray(0), Charsets.UTF_8)
            _incoming.tryEmit(text)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val text = String(value, Charsets.UTF_8)
            _incoming.tryEmit(text)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { }
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
        if (g == null) {
            log("BLE Send failed: GATT is null")
            return false
        }
        if (c == null) {
            log("BLE Send failed: Characteristic is null")
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
