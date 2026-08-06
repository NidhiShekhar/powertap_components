package com.drivool.iot.powertap

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drivool.iot.powertap.ble.BlePrefs
import com.drivool.iot.powertap.contract.ConnectionState
import com.drivool.iot.powertap.contract.DeviceTransport
import com.drivool.iot.powertap.contract.DiscoveredDevice
import kotlinx.coroutines.launch

class BleTestActivity : AppCompatActivity() {

    private lateinit var transport: DeviceTransport

    private lateinit var statusView: TextView
    private lateinit var connectToggleButton: Button
    private lateinit var scanButton: Button
    private lateinit var manualPacketInput: EditText
    private lateinit var appLogAdapter: ArrayAdapter<String>
    private lateinit var bleIncomingAdapter: ArrayAdapter<String>
    private lateinit var bleOutgoingAdapter: ArrayAdapter<String>
    private lateinit var deviceListAdapter: ArrayAdapter<String>
    private lateinit var knownDevicesAdapter: ArrayAdapter<String>
    
    private lateinit var appLogListView: ListView
    private lateinit var bleIncomingListView: ListView
    private lateinit var bleOutgoingListView: ListView
    private lateinit var knownListView: ListView
    private lateinit var deviceListView: ListView
    private lateinit var autoConnectCheck: CheckBox
    
    private var discoveredDevicesList: List<DiscoveredDevice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GatewayManager.init(this)
        transport = GatewayManager.bleTransport
        setContentView(R.layout.activity_ble_test)
        initViews()
        observeTransport()
        checkPermissions()
        transport.startScan() // Start scanning immediately on open
    }

    private fun initViews() {
        statusView = findViewById(R.id.statusView)
        connectToggleButton = findViewById(R.id.connectToggleButton)
        scanButton = findViewById(R.id.scanButton)
        manualPacketInput = findViewById(R.id.manualPacketInput)
        knownListView = findViewById(R.id.knownListView)
        deviceListView = findViewById(R.id.deviceListView)
        autoConnectCheck = findViewById(R.id.autoConnectCheck)
        appLogListView = findViewById(R.id.appLogListView)
        bleIncomingListView = findViewById(R.id.bleIncomingListView)
        bleOutgoingListView = findViewById(R.id.bleOutgoingListView)

        scanButton.setOnClickListener { 
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            if (bluetoothManager.adapter?.isEnabled == true) {
                transport.startScan() 
            } else {
                Toast.makeText(this, "Please turn on Bluetooth", Toast.LENGTH_SHORT).show()
            }
        }
        connectToggleButton.setOnClickListener { transport.disconnect() }
        findViewById<View>(R.id.clearLogsButton).setOnClickListener {
            LogRepository.clear()
            bleIncomingAdapter.clear()
            bleOutgoingAdapter.clear()
        }
        
        manualPacketInput.setText("""[2,"1048605","MeterValues",{"connectorId":"1","transactionId":"T1784549060382","meterValue":{"v":234620,"c":12880,"p":3022759,"e":23079013,"f":50}},"70041dafd038"]""")

        findViewById<View>(R.id.sendManualButton).setOnClickListener {
            val msg = manualPacketInput.text.toString()
            if (!transport.send(msg)) {
                Toast.makeText(this, "Send failed - Check BLE Connection", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.retryNotificationsButton).setOnClickListener {
            Toast.makeText(this, "Try Disconnecting and Reconnecting", Toast.LENGTH_SHORT).show()
        }

        autoConnectCheck.isChecked = BlePrefs.isAutoConnectEnabled(this)
        autoConnectCheck.setOnCheckedChangeListener { _, isChecked ->
            BlePrefs.setAutoConnectEnabled(this, isChecked)
        }

        knownDevicesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        knownListView.adapter = knownDevicesAdapter
        knownListView.setOnItemClickListener { _, _, position, _ ->
            val known = BlePrefs.getKnownDevices(this)
            known.getOrNull(position)?.let { transport.connect(it.second) }
        }

        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        deviceListView.adapter = deviceListAdapter
        deviceListView.setOnItemClickListener { _, _, position, _ ->
            discoveredDevicesList.getOrNull(position)?.let { transport.connect(it.address) }
        }

        appLogAdapter = createLogAdapter()
        appLogListView.adapter = appLogAdapter

        bleOutgoingAdapter = createLogAdapter()
        bleOutgoingListView.adapter = bleOutgoingAdapter

        bleIncomingAdapter = createLogAdapter()
        bleIncomingListView.adapter = bleIncomingAdapter

        updateKnownDevices()
    }

    private fun checkPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }

    private fun updateKnownDevices() {
        val known = BlePrefs.getKnownDevices(this)
        knownDevicesAdapter.clear()
        knownDevicesAdapter.addAll(known.map { "${it.first ?: "Unknown"} (${it.second})" })
    }

    private fun createLogAdapter() = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf()) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = super.getView(position, convertView, parent)
            (v.findViewById<TextView>(android.R.id.text1)).apply {
                textSize = 9f
                isSingleLine = false
                setPadding(5, 5, 5, 5)
            }
            return v
        }
    }

    private fun observeTransport() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    transport.connectionState.collect { state ->
                        statusView.text = "BLE: $state"
                        if (state == ConnectionState.Connected) {
                            connectToggleButton.visibility = View.VISIBLE
                            scanButton.visibility = View.GONE
                            updateKnownDevices()
                        } else {
                            connectToggleButton.visibility = View.GONE
                            scanButton.visibility = View.VISIBLE
                        }
                    }
                }
                launch {
                    transport.discoveredDevices.collect { devices ->
                        discoveredDevicesList = devices
                        deviceListAdapter.clear()
                        deviceListAdapter.addAll(devices.map { "${it.name ?: "Unknown"}\n${it.address} (${it.rssi} dBm)" })
                    }
                }
                launch {
                    transport.incoming.collect { payload ->
                        bleIncomingAdapter.insert(payload, 0)
                    }
                }
                launch {
                    transport.outgoing.collect { payload ->
                        bleOutgoingAdapter.insert(payload, 0)
                    }
                }
                LogRepository.logs.observe(this@BleTestActivity) { logs ->
                    appLogAdapter.clear()
                    appLogAdapter.addAll(logs.take(50))
                }
            }
        }
    }
}
