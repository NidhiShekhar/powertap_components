package com.drivool.iot.powertap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    private var pairingDialog: androidx.appcompat.app.AlertDialog? = null
    private var pairingMessageView: TextView? = null
    private var pairingTimeoutJob: Job? = null
    private var pendingPairAddress: String? = null
    private var pendingPairName: String = "PowerTap"
    private var pendingPairDeviceId: String = ""
    private var isPairingFromHome = false

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
                pairAndConnect(address, pendingPairDeviceId, pendingPairName)
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

        setupDeviceSelector()
        updateBleStatus(GatewayManager.bleTransport.connectionState.value)

        btnAddDevice?.setOnClickListener { showAddDeviceMenu(it) }
        btnSetupFirstDevice?.setOnClickListener { showAddDeviceMenu(it) }
        btnDisconnect?.setOnClickListener {
            GatewayManager.bleTransport.disconnect()
            Toast.makeText(context, "Disconnecting...", Toast.LENGTH_SHORT).show()
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

        viewLifecycleOwner.lifecycleScope.launch {
            GatewayManager.bleTransport.connectionState.collect { state ->
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
        btnAddDevice = null
        btnDisconnect = null
        btnSetupFirstDevice = null
        mainContent = null
        emptyState = null
        txtBleStatus = null
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        pairingDialog?.dismiss()
        pairingDialog = null
        pairingMessageView = null
        isPairingFromHome = false
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
        val known = BlePrefs.getKnownDevices(ctx)
        val selectedId = MqttPrefs.loadDeviceId(ctx)
        val selected = known.firstOrNull {
            DeviceIdentity.sameDevice(it.second, selectedId) ||
                DeviceIdentity.cleanHex(it.second) == DeviceIdentity.cleanHex(selectedId)
        }
        val paired = selected?.let { BlePrefs.isPaired(ctx, it.second) } == true

        val (text, color) = when (state) {
            ConnectionState.Scanning ->
                "Looking for your PowerTap nearby…" to R.color.primary_blue
            ConnectionState.Connecting ->
                "Found it — pairing now…" to R.color.primary_blue
            ConnectionState.Connected ->
                "Connected over Bluetooth" to R.color.status_success
            ConnectionState.Failed ->
                "Couldn't find the charger. Make sure it's on and nearby, then tap it again." to
                    R.color.status_error
            ConnectionState.Disconnected -> when {
                selected == null ->
                    "Tap the menu and choose a PowerTap to pair." to R.color.text_secondary
                paired ->
                    "Tap your PowerTap to reconnect." to R.color.text_secondary
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
        val isCharging = currentState == DeviceState.STATE_CHARGING || currentState == DeviceState.STATE_STARTED || currentState == DeviceState.STATE_STARTING
        
        val voltageStr = String.format(Locale.getDefault(), "%.1fV", data.voltage)
        val energyStr = String.format(Locale.getDefault(), "%.1fkWh", data.energy)
        val dateFormat = SimpleDateFormat("dd MMM hh:mm a", Locale.getDefault())
        val dateStr = dateFormat.format(Date()).uppercase()

        if (isCharging) {
            val currentStr = String.format(Locale.getDefault(), "%.1fA", data.current)
            val powerStr = String.format(Locale.getDefault(), "%.1fW", data.power)
            
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
            val bd = btnDisconnect

            if (isOnline) {
                bd?.visibility = if (isGatewayOnline) View.VISIBLE else View.GONE
                
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
                bd?.visibility = View.GONE
                val bleState = GatewayManager.bleTransport.connectionState.value
                val pairing = isPairingFromHome ||
                    bleState == ConnectionState.Scanning ||
                    bleState == ConnectionState.Connecting
                sb.activate(false)
                sb.setText(
                    if (pairing) "Pairing with PowerTap…" else "Device is Offline",
                )
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
