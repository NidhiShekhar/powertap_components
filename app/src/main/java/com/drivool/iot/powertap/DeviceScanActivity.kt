package com.drivool.iot.powertap

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drivool.iot.powertap.contract.DiscoveredDevice
import kotlinx.coroutines.launch

class DeviceScanActivity : AppCompatActivity() {

    private lateinit var deviceList: RecyclerView
    private lateinit var btnScan: Button
    private lateinit var btnScanQr: Button
    private lateinit var scanProgress: ProgressBar
    private var devices: List<DiscoveredDevice> = emptyList()

    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val name = result.data?.getStringExtra(QrScanActivity.EXTRA_DISPLAY_NAME)
                ?: "PowerTap"
            Toast.makeText(this, "Connecting to $name", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_scan)

        btnScan = findViewById(R.id.btnScan)
        btnScanQr = findViewById(R.id.btnScanQr)
        deviceList = findViewById(R.id.deviceList)
        scanProgress = findViewById(R.id.scanProgress)

        deviceList.layoutManager = LinearLayoutManager(this)
        val adapter = DeviceAdapter { device ->
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val btAdapter = bluetoothManager.adapter

            if (btAdapter == null || !btAdapter.isEnabled) {
                Toast.makeText(this, "Please enable Bluetooth to connect", Toast.LENGTH_SHORT).show()
                return@DeviceAdapter
            }
            
            val isBonded = try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    btAdapter?.bondedDevices?.any { it.address == device.address } == true
                } else {
                    false
                }
            } catch (e: SecurityException) {
                false
            }
            
            if (isBonded) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Device Already Paired")
                    .setMessage("This device is already paired with your phone's system settings. Please go to Bluetooth Settings, 'Forget' or 'Unpair' this device, then try connecting through the app.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                val deviceId = DeviceIdentity.deviceIdFromBle(device.address)
                GatewayManager.connectToBle(
                    bleAddress = device.address,
                    deviceId = deviceId,
                    displayName = device.displayName,
                    scanFirst = false,
                )
                Toast.makeText(this, "Pairing with ${device.displayName}…", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        deviceList.adapter = adapter

        fun startDiscovery() {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            
            if (adapter == null || !adapter.isEnabled) {
                Toast.makeText(this, "Please turn on Bluetooth to scan for devices", Toast.LENGTH_LONG).show()
                return
            }

            if (checkPermissions()) {
                GatewayManager.bleTransport.startScan()
                scanProgress.visibility = View.VISIBLE
                btnScan.text = "Scanning..."
                btnScan.isEnabled = false
                
                // Stop scanning after 10 seconds
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(10000)
                    GatewayManager.bleTransport.stopScan()
                    scanProgress.visibility = View.GONE
                    btnScan.text = "Start Scanning"
                    btnScan.isEnabled = true
                }
            }
        }

        btnScan.setOnClickListener {
            startDiscovery()
        }

        btnScanQr.setOnClickListener {
            if (!checkPermissions()) {
                Toast.makeText(this, "Bluetooth permission is required to connect", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            if (adapter == null || !adapter.isEnabled) {
                Toast.makeText(this, "Please turn on Bluetooth to connect", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            GatewayManager.bleTransport.stopScan()
            scanProgress.visibility = View.GONE
            btnScan.text = "Start Scanning"
            btnScan.isEnabled = true
            qrScanLauncher.launch(Intent(this, QrScanActivity::class.java))
        }

        // Auto-start scan on open
        startDiscovery()

        lifecycleScope.launch {
            GatewayManager.bleTransport.discoveredDevices.collect { discovered ->
                devices = discovered
                adapter.submitList(discovered)
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
            return false
        }
        return true
    }

    class DeviceAdapter(private val onClick: (DiscoveredDevice) -> Unit) : 
        RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {
        
        private var items: List<DiscoveredDevice> = emptyList()

        fun submitList(list: List<DiscoveredDevice>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val deviceId = DeviceIdentity.deviceIdFromBle(item.address)
            val title = when {
                !item.name.isNullOrBlank() && item.name.startsWith("PowerTap_", ignoreCase = true) -> item.name
                deviceId != null -> "PowerTap_$deviceId"
                !item.name.isNullOrBlank() -> item.name
                else -> "PowerTap"
            }
            holder.name.text = title
            holder.address.text = if (deviceId != null) {
                "${item.address}  ·  ID $deviceId"
            } else {
                item.address
            }
            holder.rssi.text = "${item.rssi} dBm"
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.txtName)
            val address: TextView = view.findViewById(R.id.txtAddress)
            val rssi: TextView = view.findViewById(R.id.txtRssi)
        }
    }
}
