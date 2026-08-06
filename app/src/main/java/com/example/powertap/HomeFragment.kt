package com.drivool.iot.powertap

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.drivool.iot.powertap.ble.BlePrefs
import com.drivool.iot.powertap.mqtt.MqttPrefs
import com.google.android.material.tabs.TabLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment() {

    private val TAG = "HomeFragment"
    private var time = 60
    private var units = 10
    private var deviceId: String = ""
    private var currentState: Int = DeviceState.STATE_AVAILABLE
    private var transactionId: String? = null
    private var statusListener: ValueEventListener? = null
    private var deviceRef: com.google.firebase.database.DatabaseReference? = null
    private var lastFirebaseHeartbeat = 0L
    private var serverTimeOffset = 0L
    private var commandStartTime = 0L
    private val COMMAND_TIMEOUT = 15000L // 15 seconds

    private var lcdView: TwoLineLCDView? = null
    private var sliderButton: SliderButtonView? = null
    private var tabLayout: TabLayout? = null
    private var txtTitle: TextView? = null
    private var txtSubtitle: TextView? = null
    private var txtIcon: TextView? = null
    private var txtValue: TextView? = null
    private var txtInfo: TextView? = null
    private var sliderSection: View? = null
    private var seekBar: SeekBar? = null
    private var btnMinus: Button? = null
    private var btnPlus: Button? = null
    private var deviceSelector: AutoCompleteTextView? = null
    private var mainContent: View? = null
    private var emptyState: View? = null
    private var btnSetupFirstDevice: Button? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        lcdView = view.findViewById(R.id.lcd_view)
        sliderButton = view.findViewById(R.id.slider_button)
        tabLayout = view.findViewById(R.id.tabLayout)
        txtTitle = view.findViewById(R.id.txtTitle)
        txtSubtitle = view.findViewById(R.id.txtSubtitle)
        txtIcon = view.findViewById(R.id.txtIcon)
        txtValue = view.findViewById(R.id.txtValue)
        txtInfo = view.findViewById(R.id.txtInfo)
        sliderSection = view.findViewById(R.id.sliderSection)
        seekBar = view.findViewById(R.id.seekBar)
        btnMinus = view.findViewById(R.id.btnMinus)
        btnPlus = view.findViewById(R.id.btnPlus)
        deviceSelector = view.findViewById(R.id.deviceSelector)
        mainContent = view.findViewById(R.id.mainContent)
        emptyState = view.findViewById(R.id.emptyState)
        btnSetupFirstDevice = view.findViewById(R.id.btnSetupFirstDevice)

        setupDeviceSelector()

        btnSetupFirstDevice?.setOnClickListener {
            startActivity(android.content.Intent(context, DeviceScanActivity::class.java))
        }

        // Initialize LCD
        lcdView?.setText(
            listOf(LCDSegment("0V", 28f, Align.LEFT, 1f, true), LCDSegment("0Wh", 28f, Align.RIGHT, 1f, true)),
            listOf(LCDSegment("9APR 12:04AM", 24f, Align.CENTER, 1f, true))
        )

        // Initialize Tabs
        tabLayout?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        txtTitle?.text = "FULL CHARGE"
                        txtSubtitle?.visibility = View.VISIBLE
                        txtIcon?.visibility = View.VISIBLE
                        txtIcon?.text = "🔋"
                        txtSubtitle?.text = "Charge to 100% capacity"
                        sliderSection?.visibility = View.GONE
                    }
                    1 -> {
                        txtTitle?.text = "SET TIME"
                        txtSubtitle?.visibility = View.GONE
                        txtIcon?.visibility = View.GONE
                        sliderSection?.visibility = View.VISIBLE
                        seekBar?.max = 576
                        seekBar?.progress = time / 5
                        updateTimeUI()
                    }
                    2 -> {
                        txtTitle?.text = "SET UNITS"
                        txtSubtitle?.visibility = View.GONE
                        txtIcon?.visibility = View.GONE
                        sliderSection?.visibility = View.VISIBLE
                        seekBar?.max = 100
                        seekBar?.progress = units
                        updateUnitsUI()
                    }
                }
                updateOnlineStatus()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Slider logic
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val currentTab = tabLayout?.selectedTabPosition ?: 0
                    if (currentTab == 1) { // SET TIME
                        time = progress * 5
                        updateTimeUI()
                    } else if (currentTab == 2) { // SET UNITS
                        units = progress
                        updateUnitsUI()
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnMinus?.setOnClickListener {
            val currentTab = tabLayout?.selectedTabPosition ?: 0
            if (currentTab == 1) {
                time = maxOf(5, time - 5)
                seekBar?.progress = time / 5
                updateTimeUI()
            } else if (currentTab == 2) {
                units = maxOf(1, units - 1)
                seekBar?.progress = units
                updateUnitsUI()
            }
        }

        btnPlus?.setOnClickListener {
            val currentTab = tabLayout?.selectedTabPosition ?: 0
            if (currentTab == 1) {
                time += 5
                seekBar?.progress = time / 5
                updateTimeUI()
            } else if (currentTab == 2) {
                units += 1
                seekBar?.progress = units
                updateUnitsUI()
            }
        }

        sliderButton?.onSlideRight = {
            sliderButton?.let { sb ->
                if (!sb.isLocked) {
                    val mode = when (tabLayout?.selectedTabPosition) {
                        1 -> "time"
                        2 -> "units"
                        else -> "full"
                    }
                    val value = when (mode) {
                        "time" -> time
                        "units" -> units
                        else -> null
                    }
                    
                    // Check connectivity
                    if (!GatewayManager.isDeviceOnline.value && lastFirebaseHeartbeat == 0L) {
                        Toast.makeText(context, "Device is offline. Command might fail.", Toast.LENGTH_SHORT).show()
                    }

                    // OPTIMIZATION: Send locally over BLE immediately if connected
                    if (GatewayManager.isDeviceOnline.value) {
                        val ocppStart = "[2,\"${System.currentTimeMillis()}\",\"RemoteStart\",{\"mode\":\"$mode\",\"tid\":\"T${System.currentTimeMillis()}\"}]"
                        GatewayManager.bleTransport.send(ocppStart)
                    }

                    currentState = DeviceState.STATE_STARTING
                    commandStartTime = System.currentTimeMillis()
                    updateOnlineStatus()
                    
                    sb.showProgress("Starting...")
                    FirebaseApiManager.startCharging(deviceId, mode, value,
                        onResult = { 
                            LogRepository.append("Firebase: Start Charging Ack: $it")
                            activity?.runOnUiThread {
                                Toast.makeText(context, "Start Command Sent", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onError = { 
                            LogRepository.append("Firebase: Start Charging Error: $it")
                            activity?.runOnUiThread {
                                if (currentState == DeviceState.STATE_STARTING) {
                                    currentState = DeviceState.STATE_AVAILABLE
                                    updateOnlineStatus()
                                    sb.hideProgress()
                                    Toast.makeText(context, "Error: $it", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
            }
        }
        
        sliderButton?.onSlideLeft = {
            sliderButton?.let { sb ->
                if (!sb.isLocked) {
                    transactionId?.let { tid ->
                        // OPTIMIZATION: Send locally over BLE immediately if connected
                        if (GatewayManager.isDeviceOnline.value) {
                            val ocppStop = "[2,\"${System.currentTimeMillis()}\",\"RemoteStop\",{\"tid\":\"$tid\"}]"
                            GatewayManager.bleTransport.send(ocppStop)
                        }

                        currentState = DeviceState.STATE_STOPPING
                        commandStartTime = System.currentTimeMillis()
                        updateOnlineStatus()
                        
                        sb.showProgress("Stopping...")
                        FirebaseApiManager.stopCharging(deviceId, tid,
                            onResult = { 
                                LogRepository.append("Firebase: Stop Charging Ack: $it")
                                activity?.runOnUiThread {
                                    Toast.makeText(context, "Stop Command Sent", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onError = { 
                                LogRepository.append("Firebase: Stop Charging Error: $it")
                                activity?.runOnUiThread {
                                    if (currentState == DeviceState.STATE_STOPPING) {
                                        currentState = DeviceState.STATE_CHARGING // Revert
                                        updateOnlineStatus()
                                        sb.hideProgress()
                                        Toast.makeText(context, "Error: $it", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        )
                    } ?: run {
                        LogRepository.append("Firebase: Stop Charging Error - No Transaction ID")
                        Toast.makeText(context, "No active transaction to stop", Toast.LENGTH_SHORT).show()
                        sb.setState(false) // Slide back to right
                    }
                }
            }
        }

        // Setup listener initially
        setupFirebaseListener()

        // Watch GatewayManager for real-time MeterData
        viewLifecycleOwner.lifecycleScope.launch {
            GatewayManager.latestMeterData.collect { data ->
                data?.let { updateLCD(it) }
            }
        }

        // Periodic aliveness check (every 5 seconds)
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5000)
                
                // Check command timeout
                if (commandStartTime > 0 && System.currentTimeMillis() - commandStartTime > COMMAND_TIMEOUT) {
                    activity?.runOnUiThread {
                        if (currentState == DeviceState.STATE_STARTING) {
                            currentState = DeviceState.STATE_AVAILABLE
                            Toast.makeText(context, "Start Command Timed Out", Toast.LENGTH_LONG).show()
                        } else if (currentState == DeviceState.STATE_STOPPING) {
                            currentState = DeviceState.STATE_CHARGING
                            Toast.makeText(context, "Stop Command Timed Out", Toast.LENGTH_LONG).show()
                        }
                        commandStartTime = 0
                        updateOnlineStatus()
                    }
                } else {
                    updateOnlineStatus()
                }
            }
        }

        // Also watch GatewayManager's online status directly for immediate UI reaction
        viewLifecycleOwner.lifecycleScope.launch {
            GatewayManager.isDeviceOnline.collect {
                updateOnlineStatus()
            }
        }

        // Also watch for GatewayManager's currentDeviceId to re-setup the listener and update UI
        viewLifecycleOwner.lifecycleScope.launch {
            GatewayManager.currentDeviceId.collect { id ->
                if (id.isNotEmpty() && id != deviceId) {
                    deviceId = id
                    updateDeviceSelectorText(id)
                    setupFirebaseListener()
                }
            }
        }

        // Bridge state override for instant UI reaction
        viewLifecycleOwner.lifecycleScope.launch {
            GatewayManager.bridgeDetectedState.collect { newState ->
                newState?.let {
                    if (currentState != it) {
                        Log.d(TAG, "Bridge detected state change: $currentState -> $it")
                        currentState = it
                        updateOnlineStatus()
                    }
                }
            }
        }

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        statusListener?.let { deviceRef?.removeEventListener(it) }
        lcdView = null
        sliderButton = null
        tabLayout = null
        txtTitle = null
        txtSubtitle = null
        txtIcon = null
        txtValue = null
        txtInfo = null
        sliderSection = null
        seekBar = null
        btnMinus = null
        btnPlus = null
        deviceSelector = null
        mainContent = null
        emptyState = null
        btnSetupFirstDevice = null
    }

    private fun setupDeviceSelector() {
        val ctx = context ?: return
        val knownDevices = BlePrefs.getKnownDevices(ctx)
        
        if (knownDevices.isEmpty()) {
            mainContent?.visibility = View.GONE
            emptyState?.visibility = View.VISIBLE
        } else {
            mainContent?.visibility = View.VISIBLE
            emptyState?.visibility = View.GONE
        }

        val deviceStrings = knownDevices.map { "${it.first ?: "Unknown"} (${it.second})" } + "Add New Device..."
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, deviceStrings)
        deviceSelector?.setAdapter(adapter)
        
        val currentId = MqttPrefs.loadDeviceId(ctx)
        updateDeviceSelectorText(currentId)

        deviceSelector?.setOnItemClickListener { _, _, position, _ ->
            if (position == knownDevices.size) {
                startActivity(android.content.Intent(ctx, DeviceScanActivity::class.java))
            } else {
                val selected = knownDevices[position]
                
                // Check if bonded with OS
                val btManager = ctx.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
                val btAdapter = btManager.adapter
                val isBonded = try {
                    if (androidx.core.app.ActivityCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                        btAdapter?.bondedDevices?.any { it.address == selected.second } == true
                    } else false
                } catch (e: Exception) { false }

                if (isBonded) {
                    androidx.appcompat.app.AlertDialog.Builder(ctx)
                        .setTitle("Action Required")
                        .setMessage("This PowerTap is paired with your phone settings, which prevents the app from connecting. Please 'Unpair' it from Bluetooth Settings and try again.")
                        .setPositiveButton("Bluetooth Settings") { _, _ ->
                            startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    MqttPrefs.save(ctx, MqttPrefs.load(ctx), selected.second)
                    GatewayManager.bleTransport.connect(selected.second)
                    
                    deviceId = selected.second
                    setupFirebaseListener()
                }
            }
        }
    }

    private fun updateDeviceSelectorText(id: String) {
        val ctx = context ?: return
        if (id.isEmpty()) return
        val known = BlePrefs.getKnownDevices(ctx)
        val index = known.indexOfFirst { it.second == id }
        val text = if (index != -1) {
            "${known[index].first ?: "Unknown"} (${known[index].second})"
        } else {
            id
        }
        deviceSelector?.setText(text, false)
    }

    private fun updateTimeUI() {
        val hours = time / 60
        val mins = time % 60
        txtValue?.text = String.format(Locale.getDefault(), "%d:%02d", hours, mins)
        val energy = (time / 60f) * 3
        txtInfo?.text = String.format(Locale.getDefault(), "Estimated energy gain: ~ %d KWh\nCharging will stop at %d hour, %d min", energy.toInt(), hours, mins)
    }

    private fun updateUnitsUI() {
        txtValue?.text = String.format(Locale.getDefault(), "%d KWh", units)
        val estimatedHours = units / 3
        txtInfo?.text = String.format(Locale.getDefault(), "Estimated duration: ~ %d hours\nCharging will stop at %d KWh", estimatedHours, units)
    }

    private fun updateLCD(data: com.drivool.iot.powertap.contract.MeterData) {
        val isCharging = currentState == DeviceState.STATE_CHARGING || currentState == DeviceState.STATE_STARTED || currentState == DeviceState.STATE_STARTING
        
        val voltageStr = String.format(Locale.getDefault(), "%.1fV", data.voltage)
        val energyStr = String.format(Locale.getDefault(), "%.3fkWh", data.energy)
        val dateFormat = SimpleDateFormat("dd MMM hh:mm a", Locale.getDefault())
        val dateStr = dateFormat.format(Date()).uppercase()

        if (isCharging) {
            val currentStr = String.format(Locale.getDefault(), "%.2fA", data.current)
            val powerStr = if (data.power < 10) String.format(Locale.getDefault(), "%.2fW", data.power) 
                           else String.format(Locale.getDefault(), "%.1fW", data.power)
            
            lcdView?.setText(
                listOf(
                    LCDSegment(voltageStr, 22f, Align.LEFT, 1f, true, "VOLTAGE"),
                    LCDSegment(dateStr, 14f, Align.CENTER, 1.5f, false, "TIME"),
                    LCDSegment(powerStr, 22f, Align.RIGHT, 1f, true, "POWER")
                ),
                listOf(
                    LCDSegment(energyStr, 20f, Align.LEFT, 1.2f, true, "ENERGY"),
                    LCDSegment(currentStr, 20f, Align.RIGHT, 1f, true, "CURRENT")
                )
            )
        } else {
            lcdView?.setText(
                listOf(
                    LCDSegment(voltageStr, 22f, Align.LEFT, 1f, true, "VOLTAGE"),
                    LCDSegment(dateStr, 14f, Align.CENTER, 1.5f, false, "LAST UPDATED"),
                    LCDSegment(energyStr, 22f, Align.RIGHT, 1f, true, "ENERGY")
                ),
                emptyList()
            )
        }
    }

    private fun updateOnlineStatus() {
        val currentTime = System.currentTimeMillis() + serverTimeOffset
        val diffSeconds = if (lastFirebaseHeartbeat > 0) (currentTime - lastFirebaseHeartbeat) / 1000 else 999
        
        // Is online if Firebase heartbeat is recent OR if GatewayManager reports it's online via BLE/MQTT
        val isFirebaseOnline = lastFirebaseHeartbeat > 0 && diffSeconds < 45
        val isGatewayOnline = GatewayManager.isDeviceOnline.value
        val isOnline = isFirebaseOnline || isGatewayOnline
        
        activity?.runOnUiThread {
            val sb = sliderButton ?: return@runOnUiThread
            val subtitle = txtSubtitle ?: return@runOnUiThread
            val tl = tabLayout ?: return@runOnUiThread
            val ss = sliderSection ?: return@runOnUiThread

            if (isOnline) {
                val statusText = if (isGatewayOnline) "Connected" else "Online"
                subtitle.text = "ID: $deviceId | $statusText"
                
                // Don't update SB if user is dragging
                // if (sb.isDragging) return@runOnUiThread // Wait, SliderButtonView doesn't expose isDragging

                when (currentState) {
                    DeviceState.STATE_AVAILABLE, DeviceState.STATE_STOPPED -> {
                        sb.activate(true)
                        sb.hideProgress()
                        sb.setState(true) // Left side
                        sb.setText(if (tl.selectedTabPosition == 0) "Slide to Start Charging" else "Slide to Confirm")
                        ss.visibility = if (tl.selectedTabPosition == 0) View.GONE else View.VISIBLE
                    }
                    DeviceState.STATE_STARTING -> {
                        sb.activate(true)
                        sb.showProgress("Starting...")
                        ss.visibility = View.GONE
                    }
                    DeviceState.STATE_CHARGING, DeviceState.STATE_STARTED -> {
                        sb.activate(true)
                        sb.hideProgress()
                        sb.setState(false) // Right side
                        sb.setText("Slide to Stop")
                        ss.visibility = View.GONE
                        commandStartTime = 0 // Reset timeout
                    }
                    DeviceState.STATE_STOPPING -> {
                        sb.activate(true)
                        sb.showProgress("Stopping...")
                        ss.visibility = View.GONE
                    }
                    else -> {
                        sb.activate(true)
                        sb.setText("Status: $currentState")
                    }
                }
            } else {
                if (sb.isActive) {
                    sb.activate(false)
                    sb.setText("Device is Offline")
                }
                subtitle.text = "ID: $deviceId | Diff: ${diffSeconds}s | Offline"
                
                // If offline, maybe we should also clear commandStartTime?
                // commandStartTime = 0 
            }
        }
    }

    private fun setupFirebaseListener() {
        val ctx = context ?: return
        statusListener?.let { deviceRef?.removeEventListener(it) }

        deviceId = MqttPrefs.loadDeviceId(ctx).lowercase().trim()
        if (deviceId.isNotEmpty()) {
            val database = FirebaseDatabase.getInstance()
            val ref = database.getReference("PowerTapMonitor/$deviceId")
            deviceRef = ref
            val offsetRef = database.getReference(".info/serverTimeOffset")

            offsetRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    serverTimeOffset = snapshot.getValue(Long::class.java) ?: 0L
                }
                override fun onCancelled(error: DatabaseError) {}
            })

            statusListener = ref.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) {
                        activity?.runOnUiThread {
                            sliderButton?.activate(false)
                            sliderButton?.setText("Device Not Found")
                            txtSubtitle?.text = "ID: $deviceId | Status: Not Found"
                        }
                        return
                    }

                    val newState = (snapshot.child("state").value as? Long)?.toInt() ?: DeviceState.STATE_AVAILABLE
                    
                    // Logic to accept new state: 
                    // If we were STARTING, and it's now CHARGING/STARTED, accept it.
                    // If we were STOPPING, and it's now AVAILABLE/STOPPED, accept it.
                    // Otherwise, if we aren't in a transient state, just accept it.
                    
                    val wasTransient = currentState == DeviceState.STATE_STARTING || currentState == DeviceState.STATE_STOPPING
                    
                    if (!wasTransient || 
                        (currentState == DeviceState.STATE_STARTING && (newState == DeviceState.STATE_CHARGING || newState == DeviceState.STATE_STARTED)) ||
                        (currentState == DeviceState.STATE_STOPPING && (newState == DeviceState.STATE_AVAILABLE || newState == DeviceState.STATE_STOPPED || newState == DeviceState.STATE_STOPPING))) {
                        
                        if (currentState != newState) {
                            Log.d(TAG, "State transition: $currentState -> $newState")
                            currentState = newState
                        }
                    }

                    transactionId = snapshot.child("transactionId").value as? String

                    val heartbeatObj = snapshot.child("time").value
                    var hb = 0L
                    if (heartbeatObj is Long) hb = heartbeatObj
                    else if (heartbeatObj is String) hb = heartbeatObj.toLongOrNull() ?: 0L
                    else if (heartbeatObj is Double) hb = heartbeatObj.toLong()

                    if (hb > 0 && hb < 2_000_000_000L) hb *= 1000
                    
                    lastFirebaseHeartbeat = hb
                    updateOnlineStatus()
                    
                    // Also update LCD layout based on state change
                    GatewayManager.latestMeterData.value?.let { updateLCD(it) }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Firebase listen failed: ${error.message}")
                    updateOnlineStatus()
                }
            })
        } else {
            sliderButton?.activate(false)
            sliderButton?.setText("No Device Connected")
        }
    }
}
