package com.drivool.iot.powertap

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drivool.iot.powertap.contract.ConnectionState
import com.drivool.iot.powertap.contract.DeviceTransport
import com.drivool.iot.powertap.contract.DiscoveredDevice
import kotlinx.coroutines.launch

class BleTestActivity : AppCompatActivity() {

    private lateinit var transport: DeviceTransport

    private lateinit var statusView: TextView
    private lateinit var connectToggleButton: Button
    private lateinit var scanButton: Button
    private lateinit var appLogAdapter: ArrayAdapter<String>
    private lateinit var bleIncomingAdapter: ArrayAdapter<String>
    private lateinit var bleOutgoingAdapter: ArrayAdapter<String>
    private lateinit var deviceListAdapter: ArrayAdapter<String>
    
    private lateinit var manualPacketInput: EditText
    private var discoveredDevicesList: List<DiscoveredDevice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GatewayManager.init(this)
        transport = GatewayManager.bleTransport
        setContentView(buildUi())
        observeTransport()
        checkPermissions()
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

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Top Header
        val header = FrameLayout(this).apply {
            setPadding(30, 20, 30, 20)
            setBackgroundColor(Color.LTGRAY)
        }
        statusView = TextView(this).apply {
            text = "BLE: Disconnected"
            setTextColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                -2, -2, Gravity.START or Gravity.CENTER_VERTICAL
            )
        }
        
        val headerButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.END)
        }
        
        scanButton = Button(this).apply {
            text = "SCAN"
            setOnClickListener { transport.startScan() }
        }
        
        connectToggleButton = Button(this).apply {
            text = "DISCONNECT"
            visibility = View.GONE
            setBackgroundColor(Color.RED)
            setTextColor(Color.WHITE)
            setOnClickListener { transport.disconnect() }
        }
        
        headerButtons.addView(scanButton)
        headerButtons.addView(connectToggleButton)
        header.addView(statusView)
        header.addView(headerButtons)
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f
            )
        }
        
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        scroll.addView(content)

        val wrap = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8; bottomMargin = 8 }

        // Device List Section
        content.addView(TextView(this).apply { text = "Discovered Devices (Click to connect)"; textSize = 14f; setTextColor(Color.GRAY) })
        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        val deviceListView = ListView(this).apply {
            adapter = deviceListAdapter
            layoutParams = LinearLayout.LayoutParams(-1, 300)
            setOnItemClickListener { _, _, position, _ ->
                discoveredDevicesList.getOrNull(position)?.let { transport.connect(it.address) }
            }
        }
        content.addView(deviceListView)

        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })

        content.addView(TextView(this).apply { 
            text = "SEND PACKET TO ESP (BLE)"; 
            textSize = 16f; 
            setTextColor(Color.BLUE) 
        })
        manualPacketInput = EditText(this).apply {
            hint = "Enter JSON packet here..."
            setText("""[2,"1048605","MeterValues",{"connectorId":"1","transactionId":"T1784549060382","meterValue":{"v":234620,"c":12880,"p":3022759,"e":23079013,"f":50}},"70041dafd038"]""")
            layoutParams = wrap
            minLines = 4
            gravity = Gravity.TOP
            setBackgroundColor(Color.parseColor("#F0F0F0"))
        }
        val sendManualButton = Button(this).apply {
            text = "SEND TO ESP"
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            layoutParams = wrap
            setOnClickListener {
                val msg = manualPacketInput.text.toString()
                if (!transport.send(msg)) {
                    Toast.makeText(this@BleTestActivity, "Send failed - Check BLE Connection", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val retryNotificationsButton = Button(this).apply {
            text = "RE-ENABLE NOTIFICATIONS (HANDSHAKE)"
            setBackgroundColor(Color.parseColor("#FF9800")) // Orange
            setTextColor(Color.WHITE)
            layoutParams = wrap
            setOnClickListener {
                // We'll call connect again or just trigger the handshake if possible.
                // Since transport interface doesn't have "enableNotifications", 
                // we'll just log that they should try reconnecting if it fails.
                Toast.makeText(this@BleTestActivity, "Try Disconnecting and Reconnecting", Toast.LENGTH_SHORT).show()
            }
        }

        content.addView(manualPacketInput)
        content.addView(sendManualButton)
        content.addView(retryNotificationsButton)

        // Logs Control Bar
        val logsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 10, 20, 10)
            setBackgroundColor(Color.DKGRAY)
        }
        logsHeader.addView(TextView(this).apply { 
            text = "LOGS"; setTextColor(Color.WHITE); 
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { gravity = Gravity.CENTER_VERTICAL }
        })
        val clearLogsButton = Button(this).apply {
            text = "CLEAR ALL"
            textSize = 10f
            setOnClickListener {
                LogRepository.clear()
                bleIncomingAdapter.clear()
                bleOutgoingAdapter.clear()
            }
        }
        logsHeader.addView(clearLogsButton)
        root.addView(logsHeader)

        // Bottom Logs Section
        val listsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, 0, 0.7f)
        }

        val appLogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        appLogLayout.addView(TextView(this).apply { text = "App Logs"; gravity = Gravity.CENTER; textSize = 10f })
        appLogAdapter = createLogAdapter()
        appLogLayout.addView(ListView(this).apply { adapter = appLogAdapter })

        val outLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        outLayout.addView(TextView(this).apply { text = "Outgoing (BLE)"; gravity = Gravity.CENTER; textSize = 10f; setTextColor(Color.parseColor("#388E3C")) })
        bleOutgoingAdapter = createLogAdapter()
        outLayout.addView(ListView(this).apply { adapter = bleOutgoingAdapter })

        val inLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        inLayout.addView(TextView(this).apply { text = "Incoming (BLE)"; gravity = Gravity.CENTER; textSize = 10f; setTextColor(Color.BLUE) })
        bleIncomingAdapter = createLogAdapter()
        inLayout.addView(ListView(this).apply { adapter = bleIncomingAdapter })

        listsLayout.addView(appLogLayout)
        listsLayout.addView(outLayout)
        listsLayout.addView(inLayout)

        root.addView(scroll)
        root.addView(listsLayout)
        return root
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
