package com.drivool.iot.powertap

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.drivool.iot.powertap.ble.BlePrefs
import com.drivool.iot.powertap.contract.ConnectionState
import com.drivool.iot.powertap.mqtt.MqttPrefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var btnAddDevice: MaterialButton? = null
    private var btnDisconnect: MaterialButton? = null
    private var btnSetupFirstDevice: Button? = null
    private var mainContent: View? = null
    private var emptyState: View? = null
    private var txtBleStatus: TextView? = null
    private var txtBtOffBanner: TextView? = null

    private var pairingDialog: androidx.appcompat.app.AlertDialog? = null
    private var pairingMessageView: TextView? = null
    private var pairingTimeoutJob: Job? = null
    private var pendingPairAddress: String? = null
    private var pendingPairName: String = "PowerTap"
    private var pendingPairDeviceId: String = ""
    private var isPairingFromHome = false
    private var silentAutoConnect = false
    private var blePermissionAsked = false
    private var startProgressMessage = "Starting..."
    private var chargingUiStartedAt = 0L
    private var lastAnnouncedBleState: ConnectionState? = null
    private var lastHandledAckTs = 0L
    private var showingChargingCard = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    Toast.makeText(context, "Bluetooth is off — turn it on to connect", Toast.LENGTH_SHORT).show()
                    updateBleStatus(GatewayManager.bleTransport.connectionState.value)
                }
                BluetoothAdapter.STATE_ON -> {
                    Toast.makeText(context, "Bluetooth on — connecting to last PowerTap…", Toast.LENGTH_SHORT).show()
                    maybeAutoConnect()
                    updateBleStatus(GatewayManager.bleTransport.connectionState.value)
                }
            }
        }
    }

    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            val name = result.data?.getStringExtra(QrScanActivity.EXTRA_DISPLAY_NAME)
                ?: "PowerTap"
            Toast.makeText(context, "Pairing with $name", Toast.LENGTH_SHORT).show()
            setupDeviceSelector()
            result.data?.getStringExtra(QrScanActivity.EXTRA_DEVICE_ID)?.let { id ->
                deviceId = id
                updateDeviceSelectorText(id)
                setupFirebaseListener()
            }
            updateBleStatus(GatewayManager.bleTransport.connectionState.value)
        }
    }

    private val nearbyScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        setupDeviceSelector()
        context?.let { ctx ->
            val id = MqttPrefs.loadDeviceId(ctx)
            if (id.isNotEmpty()) {
                deviceId = id
                updateDeviceSelectorText(id)
                setupFirebaseListener()
            }
        }
        updateBleStatus(GatewayManager.bleTransport.connectionState.value)
    }

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants.values.all { it }
        if (granted) {
            val address = pendingPairAddress
            if (address != null) {
                if (silentAutoConnect) {
                    silentAutoConnect = false
                    maybeAutoConnect()
                } else {
                    pairAndConnect(address, pendingPairDeviceId, pendingPairName)
                }
            }
        } else {
            Toast.makeText(
                context,
                "Bluetooth permission is needed to pair with your PowerTap",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

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
        btnAddDevice = view.findViewById(R.id.btnAddDevice)
        btnDisconnect = view.findViewById(R.id.btnDisconnect)
        btnSetupFirstDevice = view.findViewById(R.id.btnSetupFirstDevice)
        mainContent = view.findViewById(R.id.mainContent)
        emptyState = view.findViewById(R.id.emptyState)
        txtBleStatus = view.findViewById(R.id.txtBleStatus)
        txtBtOffBanner = view.findViewById(R.id.txtBtOffBanner)

        txtBtOffBanner?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }

        setupDeviceSelector()
        updateBleStatus(GatewayManager.bleTransport.connectionState.value)

        btnAddDevice?.setOnClickListener { showAddDeviceMenu(it) }
        btnSetupFirstDevice?.setOnClickListener { showAddDeviceMenu(it) }
        btnDisconnect?.setOnClickListener {
            val wasCharging = isChargingUi(currentState)
            GatewayManager.markUserDisconnect()
            val msg = if (wasCharging) {
                "Disconnected Bluetooth. Charging continues on the charger."
            } else {
                "Disconnected from PowerTap"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            updateBleStatus(ConnectionState.Disconnected)
        }

        // Initialize LCD
        lcdView?.setText(
            listOf(LCDSegment("0.0V", 28f, Align.LEFT, 1f, true), LCDSegment("0.0Wh", 28f, Align.RIGHT, 1f, true)),
            listOf(LCDSegment("9APR 12:04AM", 24f, Align.CENTER, 1f, true))
        )

        // Initialize Tabs
        tabLayout?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                applySelectedTab(tab?.position ?: 0)
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
                    startProgressMessage = "Starting..."
                    updateOnlineStatus()
                    
                    sb.showProgress(startProgressMessage)
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
                        startProgressMessage = "Stopping..."
                        updateOnlineStatus()
                        
                        sb.showProgress(startProgressMessage)
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

        // Periodic aliveness check + charging duration clock
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(1000)

                if (commandStartTime > 0 && System.currentTimeMillis() - commandStartTime > COMMAND_TIMEOUT) {
                    activity?.runOnUiThread {
                        if (currentState == DeviceState.STATE_STARTING) {
                            currentState = DeviceState.STATE_AVAILABLE
                            chargingUiStartedAt = 0L
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

                if (isChargingUi(currentState)) {
                    GatewayManager.latestMeterData.value?.let { updateLCD(it) }
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

        viewLifecycleOwner.lifecycleScope.launch {
            GatewayManager.bleTransport.connectionState.collect { state ->
                announceBleState(state)
                updateBleStatus(state)
                if (!isPairingFromHome) return@collect
                when (state) {
                    ConnectionState.Scanning ->
                        setPairingMessage("Looking for your charger nearby…")
                    ConnectionState.Connecting ->
                        setPairingMessage("Found it — pairing now…")
                    ConnectionState.Connected -> {
                        val name = pendingPairName
                        finishPairing(success = true)
                        Toast.makeText(context, "Paired with $name", Toast.LENGTH_SHORT).show()
                        setupDeviceSelector()
                        updateOnlineStatus()
                    }
                    ConnectionState.Failed -> showPairingFailed()
                    ConnectionState.Disconnected -> { /* wait for timeout or next state */ }
                }
            }
        }

        // Firmware type-3 ACK: Accepted means "command received", StartTransaction means charging began
        viewLifecycleOwner.lifecycleScope.launch {
            GatewayManager.commandAck.collect { ack ->
                ack ?: return@collect
                if (ack.timestamp == lastHandledAckTs) return@collect
                lastHandledAckTs = ack.timestamp
                activity?.runOnUiThread {
                    when (ack.action) {
                        "RemoteStart" -> {
                            if (ack.accepted) {
                                startProgressMessage = "Buffering…"
                                if (currentState == DeviceState.STATE_STARTING ||
                                    currentState == DeviceState.STATE_AVAILABLE
                                ) {
                                    currentState = DeviceState.STATE_STARTING
                                }
                                Toast.makeText(context, "Charger accepted — starting…", Toast.LENGTH_SHORT).show()
                                updateOnlineStatus()
                            } else {
                                currentState = DeviceState.STATE_AVAILABLE
                                chargingUiStartedAt = 0L
                                commandStartTime = 0
                                Toast.makeText(context, "Charger rejected start (${ack.status})", Toast.LENGTH_LONG).show()
                                updateOnlineStatus()
                            }
                        }
                        "RemoteStop" -> {
                            if (ack.accepted) {
                                startProgressMessage = "Stopping..."
                                Toast.makeText(context, "Charger accepted stop", Toast.LENGTH_SHORT).show()
                                updateOnlineStatus()
                            } else {
                                currentState = DeviceState.STATE_CHARGING
                                commandStartTime = 0
                                Toast.makeText(context, "Charger rejected stop (${ack.status})", Toast.LENGTH_LONG).show()
                                updateOnlineStatus()
                            }
                        }
                    }
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
                        if (isChargingUi(it) && chargingUiStartedAt == 0L) {
                            chargingUiStartedAt = GatewayManager.chargingStartedAt.value
                                ?: System.currentTimeMillis()
                        }
                        if (!isChargingUi(it)) chargingUiStartedAt = 0L
                        updateOnlineStatus()
                        GatewayManager.latestMeterData.value?.let { data -> updateLCD(data) }
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
        btnAddDevice = null
        btnDisconnect = null
        btnSetupFirstDevice = null
        mainContent = null
        emptyState = null
        txtBleStatus = null
        txtBtOffBanner = null
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        pairingDialog?.dismiss()
        pairingDialog = null
        pairingMessageView = null
        isPairingFromHome = false
    }

    override fun onStart() {
        super.onStart()
        val ctx = context ?: return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(bluetoothReceiver, filter)
        }
        maybeAutoConnect()
        updateBleStatus(GatewayManager.bleTransport.connectionState.value)
    }

    override fun onStop() {
        try {
            context?.unregisterReceiver(bluetoothReceiver)
        } catch (_: IllegalArgumentException) { }
        super.onStop()
    }

    private fun isChargingUi(state: Int): Boolean = state == DeviceState.STATE_STARTING ||
        state == DeviceState.STATE_STARTED ||
        state == DeviceState.STATE_CHARGING ||
        state == DeviceState.STATE_STOPPING

    private fun isBluetoothEnabled(): Boolean {
        val ctx = context ?: return false
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return adapter != null && adapter.isEnabled
    }

    private fun lastDeviceDisplayName(): String {
        val ctx = context ?: return "PowerTap"
        val last = BlePrefs.getLastDeviceAddress(ctx) ?: return "PowerTap"
        val known = BlePrefs.getKnownDevices(ctx)
        val index = known.indexOfFirst { it.second.equals(last, ignoreCase = true) }
        return if (index >= 0) "PowerTap ${index + 1}" else "PowerTap"
    }

    /**
     * Opening the app reconnects the last charger without the pairing dialog.
     * Explicit Disconnect pauses this until the user picks a device again.
     */
    private fun maybeAutoConnect() {
        val ctx = context ?: return
        if (GatewayManager.userRequestedDisconnect) return
        if (!BlePrefs.isAutoConnectEnabled(ctx)) return
        val last = BlePrefs.getLastDeviceAddress(ctx) ?: return

        if (!isBluetoothEnabled()) {
            updateBleStatus(GatewayManager.bleTransport.connectionState.value)
            return
        }
        if (!hasBlePermissions(ctx)) {
            if (blePermissionAsked) return
            blePermissionAsked = true
            silentAutoConnect = true
            pendingPairAddress = last
            pendingPairDeviceId = DeviceIdentity.deviceIdFromBle(last)
                ?: DeviceIdentity.cleanHex(last)
                ?: last
            pendingPairName = lastDeviceDisplayName()
            blePermissionLauncher.launch(blePermissions())
            return
        }
        GatewayManager.tryAutoConnect()
    }

    private fun announceBleState(state: ConnectionState) {
        if (isPairingFromHome) {
            lastAnnouncedBleState = state
            return
        }
        val previous = lastAnnouncedBleState
        if (previous == state) return
        lastAnnouncedBleState = state
        val name = lastDeviceDisplayName()
        when {
            state == ConnectionState.Connected && previous != ConnectionState.Connected ->
                Toast.makeText(context, "Connected to $name", Toast.LENGTH_SHORT).show()
            state == ConnectionState.Disconnected &&
                (previous == ConnectionState.Connected || previous == ConnectionState.Connecting) ->
                Toast.makeText(context, "Disconnected from $name", Toast.LENGTH_SHORT).show()
            state == ConnectionState.Failed && previous != ConnectionState.Failed ->
                Toast.makeText(
                    context,
                    "Couldn't connect to $name. Make sure it's on and nearby.",
                    Toast.LENGTH_LONG,
                ).show()
        }
    }

    private fun applySelectedTab(position: Int) {
        if (isChargingUi(currentState)) {
            updateChargingCard()
            return
        }
        when (position) {
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
            else -> {
                txtTitle?.text = "FULL CHARGE"
                txtSubtitle?.visibility = View.VISIBLE
                txtIcon?.visibility = View.VISIBLE
                txtIcon?.text = "🔋"
                txtSubtitle?.text = "Charge to 100% capacity"
                sliderSection?.visibility = View.GONE
            }
        }
    }

    private fun updateChargingCard() {
        txtIcon?.visibility = View.VISIBLE
        txtSubtitle?.visibility = View.VISIBLE
        sliderSection?.visibility = View.GONE
        when (currentState) {
            DeviceState.STATE_STARTING -> {
                txtIcon?.text = "⚡"
                txtTitle?.text = "STARTING"
                txtSubtitle?.text = "Waiting for the charger to begin…"
            }
            DeviceState.STATE_STOPPING -> {
                txtIcon?.text = "⚡"
                txtTitle?.text = "STOPPING"
                txtSubtitle?.text = "Ending this charging session…"
            }
            else -> {
                txtIcon?.text = "⚡"
                txtTitle?.text = "CHARGING"
                txtSubtitle?.text = "Charging in progress"
            }
        }
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

        val adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_2, android.R.id.text1, mutableListOf<String>().apply {
            addAll(knownDevices.mapIndexed { i, _ -> "PowerTap ${i + 1}" })
            add("Add new device")
        }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val text1 = view.findViewById<TextView>(android.R.id.text1)
                val text2 = view.findViewById<TextView>(android.R.id.text2)
                
                if (position < knownDevices.size) {
                    val address = knownDevices[position].second
                    val id = address.replace(":", "")
                    text1.text = "PowerTap ${position + 1}"
                    text2.text = if (BlePrefs.isPaired(ctx, address)) {
                        id
                    } else {
                        "$id  ·  Tap to pair"
                    }
                    text2.visibility = View.VISIBLE
                } else {
                    text1.text = "Add new device"
                    text2.visibility = View.GONE
                }
                return view
            }
        }
        deviceSelector?.setAdapter(adapter)
        
        val currentId = MqttPrefs.loadDeviceId(ctx)
        updateDeviceSelectorText(currentId)

        deviceSelector?.setOnItemClickListener { _, view, position, _ ->
            if (position == knownDevices.size) {
                updateDeviceSelectorText(MqttPrefs.loadDeviceId(ctx))
                showAddDeviceMenu(btnAddDevice ?: view ?: deviceSelector!!)
                return@setOnItemClickListener
            }
            if (position !in knownDevices.indices) return@setOnItemClickListener
            val selected = knownDevices[position]
            val bleAddress = selected.second
            val resolvedId = DeviceIdentity.deviceIdFromBle(bleAddress)
                ?: DeviceIdentity.cleanHex(bleAddress)
                ?: bleAddress
            val displayName = selected.first?.takeIf { it.isNotBlank() }
                ?: "PowerTap ${position + 1}"

            updateDeviceSelectorText(resolvedId)
            pairAndConnect(bleAddress, resolvedId, displayName)
        }
    }

    private fun blePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasBlePermissions(ctx: android.content.Context): Boolean {
        return blePermissions().all {
            ActivityCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isOsBonded(ctx: android.content.Context, bleAddress: String): Boolean {
        return try {
            val btManager = ctx.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
                as android.bluetooth.BluetoothManager
            val adapter = btManager.adapter ?: return false
            val canReadBonds = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            canReadBonds && adapter.bondedDevices.any { it.address.equals(bleAddress, true) }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Pair from Home the same way Add Device does: scan until the charger
     * advertises, then GATT-connect. Direct connect without a scan is why
     * first-time Home taps used to fail.
     */
    private fun pairAndConnect(bleAddress: String, resolvedId: String, displayName: String) {
        val ctx = context ?: return

        pendingPairAddress = bleAddress
        pendingPairDeviceId = resolvedId
        pendingPairName = displayName

        val btManager = ctx.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
            as android.bluetooth.BluetoothManager
        val btAdapter = btManager.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            Toast.makeText(ctx, "Turn on Bluetooth to pair with your PowerTap", Toast.LENGTH_LONG).show()
            updateDeviceSelectorText(MqttPrefs.loadDeviceId(ctx))
            return
        }

        if (!hasBlePermissions(ctx)) {
            blePermissionLauncher.launch(blePermissions())
            return
        }

        if (isOsBonded(ctx, bleAddress)) {
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("Unpair in phone settings first")
                .setMessage(
                    "This PowerTap is paired in Android Bluetooth settings, which blocks the app.\n\n" +
                        "Open Bluetooth settings, tap the charger, choose Forget / Unpair, then come back here and tap it again to pair through PowerTap.",
                )
                .setPositiveButton("Bluetooth Settings") { _, _ ->
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val alreadyConnected = GatewayManager.bleTransport.connectionState.value == ConnectionState.Connected &&
            GatewayManager.bleTransport.connectedAddress.value.equals(bleAddress, ignoreCase = true)
        if (alreadyConnected) {
            Toast.makeText(ctx, "Already connected to $displayName", Toast.LENGTH_SHORT).show()
            deviceId = resolvedId
            setupFirebaseListener()
            updateBleStatus(ConnectionState.Connected)
            return
        }

        deviceId = resolvedId
        setupFirebaseListener()

        isPairingFromHome = true
        showPairingDialog(displayName)

        if (!GatewayManager.connectToBle(bleAddress, resolvedId, displayName, scanFirst = true)) {
            finishPairing(success = false)
            Toast.makeText(ctx, "Turn on Bluetooth to pair with your PowerTap", Toast.LENGTH_LONG).show()
            return
        }
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(25_000)
            if (isPairingFromHome &&
                GatewayManager.bleTransport.connectionState.value != ConnectionState.Connected
            ) {
                GatewayManager.bleTransport.stopScan()
                GatewayManager.bleTransport.disconnect()
                showPairingFailed()
            }
        }
    }

    private fun showPairingDialog(name: String) {
        val ctx = context ?: return
        pairingDialog?.dismiss()

        val content = layoutInflater.inflate(R.layout.dialog_ble_pairing, null)
        pairingMessageView = content.findViewById(R.id.txtPairingMessage)
        pairingMessageView?.text = "Keep $name on and within a few metres of your phone."

        pairingDialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Pairing with $name")
            .setView(content)
            .setNegativeButton("Cancel") { _, _ -> cancelPairing() }
            .setCancelable(false)
            .show()
        updateBleStatus(GatewayManager.bleTransport.connectionState.value)
    }

    private fun setPairingMessage(message: String) {
        pairingMessageView?.text = message
    }

    private fun cancelPairing() {
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        isPairingFromHome = false
        GatewayManager.bleTransport.stopScan()
        GatewayManager.bleTransport.disconnect()
        pairingDialog?.dismiss()
        pairingDialog = null
        pairingMessageView = null
        updateBleStatus(ConnectionState.Disconnected)
    }

    private fun finishPairing(success: Boolean) {
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        isPairingFromHome = false
        pairingDialog?.dismiss()
        pairingDialog = null
        pairingMessageView = null
        if (success) {
            pendingPairAddress = null
        }
        updateBleStatus(GatewayManager.bleTransport.connectionState.value)
    }

    private fun showPairingFailed() {
        val ctx = context ?: return
        val name = pendingPairName
        val address = pendingPairAddress
        val id = pendingPairDeviceId
        finishPairing(success = false)

        pairingDialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Couldn't find $name")
            .setMessage(
                "The charger has to be powered on and nearby so the app can pair over Bluetooth — " +
                    "the same step as Add device → Scan nearby.\n\n" +
                    "Make sure the PowerTap is on, then try again.",
            )
            .setPositiveButton("Try again") { _, _ ->
                if (address != null) pairAndConnect(address, id, name)
            }
            .setNeutralButton("Scan nearby") { _, _ ->
                nearbyScanLauncher.launch(Intent(ctx, DeviceScanActivity::class.java))
            }
            .setNegativeButton("Cancel", null)
            .show()
        updateBleStatus(ConnectionState.Failed)
    }

    private fun updateBleStatus(state: ConnectionState) {
        val ctx = context ?: return
        val status = txtBleStatus ?: return
        val banner = txtBtOffBanner
        val known = BlePrefs.getKnownDevices(ctx)
        val selectedId = MqttPrefs.loadDeviceId(ctx)
        val selected = known.firstOrNull {
            DeviceIdentity.sameDevice(it.second, selectedId) ||
                DeviceIdentity.cleanHex(it.second) == DeviceIdentity.cleanHex(selectedId)
        }
        val paired = selected?.let { BlePrefs.isPaired(ctx, it.second) } == true
        val name = lastDeviceDisplayName()

        if (!isBluetoothEnabled()) {
            banner?.visibility = View.VISIBLE
            status.visibility = View.GONE
            updateOnlineStatus()
            return
        }
        banner?.visibility = View.GONE
        status.visibility = View.VISIBLE

        val (text, color) = when (state) {
            ConnectionState.Scanning ->
                "Looking for $name nearby…" to R.color.primary_blue
            ConnectionState.Connecting ->
                "Connecting to $name…" to R.color.primary_blue
            ConnectionState.Connected ->
                "Connected to $name over Bluetooth" to R.color.status_success
            ConnectionState.Failed ->
                "Couldn't find $name. Make sure it's on and nearby, then tap it again." to
                    R.color.status_error
            ConnectionState.Disconnected -> when {
                GatewayManager.userRequestedDisconnect ->
                    "Disconnected. Tap your PowerTap to reconnect." to R.color.text_secondary
                selected == null ->
                    "Tap the menu and choose a PowerTap to pair." to R.color.text_secondary
                paired ->
                    "Connecting to last PowerTap…" to R.color.text_secondary
                else ->
                    "Tap your PowerTap to pair. Keep the charger on and nearby." to
                        R.color.status_warning
            }
        }
        status.text = text
        status.setTextColor(ctx.getColor(color))
        updateOnlineStatus()
    }

    private fun showAddDeviceMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_add_device, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_scan_qr -> {
                    qrScanLauncher.launch(Intent(requireContext(), QrScanActivity::class.java))
                    true
                }
                R.id.action_scan_nearby -> {
                    nearbyScanLauncher.launch(Intent(requireContext(), DeviceScanActivity::class.java))
                    true
                }
                R.id.action_enter_id -> {
                    showEnterDeviceIdDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showEnterDeviceIdDialog() {
        val ctx = context ?: return
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val idInput = EditText(ctx).apply {
            hint = "Device ID or PowerTap_XXXXXXXXXXXX"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        val bleInput = EditText(ctx).apply {
            hint = "BLE address (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        container.addView(idInput)
        container.addView(bleInput)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Enter Device")
            .setMessage("Enter the PowerTap name or 12-character device ID. BLE address is optional if you only know the ID.")
            .setView(container)
            .setPositiveButton("Connect", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val qr = PowerTapQr.fromManualEntry(
                    idInput.text.toString(),
                    bleInput.text.toString().ifBlank { null },
                )
                if (qr == null) {
                    Toast.makeText(
                        ctx,
                        "Enter a valid 12-character device ID (or PowerTap_… name)",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@setOnClickListener
                }

                pairAndConnect(qr.bleAddress, qr.deviceId, qr.displayName)
                setupDeviceSelector()
                updateDeviceSelectorText(qr.deviceId)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updateDeviceSelectorText(id: String) {
        val ctx = context ?: return
        if (id.isEmpty()) {
            deviceSelector?.setText("Select PowerTap", false)
            return
        }
        val known = BlePrefs.getKnownDevices(ctx)
        val index = known.indexOfFirst {
            DeviceIdentity.sameDevice(it.second, id) ||
                DeviceIdentity.cleanHex(it.second) == DeviceIdentity.cleanHex(id) ||
                DeviceIdentity.deviceIdFromBle(it.second) == DeviceIdentity.cleanHex(id)
        }
        val text = if (index != -1) {
            "PowerTap ${index + 1}"
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
        val charging = isChargingUi(currentState)
        
        val voltageStr = String.format(Locale.getDefault(), "%.1fV", data.voltage)
        val energyStr = MeterUnits.formatEnergyWh(data.energy)
        val dateFormat = SimpleDateFormat("dd MMM hh:mm a", Locale.getDefault())
        val dateStr = dateFormat.format(Date()).uppercase()

        if (charging) {
            val currentStr = String.format(Locale.getDefault(), "%.1fA", data.current)
            val powerStr = MeterUnits.formatPowerWatts(data.power)
            val startedAt = GatewayManager.chargingStartedAt.value
                ?: TransactionRepository.sessions.value.firstOrNull { it.status == "Active" }?.startTime
                ?: chargingUiStartedAt.takeIf { it > 0 }
                ?: commandStartTime.takeIf { it > 0 }
            if (startedAt != null && chargingUiStartedAt == 0L) chargingUiStartedAt = startedAt
            val durationStr = MeterUnits.formatDuration(
                System.currentTimeMillis() - (startedAt ?: System.currentTimeMillis())
            )
            
            lcdView?.setText(
                listOf(
                    LCDSegment(voltageStr, 22f, Align.LEFT, 1f, true, "VOLTAGE"),
                    LCDSegment(dateStr, 14f, Align.CENTER, 1.5f, false, "TIME"),
                    LCDSegment(powerStr, 22f, Align.RIGHT, 1f, true, "POWER")
                ),
                listOf(
                    LCDSegment(energyStr, 18f, Align.LEFT, 1f, true, "ENERGY"),
                    LCDSegment(durationStr, 18f, Align.CENTER, 1.2f, true, "DURATION"),
                    LCDSegment(currentStr, 18f, Align.RIGHT, 1f, true, "CURRENT")
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
        
        val isFirebaseOnline = lastFirebaseHeartbeat > 0 && diffSeconds < 45
        val isGatewayOnline = GatewayManager.isDeviceOnline.value
        val bleConnected = GatewayManager.bleTransport.connectionState.value == ConnectionState.Connected
        val isOnline = isFirebaseOnline || isGatewayOnline || bleConnected
        
        activity?.runOnUiThread {
            val sb = sliderButton ?: return@runOnUiThread
            val tl = tabLayout ?: return@runOnUiThread
            val ss = sliderSection ?: return@runOnUiThread
            val bd = btnDisconnect

            // Disconnect only while this phone holds the BLE link. Hidden during
            // scan/connect and after the user (or a drop) is no longer GATT-connected.
            bd?.visibility = if (bleConnected) View.VISIBLE else View.GONE

            val charging = isChargingUi(currentState)
            if (charging) {
                updateChargingCard()
                showingChargingCard = true
            } else if (showingChargingCard) {
                applySelectedTab(tl.selectedTabPosition)
                showingChargingCard = false
            }

            if (isOnline) {
                when (currentState) {
                    DeviceState.STATE_AVAILABLE, DeviceState.STATE_STOPPED -> {
                        sb.activate(true)
                        sb.hideProgress()
                        sb.setState(true)
                        sb.setText(if (tl.selectedTabPosition == 0) "Slide to Start Charging" else "Slide to Confirm")
                        ss.visibility = if (tl.selectedTabPosition == 0) View.GONE else View.VISIBLE
                    }
                    DeviceState.STATE_STARTING -> {
                        sb.activate(true)
                        sb.showProgress(startProgressMessage)
                        ss.visibility = View.GONE
                    }
                    DeviceState.STATE_CHARGING, DeviceState.STATE_STARTED -> {
                        sb.activate(true)
                        sb.hideProgress()
                        sb.setState(false)
                        sb.setText("Slide to Stop")
                        ss.visibility = View.GONE
                        commandStartTime = 0
                    }
                    DeviceState.STATE_STOPPING -> {
                        sb.activate(true)
                        sb.showProgress(startProgressMessage)
                        ss.visibility = View.GONE
                    }
                    else -> {
                        sb.activate(true)
                        sb.setText("Status: $currentState")
                    }
                }
            } else {
                val bleState = GatewayManager.bleTransport.connectionState.value
                val pairing = isPairingFromHome ||
                    bleState == ConnectionState.Scanning ||
                    bleState == ConnectionState.Connecting
                if (isChargingUi(currentState)) {
                    // Session is live on the charger; don't grey the slider just because
                    // cloud heartbeat lagged.
                    sb.activate(true)
                    if (currentState == DeviceState.STATE_STARTING || currentState == DeviceState.STATE_STOPPING) {
                        sb.showProgress(startProgressMessage)
                    } else {
                        sb.hideProgress()
                        sb.setState(false)
                        sb.setText("Slide to Stop")
                    }
                } else {
                    sb.activate(false)
                    sb.setText(
                        when {
                            !isBluetoothEnabled() -> "Turn on Bluetooth"
                            pairing -> "Connecting to PowerTap…"
                            else -> "Device is Offline"
                        },
                    )
                }
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
                        }
                        return
                    }

                    val newState = (snapshot.child("state").value as? Long)?.toInt() ?: DeviceState.STATE_AVAILABLE
                    
                    val wasTransient = currentState == DeviceState.STATE_STARTING || currentState == DeviceState.STATE_STOPPING
                    val bleConnected = GatewayManager.bleTransport.connectionState.value == ConnectionState.Connected
                    val localCharging = currentState == DeviceState.STATE_CHARGING ||
                        currentState == DeviceState.STATE_STARTED ||
                        currentState == DeviceState.STATE_STARTING
                    val remoteIdle = newState == DeviceState.STATE_AVAILABLE || newState == DeviceState.STATE_STOPPED
                    // BLE StartTransaction is faster than Firebase. Don't let a stale
                    // "available" snapshot grey the slider after charging has begun.
                    val ignoreStaleIdle = bleConnected && localCharging && remoteIdle
                    
                    if (!ignoreStaleIdle && (!wasTransient || 
                        (currentState == DeviceState.STATE_STARTING && (newState == DeviceState.STATE_CHARGING || newState == DeviceState.STATE_STARTED)) ||
                        (currentState == DeviceState.STATE_STOPPING && (newState == DeviceState.STATE_AVAILABLE || newState == DeviceState.STATE_STOPPED || newState == DeviceState.STATE_STOPPING)))) {
                        
                        if (currentState != newState) {
                            Log.d(TAG, "State transition: $currentState -> $newState")
                            currentState = newState
                            if (isChargingUi(newState) && chargingUiStartedAt == 0L) {
                                chargingUiStartedAt = GatewayManager.chargingStartedAt.value
                                    ?: System.currentTimeMillis()
                            }
                            if (!isChargingUi(newState)) chargingUiStartedAt = 0L
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
