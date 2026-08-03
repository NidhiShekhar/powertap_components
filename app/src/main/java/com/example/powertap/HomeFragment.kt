package com.drivool.iot.powertap

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.drivool.iot.powertap.mqtt.MqttPrefs
import com.google.android.material.tabs.TabLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment() {

    private var time = 60
    private var units = 10
    private var deviceId: String = ""
    private var currentState: Int = DeviceState.STATE_AVAILABLE
    private var transactionId: String? = null
    private var statusListener: ValueEventListener? = null
    private var deviceRef: com.google.firebase.database.DatabaseReference? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        val lcdView: TwoLineLCDView = view.findViewById(R.id.lcd_view)
        val sliderButton: SliderButtonView = view.findViewById(R.id.slider_button)
        val tabLayout: TabLayout = view.findViewById(R.id.tabLayout)
        val txtTitle: TextView = view.findViewById(R.id.txtTitle)
        val txtSubtitle: TextView = view.findViewById(R.id.txtSubtitle)
        val txtIcon: TextView = view.findViewById(R.id.txtIcon)
        val txtValue: TextView = view.findViewById(R.id.txtValue)
        val txtInfo: TextView = view.findViewById(R.id.txtInfo)
        val sliderSection: View = view.findViewById(R.id.sliderSection)
        val seekBar: SeekBar = view.findViewById(R.id.seekBar)
        val btnMinus: Button = view.findViewById(R.id.btnMinus)
        val btnPlus: Button = view.findViewById(R.id.btnPlus)

        // Initialize LCD
        lcdView.setText(
            listOf(LCDSegment("0V", 28f, Align.LEFT, 1f, true), LCDSegment("0Wh", 28f, Align.RIGHT, 1f, true)),
            listOf(LCDSegment("9APR 12:04AM", 24f, Align.CENTER, 1f, true))
        )

        fun updateTimeUI() {
            val hours = time / 60
            val mins = time % 60
            txtValue.text = String.format(Locale.getDefault(), "%d:%02d", hours, mins)
            val energy = (time / 60f) * 3
            txtInfo.text = String.format(Locale.getDefault(), "Estimated energy gain: ~ %d KWh\nCharging will stop at %d hour, %d min", energy.toInt(), hours, mins)
        }

        fun updateUnitsUI() {
            txtValue.text = String.format(Locale.getDefault(), "%d KWh", units)
            val estimatedHours = units / 3
            txtInfo.text = String.format(Locale.getDefault(), "Estimated duration: ~ %d hours\nCharging will stop at %d KWh", estimatedHours, units)
        }

        // Initialize Tabs
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        txtTitle.text = "FULL CHARGE"
                        txtSubtitle.visibility = View.VISIBLE
                        txtIcon.visibility = View.VISIBLE
                        txtIcon.text = "🔋"
                        txtSubtitle.text = "Charge to 100% capacity"
                        sliderSection.visibility = View.GONE
                    }
                    1 -> {
                        txtTitle.text = "SET TIME"
                        txtSubtitle.visibility = View.GONE
                        txtIcon.visibility = View.GONE
                        sliderSection.visibility = View.VISIBLE
                        seekBar.max = 576
                        seekBar.progress = time / 5
                        updateTimeUI()
                    }
                    2 -> {
                        txtTitle.text = "SET UNITS"
                        txtSubtitle.visibility = View.GONE
                        txtIcon.visibility = View.GONE
                        sliderSection.visibility = View.VISIBLE
                        seekBar.max = 100
                        seekBar.progress = units
                        updateUnitsUI()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Slider logic
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val currentTab = tabLayout.selectedTabPosition
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

        btnMinus.setOnClickListener {
            val currentTab = tabLayout.selectedTabPosition
            if (currentTab == 1) {
                time = maxOf(5, time - 5)
                seekBar.progress = time / 5
                updateTimeUI()
            } else if (currentTab == 2) {
                units = maxOf(1, units - 1)
                seekBar.progress = units
                updateUnitsUI()
            }
        }

        btnPlus.setOnClickListener {
            val currentTab = tabLayout.selectedTabPosition
            if (currentTab == 1) {
                time += 5
                seekBar.progress = time / 5
                updateTimeUI()
            } else if (currentTab == 2) {
                units += 1
                seekBar.progress = units
                updateUnitsUI()
            }
        }

        var lastFirebaseHeartbeat = 0L
        var serverTimeOffset = 0L

        fun updateOnlineStatus() {
            val currentTime = System.currentTimeMillis() + serverTimeOffset
            val diffSeconds = if (lastFirebaseHeartbeat > 0) (currentTime - lastFirebaseHeartbeat) / 1000 else 999
            
            // Online threshold is now strictly 45 seconds as requested
            val isOnline = lastFirebaseHeartbeat > 0 && diffSeconds < 45
            
            activity?.runOnUiThread {
                if (isOnline) {
                    txtSubtitle.text = "ID: $deviceId | Online"
                    
                    when (currentState) {
                        DeviceState.STATE_AVAILABLE, DeviceState.STATE_STOPPED -> {
                            sliderButton.activate(true)
                            sliderButton.setState(true) // Left side
                            sliderButton.setText(if (tabLayout.selectedTabPosition == 0) "Slide to Start Charging" else "Slide to Confirm")
                            sliderButton.hideProgress()
                            sliderSection.visibility = if (tabLayout.selectedTabPosition == 0) View.GONE else View.VISIBLE
                        }
                        DeviceState.STATE_STARTING -> {
                            sliderButton.activate(true)
                            sliderButton.showProgress("Starting...")
                        }
                        DeviceState.STATE_CHARGING, DeviceState.STATE_STARTED -> {
                            sliderButton.activate(true)
                            sliderButton.setState(false) // Right side
                            sliderButton.setText("Slide to Stop")
                            sliderButton.hideProgress()
                            sliderSection.visibility = View.GONE
                        }
                        DeviceState.STATE_STOPPING -> {
                            sliderButton.activate(true)
                            sliderButton.showProgress("Stopping...")
                        }
                        else -> {
                            sliderButton.activate(true)
                            sliderButton.setText("Status: $currentState")
                        }
                    }
                } else {
                    if (sliderButton.isActive) {
                        sliderButton.activate(false)
                        sliderButton.setText("Device is Offline")
                    }
                    txtSubtitle.text = "ID: $deviceId | Diff: ${diffSeconds}s | Offline"
                }
            }
        }

        fun setupFirebaseListener() {
            // Remove old listener if exists
            statusListener?.let { deviceRef?.removeEventListener(it) }

            deviceId = MqttPrefs.loadDeviceId(requireContext()).lowercase().trim()
            if (deviceId.isNotEmpty()) {
                val database = FirebaseDatabase.getInstance()
                // Update to the correct path as per your Firebase rules: PowerTapMonitor/$deviceId
                val ref = database.getReference("PowerTapMonitor/$deviceId")
                deviceRef = ref
                txtSubtitle.text = "Device ID: $deviceId"
                val offsetRef = database.getReference(".info/serverTimeOffset")

                var localServerOffset = 0L

                offsetRef.addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        localServerOffset = snapshot.getValue(Long::class.java) ?: 0L
                        serverTimeOffset = localServerOffset
                        LogRepository.append("Firebase: Server time offset: $serverTimeOffset")
                    }
                    override fun onCancelled(error: DatabaseError) {
                        LogRepository.append("Firebase: Server time offset failed: ${error.message}")
                    }
                })

                statusListener = ref.addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        LogRepository.append("Firebase: Data changed for $deviceId: ${snapshot.exists()}")
                        if (!snapshot.exists()) {
                            sliderButton.activate(false)
                            sliderButton.setText("Device Not Found")
                            txtSubtitle.text = "ID: $deviceId | Status: Not Found"
                            return
                        }

                        // Get the 'time' field as shown in the Firebase console
                        val heartbeatObj = snapshot.child("time").value
                        LogRepository.append("Firebase: Heartbeat value: $heartbeatObj")

                        currentState = (snapshot.child("state").value as? Long)?.toInt() ?: DeviceState.STATE_AVAILABLE
                        transactionId = snapshot.child("transactionId").value as? String

                        var hb = 0L
                        if (heartbeatObj is Long) {
                            hb = heartbeatObj
                        } else if (heartbeatObj is String) {
                            hb = heartbeatObj.toLongOrNull() ?: 0L
                        } else if (heartbeatObj is Double) {
                            hb = heartbeatObj.toLong()
                        }

                        // Handle both seconds and milliseconds (epoch)
                        if (hb > 0 && hb < 2_000_000_000L) {
                            hb *= 1000 // Convert seconds to ms
                        }
                        
                        lastFirebaseHeartbeat = hb
                        updateOnlineStatus()
                    }

                    override fun onCancelled(error: DatabaseError) {
                        LogRepository.append("Firebase: Listen failed for $deviceId: ${error.message}")
                        updateOnlineStatus()
                    }
                })
            } else {
                sliderButton.activate(false)
                sliderButton.setText("No Device Connected")
            }
        }

        sliderButton.onSlideRight = {
            val mode = when (tabLayout.selectedTabPosition) {
                1 -> "time"
                2 -> "units"
                else -> "full"
            }
            val value = when (mode) {
                "time" -> time
                "units" -> units
                else -> null
            }
            
            sliderButton.showProgress("Connecting...")
            FirebaseApiManager.startCharging(deviceId, mode, value,
                onResult = { LogRepository.append("Firebase: Start Charging Ack: $it") },
                onError = { 
                    LogRepository.append("Firebase: Start Charging Error: $it")
                    sliderButton.hideProgress()
                }
            )
        }
        
        sliderButton.onSlideLeft = {
            transactionId?.let { tid ->
                sliderButton.showProgress("Stopping...")
                FirebaseApiManager.stopCharging(deviceId, tid,
                    onResult = { LogRepository.append("Firebase: Stop Charging Ack: $it") },
                    onError = { 
                        LogRepository.append("Firebase: Stop Charging Error: $it")
                        sliderButton.hideProgress()
                    }
                )
            } ?: run {
                LogRepository.append("Firebase: Stop Charging Error - No Transaction ID")
            }
        }

        // Setup listener initially
        setupFirebaseListener()

        // Watch GatewayManager for real-time MeterData
        viewLifecycleOwner.lifecycleScope.launch {
            GatewayManager.latestMeterData.collect { data ->
                data?.let {
                    val voltageStr = String.format(Locale.getDefault(), "%.1fV", it.voltage)
                    val energyStr = String.format(Locale.getDefault(), "%.3fKWh", it.energy)
                    val dateFormat = SimpleDateFormat("d MMM hh:mm a", Locale.getDefault())
                    val dateStr = dateFormat.format(Date()).uppercase()

                    lcdView.setText(
                        listOf(
                            LCDSegment(voltageStr, 28f, Align.LEFT, 1f, true),
                            LCDSegment(energyStr, 28f, Align.RIGHT, 1f, true)
                        ),
                        listOf(
                            LCDSegment(dateStr, 24f, Align.CENTER, 1f, true)
                        )
                    )
                }
            }
        }

        // Periodic aliveness check (every 5 seconds) to ensure button turns grey when idle
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5000)
                updateOnlineStatus()
            }
        }

        // Also watch for GatewayManager's currentDeviceId to re-setup the listener automatically
        viewLifecycleOwner.lifecycleScope.launch {
            GatewayManager.currentDeviceId.collect { id ->
                if (id.isNotEmpty() && id != deviceId) {
                    setupFirebaseListener()
                }
            }
        }

        return view
    }
}