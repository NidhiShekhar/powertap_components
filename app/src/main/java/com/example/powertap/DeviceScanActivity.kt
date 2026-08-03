package com.drivool.iot.powertap

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.drivool.iot.powertap.contract.DiscoveredDevice
import kotlinx.coroutines.launch

class DeviceScanActivity : AppCompatActivity() {

    private lateinit var adapter: ArrayAdapter<String>
    private var devices: List<DiscoveredDevice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_scan)

        val btnScan: Button = findViewById(R.id.btnScan)
        val listView: ListView = findViewById(R.id.deviceList)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = adapter

        btnScan.setOnClickListener {
            if (checkPermissions()) {
                GatewayManager.bleTransport.startScan()
            }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val device = devices[position]
            GatewayManager.bleTransport.connect(device.address)
            Toast.makeText(this, "Connecting to ${device.displayName}", Toast.LENGTH_SHORT).show()
            finish()
        }

        observeDevices()
    }

    private fun observeDevices() {
        lifecycleScope.launch {
            GatewayManager.bleTransport.discoveredDevices.collect { discovered ->
                devices = discovered
                adapter.clear()
                adapter.addAll(discovered.map { "${it.displayName}\n${it.address}" })
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
}
