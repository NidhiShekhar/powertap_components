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
import com.drivool.iot.powertap.session.BleConnectionPolicy
import com.drivool.iot.powertap.session.CloudSession
import com.drivool.iot.powertap.session.HardwareSession
import com.drivool.iot.powertap.session.LeaseState
import com.drivool.iot.powertap.session.Reconciliation
import com.drivool.iot.powertap.session.SessionLeaseStore
import com.drivool.iot.powertap.session.SessionReconciler
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

    /**
     * The session this phone owns, read straight from the durable lease.
     *
     * It used to be a plain field that the Firebase listener overwrote on every
     * snapshot and nulled whenever cloud state was not "charging". That is how
     * the phone lost track of a live session and then offered to "reset",
     * abandoning a charge that was still running.
     */
    private val transactionId: String?
        get() = SessionLeaseStore.open?.transactionId

    private var statusListener: ValueEventListener? = null
    private var deviceRef: com.google.firebase.database.DatabaseReference? = null
    private var lastFirebaseHeartbeat = 0L
    private var serverTimeOffset = 0L
    private var commandStartTime = 0L
    private var hardwareStartSeen = false
    private var serverStartAcked = false
    private val COMMAND_TIMEOUT = ChargeSessionLogic.COMMAND_TIMEOUT_MS
    private var offsetListener: ValueEventListener? = null
    private var offsetRef: com.google.firebase.database.DatabaseReference? = null

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
    private var btnSetupFirstDevice: Button? = null
    private var mainContent: View? = null
    private var emptyState: View? = null
    private var txtBleStatus: TextView? = null
    private var txtBtOffBanner: TextView? = null
    private var modeCard: View? = null
    private var chargingChartCard: View? = null
    private var chargingChart: ChargingChartView? = null

    private var pairingDialog: androidx.appcompat.app.AlertDialog? = null
    private var actionDialog: androidx.appcompat.app.AlertDialog? = null
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

    private var connectionCard: View? = null
    private var txtConnectionTitle: TextView? = null
    private var txtConnectionIcon: TextView? = null
    private var btnConnect: MaterialButton? = null

    /** Latest cloud snapshot, fed to the reconciler as corroborating evidence. */
    private var cloudTransactionId: String? = null
    private var cloudRawState: Int = DeviceState.STATE_AVAILABLE
    /** Login that Firebase says started the live charge on this device. */
    private var cloudOwnerAccount: String? = null

    /** Avoid re-showing the "charger in use" dialog for the same foreign tid. */
    private var occupiedPromptFor: String? = null
    private var sessionEndShownFor: String? = null
    /** Avoid toasting reclaim for the same tid repeatedly. */
    private var reclaimedPromptFor: String? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    Toast.makeText(context, "Bluetooth is off — turn it on to connect", Toast.LENGTH_SHORT).show()
                    updateBleStatus(GatewayManager.bleTransport.connectionState.value)
                }
                BluetoothAdapter.STATE_ON -> {
                    // Only reconnects if this phone owns a running session.
                    // Reconnecting on Bluetooth-on used to occupy any charger in
                    // range, hiding it from other drivers.
                    if (SessionLeaseStore.hasOpenLease) {
                        Toast.makeText(
                            context,
                            "Bluetooth on — reconnecting to your charging session…",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    resumeOwnedSession()
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
                    resumeOwnedSession()
                } else {
                    pairAndConnect(address, pendingPairDeviceId, pendingPairName)
                }
            }
        } else {
            showActionDialog(
                title = "Bluetooth permission needed",
                message = "PowerTap needs Bluetooth permission to pair with your charger. Grant it, then try again.",
                positiveText = "Try again",
                onPositive = { requestBlePermissions() },
            )
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
        btnSetupFirstDevice = view.findViewById(R.id.btnSetupFirstDevice)
        mainContent = view.findViewById(R.id.mainContent)
        emptyState = view.findViewById(R.id.emptyState)
        txtBleStatus = view.findViewById(R.id.txtBleStatus)
        txtBtOffBanner = view.findViewById(R.id.txtBtOffBanner)
        connectionCard = view.findViewById(R.id.connectionCard)
        txtConnectionTitle = view.findViewById(R.id.txtConnectionTitle)
        txtConnectionIcon = view.findViewById(R.id.txtConnectionIcon)
        btnConnect = view.findViewById(R.id.btnConnect)
        modeCard = view.findViewById(R.id.modeCard)
        chargingChartCard = view.findViewById(R.id.chargingChartCard)
        chargingChart = view.findViewById(R.id.chargingChart)

        txtBtOffBanner?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }

        setupDeviceSelector()
        updateBleStatus(GatewayManager.bleTransport.connectionState.value)

        btnAddDevice?.setOnClickListener { showAddDeviceMenu(it) }
        btnSetupFirstDevice?.setOnClickListener { showAddDeviceMenu(it) }
        btnConnect?.setOnClickListener { onConnectButtonTapped() }

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

        sliderButton?.onSlideRight = { requestStartCharging() }
        sliderButton?.onSlideLeft = { requestStopCharging() }
        sliderButton?.onBlockedTap = { onSliderBlockedTap() }

        // Setup listener initially
        setupFirebaseListener()

        // Watch GatewayManager for real-time MeterData
        viewLifecycleOwner.lifecycleScope.launch {
            GatewayManager.latestMeterData.collect { data ->
                data?.let {
                    updateLCD(it)
                    if (isChargingUi(currentState)) chargingChart?.addSample(it)
                }
            }
        }

        // Command timeout + charging duration clock. Full slider rebuild is
        // event-driven; doing it every second was a major source of jank.
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(1000)

                if (ChargeSessionLogic.isCommandTimedOut(commandStartTime, System.currentTimeMillis(), COMMAND_TIMEOUT)) {
                    activity?.runOnUiThread {
                        val previous = currentState
                        if (previous == DeviceState.STATE_STARTING) {
                            handleStartTimeout()
                        } else if (previous == DeviceState.STATE_STOPPING) {
                            currentState = ChargeSessionLogic.stateAfterCommandTimeout(currentState)
                            commandStartTime = 0
                            updateOnlineStatus()
                            // No "reset" option here any more. Clearing a session
                            // the charger has not confirmed stopped is exactly
                            // how sessions were left dangling.
                            showActionDialog(
                                title = "Stop didn’t confirm",
                                message = "The charger hasn’t confirmed the stop yet. Charging may " +
                                    "still be running.\n\nStay close to ${lastDeviceDisplayName()} " +
                                    "and try again — we’ll keep checking in the background.",
                                positiveText = "Try again",
                                onPositive = { requestStopCharging() },
                            )
                        } else {
                            commandStartTime = 0
                            updateOnlineStatus()
                        }
                    }
                }

                if (ChargeSessionLogic.isChargingUi(currentState)) {
                    GatewayManager.latestMeterData.value?.let { updateLCD(it) }
                }
                reconcileSession()
            }
        }

        // Also watch GatewayManager's online status directly for immediate UI reaction
        viewLifecycleOwner.lifecycleScope.launch {
            GatewayManager.isDeviceOnline.collect {
                updateOnlineStatus()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            GatewayManager.isReconnecting.collect {
                updateBleStatus(GatewayManager.bleTransport.connectionState.value)
            }
        }

        // The charger is the authority on which session is running. Every new
        // piece of evidence is re-reconciled against the lease we hold.
        viewLifecycleOwner.lifecycleScope.launch {
            GatewayManager.hardwareSession.collect { reconcileSession() }
        }

        // Keeps the connection card and lease-derived UI in step with the lease.
        viewLifecycleOwner.lifecycleScope.launch {
            SessionLeaseStore.lease.collect {
                updateBleStatus(GatewayManager.bleTransport.connectionState.value)
            }
        }

        // Explain the courtesy release rather than letting the link vanish.
        viewLifecycleOwner.lifecycleScope.launch {
            GatewayManager.idleReleased.collect { at ->
                if (at == 0L) return@collect
                GatewayManager.acknowledgeIdleRelease()
                activity?.runOnUiThread {
                    showActionDialog(
                        title = "Disconnected to free the charger",
                        message = "You weren't charging, so we disconnected you from ${lastDeviceDisplayName()}. " +
                            "Tap Connect when you're ready to charge again.",
                    )
                }
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
                        setPairingMessage("Found PowerTap. Pairing now…")
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

        // BLE ACKs are UX only. A billed session is committed when Firebase monitor state changes.
        viewLifecycleOwner.lifecycleScope.launch {
            GatewayManager.commandAck.collect { ack ->
                ack ?: return@collect
                if (ack.timestamp == lastHandledAckTs) return@collect
                lastHandledAckTs = ack.timestamp
                activity?.runOnUiThread {
                    // A rejection that names the live session repairs itself, so
                    // it must not also surface as a failure the user has to act
                    // on. Handled before the per-action branches because it
                    // applies to both.
                    if (onChargerNamedActiveSession(ack)) return@runOnUiThread
                    when (ack.action) {
                        "RemoteStart" -> {
                            if (ack.accepted) {
                                if (currentState == DeviceState.STATE_STARTING) {
                                    startProgressMessage = "Buffering…"
                                    updateOnlineStatus()
                                }
                            } else if (currentState == DeviceState.STATE_STARTING) {
                                currentState = DeviceState.STATE_AVAILABLE
                                chargingUiStartedAt = 0L
                                commandStartTime = 0
                                updateOnlineStatus()
                                showActionDialog(
                                    title = "Charger rejected start",
                                    message = "The PowerTap did not accept the start command (${ack.status}). Check the cable and try again.",
                                    positiveText = "Try again",
                                    onPositive = { requestStartCharging() },
                                )
                            }
                        }
                        "RemoteStop" -> {
                            if (ack.accepted) {
                                if (currentState == DeviceState.STATE_STOPPING) {
                                    startProgressMessage = "Confirming stop…"
                                    updateOnlineStatus()
                                }
                            } else if (currentState == DeviceState.STATE_STOPPING) {
                                currentState = DeviceState.STATE_CHARGING
                                commandStartTime = 0
                                updateOnlineStatus()
                                showActionDialog(
                                    title = "Charger rejected stop",
                                    message = "The PowerTap did not accept the stop command (${ack.status}). Charging may still be running.",
                                    positiveText = "Try again",
                                    onPositive = { requestStopCharging() },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Hardware packets only advance the buffer copy. Orange/green wait for Firebase.
        viewLifecycleOwner.lifecycleScope.launch {
            GatewayManager.bridgeDetectedState.collect { newState ->
                newState ?: return@collect
                activity?.runOnUiThread {
                    when (newState) {
                        DeviceState.STATE_CHARGING, DeviceState.STATE_STARTED -> {
                            if (currentState == DeviceState.STATE_STARTING) {
                                hardwareStartSeen = true
                                startProgressMessage = "Confirming session…"
                                updateOnlineStatus()
                            }
                        }
                        DeviceState.STATE_AVAILABLE, DeviceState.STATE_STOPPED -> {
                            if (commandStartTime > 0L && currentState == DeviceState.STATE_STOPPING) {
                                startProgressMessage = "Confirming stop…"
                                updateOnlineStatus()
                            } else {
                                // Ending the session is the reconciler's call, so
                                // that hardware evidence is weighed against the
                                // lease in one place.
                                reconcileSession()
                            }
                        }
                    }
                    GatewayManager.latestMeterData.value?.let { data -> updateLCD(data) }
                }
            }
        }

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        statusListener?.let { deviceRef?.removeEventListener(it) }
        offsetListener?.let { offsetRef?.removeEventListener(it) }
        statusListener = null
        offsetListener = null
        offsetRef = null
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
        btnSetupFirstDevice = null
        mainContent = null
        emptyState = null
        txtBleStatus = null
        txtBtOffBanner = null
        connectionCard = null
        txtConnectionTitle = null
        txtConnectionIcon = null
        btnConnect = null
        modeCard = null
        chargingChartCard = null
        chargingChart = null
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        pairingDialog?.dismiss()
        pairingDialog = null
        pairingMessageView = null
        actionDialog?.dismiss()
        actionDialog = null
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
        resumeOwnedSession()
        reconcileSession()
        updateBleStatus(GatewayManager.bleTransport.connectionState.value)
    }

    override fun onStop() {
        try {
            context?.unregisterReceiver(bluetoothReceiver)
        } catch (_: IllegalArgumentException) { }
        super.onStop()
    }

    private fun isChargingUi(state: Int): Boolean = ChargeSessionLogic.isChargingUi(state)

    /**
     * Blocking failures stay on screen until the user chooses Cancel or Try again.
     * Short status updates (connected / pairing) still use toasts.
     */
    private fun showActionDialog(
        title: String,
        message: String,
        positiveText: String = "Try again",
        negativeText: String = "Cancel",
        onPositive: (() -> Unit)? = null,
        neutralText: String? = null,
        onNeutral: (() -> Unit)? = null,
    ) {
        val ctx = context ?: return
        if (!isAdded) return
        actionDialog?.dismiss()
        val builder = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(true)
        if (onPositive != null) {
            builder.setNegativeButton(negativeText, null)
            builder.setPositiveButton(positiveText) { _, _ -> onPositive() }
        } else {
            builder.setPositiveButton("OK", null)
        }
        if (neutralText != null && onNeutral != null) {
            builder.setNeutralButton(neutralText) { _, _ -> onNeutral() }
        }
        actionDialog = builder.show()
    }

    /**
     * Compare the session we own against what the charger reports, and act on the
     * difference. Runs every second and on every new charger packet.
     */
    private fun reconcileSession() {
        val now = System.currentTimeMillis()
        val cloud = CloudSession(
            transactionId = cloudTransactionId,
            state = cloudRawState,
            heartbeatFresh = ChargeSessionLogic.isHeartbeatFresh(
                lastFirebaseHeartbeat,
                now + serverTimeOffset,
            ),
            ownerAccount = cloudOwnerAccount,
        )
        val outcome = SessionReconciler.reconcile(
            lease = SessionLeaseStore.lease.value,
            hardware = GatewayManager.hardwareSession.value,
            cloud = cloud,
            nowMs = now,
            currentAccount = FirebaseApiManager.accountId,
        )

        when (outcome) {
            is Reconciliation.Confirmed -> {
                SessionLeaseStore.confirm(outcome.transactionId, now)
                occupiedPromptFor = null
                sessionEndShownFor = null
                reclaimedPromptFor = null
                if (!isChargingUi(currentState)) {
                    currentState = DeviceState.STATE_CHARGING
                    if (chargingUiStartedAt == 0L) chargingUiStartedAt = now
                    updateOnlineStatus()
                }
            }

            is Reconciliation.Reclaim -> reclaimOwnedSession(outcome.transactionId)

            is Reconciliation.Occupied -> handleOccupiedSession(outcome)

            is Reconciliation.Ended -> finishSession(outcome.transactionId)

            is Reconciliation.Expired -> promptExpiredSession(outcome.transactionId)

            // Deliberately does nothing. Not knowing what the charger is doing is
            // not evidence that the session ended, and clearing it here is what
            // used to leave sessions dangling on the charger.
            Reconciliation.HoldUnknown -> Unit

            Reconciliation.Idle -> {
                occupiedPromptFor = null
                reclaimedPromptFor = null
                if (isChargingUi(currentState) && commandStartTime == 0L) {
                    currentState = DeviceState.STATE_AVAILABLE
                    chargingUiStartedAt = 0L
                    updateOnlineStatus()
                }
            }
        }
    }

    /**
     * This Firebase account owns the live charge, but the phone lost its local
     * lease (reinstall / cleared data). Rebuild the lease and resume Stop/BLE.
     */
    private fun reclaimOwnedSession(transactionId: String) {
        val ctx = context ?: return
        val existing = SessionLeaseStore.open
        if (existing?.transactionId == transactionId) {
            SessionLeaseStore.confirm(transactionId)
            return
        }
        val address = GatewayManager.bleTransport.connectedAddress.value
            ?: BlePrefs.getLastDeviceAddress(ctx)
            ?: ""
        SessionLeaseStore.adopt(transactionId, deviceId, address)
        FirebaseApiManager.claimSessionOwnership(deviceId, transactionId)
        currentState = DeviceState.STATE_CHARGING
        commandStartTime = 0
        if (chargingUiStartedAt == 0L) chargingUiStartedAt = System.currentTimeMillis()
        occupiedPromptFor = null
        sessionEndShownFor = null
        updateOnlineStatus()
        resumeOwnedSession()
        if (reclaimedPromptFor != transactionId) {
            reclaimedPromptFor = transactionId
            Toast.makeText(
                context,
                "Restored your charging session — you can stop it from this phone",
                Toast.LENGTH_LONG,
            ).show()
            LogRepository.append(
                "Home: reclaimed tid=$transactionId for account=${FirebaseApiManager.accountId}",
            )
        }
    }

    /**
     * Charger is busy with a session this phone does not own.
     *
     * Drop any stale local lease so we do not offer Stop for the wrong id, and
     * tell the user the PowerTap is in use — without a take-control option.
     */
    private fun handleOccupiedSession(outcome: Reconciliation.Occupied) {
        if (outcome.ourStaleTransactionId != null) {
            TransactionRepository.markSuperseded(outcome.ourStaleTransactionId)
            SessionLeaseStore.release()
            LogRepository.append(
                "Home: charger runs ${outcome.transactionId}, not ${outcome.ourStaleTransactionId} — " +
                    "dropping stale lease (no take-over)",
            )
        }
        if (isChargingUi(currentState) && commandStartTime == 0L) {
            currentState = DeviceState.STATE_AVAILABLE
            chargingUiStartedAt = 0L
        }
        updateOnlineStatus()
        promptOccupiedSession(outcome.transactionId)
    }

    /**
     * Bind our lease to the tid the server assigned for *our* Start.
     * Never used to claim a session another phone started.
     */
    private fun bindStartedSession(transactionId: String, previousProposed: String?) {
        val ctx = context ?: return
        val address = GatewayManager.bleTransport.connectedAddress.value
            ?: BlePrefs.getLastDeviceAddress(ctx)
            ?: ""
        if (previousProposed != null && previousProposed != transactionId) {
            TransactionRepository.markSuperseded(previousProposed)
            LogRepository.append(
                "Home: start ack renamed tid $previousProposed → $transactionId",
            )
        }
        SessionLeaseStore.adopt(transactionId, deviceId, address)
        FirebaseApiManager.claimSessionOwnership(deviceId, transactionId)
        currentState = DeviceState.STATE_CHARGING
        commandStartTime = 0
        if (chargingUiStartedAt == 0L) chargingUiStartedAt = System.currentTimeMillis()
        sessionEndShownFor = null
        updateOnlineStatus()
    }

    private fun promptOccupiedSession(transactionId: String) {
        if (occupiedPromptFor == transactionId) return
        occupiedPromptFor = transactionId
        val started = TransactionRepository.sessions.value
            .firstOrNull { it.transactionId == transactionId }?.startTime
        val since = started?.let {
            " It started ${MeterUnits.formatDuration(System.currentTimeMillis() - it)} ago."
        } ?: ""
        val owner = cloudOwnerAccount?.takeIf { it.isNotBlank() && it != "guest" }
        val who = if (owner != null) {
            "Only the account that started it ($owner) can stop it. Sign in with that account on any phone to resume control."
        } else {
            "Only the account that started it can stop it. Sign in with that account to resume control."
        }
        showActionDialog(
            title = "This PowerTap is already charging",
            message = "A charging session is already running on ${lastDeviceDisplayName()}.$since\n\n$who",
        )
    }

    private fun promptExpiredSession(transactionId: String) {
        if (occupiedPromptFor == transactionId) return
        occupiedPromptFor = transactionId
        showActionDialog(
            title = "Session can't be confirmed",
            message = "We haven't been able to reach ${lastDeviceDisplayName()} for a long time, " +
                "so we can't tell whether this session is still running.\n\n" +
                "Clear it only if you're sure the charger has stopped.",
            positiveText = "Clear session",
            onPositive = { finishSession(transactionId, chargerConfirmed = false) },
            negativeText = "Keep waiting",
        )
    }

    /**
     * Close out a session and hand the charger back.
     *
     * [chargerConfirmed] is false only when the user cleared an unconfirmable
     * session. The cloud node is cleared solely on confirmed ends, so we never
     * erase the record of a session that might still be live.
     */
    private fun finishSession(transactionId: String, chargerConfirmed: Boolean = true) {
        val lease = SessionLeaseStore.lease.value
        val neverStarted = lease?.state == LeaseState.Requested
        SessionLeaseStore.release()

        currentState = DeviceState.STATE_AVAILABLE
        chargingUiStartedAt = 0L
        commandStartTime = 0
        hardwareStartSeen = false
        serverStartAcked = false
        occupiedPromptFor = null
        reclaimedPromptFor = null
        updateOnlineStatus()

        if (chargerConfirmed && deviceId.isNotBlank()) {
            FirebaseApiManager.markSessionIdle(deviceId)
        }

        // Give the charger back so it starts advertising to other drivers again.
        if (BleConnectionPolicy.shouldReleaseAfterSession(
                hasOpenLease = false,
                state = GatewayManager.bleTransport.connectionState.value,
            )
        ) {
            GatewayManager.releaseLink("session $transactionId finished")
        }

        if (sessionEndShownFor == transactionId) return
        sessionEndShownFor = transactionId

        if (neverStarted) {
            showActionDialog(
                title = "Charging didn't start",
                message = "${lastDeviceDisplayName()} never began a session. Check the cable is " +
                    "connected, then tap Connect and try again.",
                positiveText = "Try again",
                onPositive = { onConnectButtonTapped() },
            )
            return
        }

        showActionDialog(
            title = "Charging finished",
            message = buildString {
                append(sessionSummary(transactionId))
                append("\n\nWe've disconnected from ${lastDeviceDisplayName()} so another driver can use it. ")
                append("Tap Connect whenever you want to charge again.")
            },
        )
    }

    private fun sessionSummary(transactionId: String): String {
        val session = TransactionRepository.sessions.value
            .firstOrNull { it.transactionId == transactionId } ?: return "Your session has ended."
        val energy = MeterUnits.formatEnergyWh(session.energyConsumed)
        val stop = session.stopTime ?: System.currentTimeMillis()
        val duration = MeterUnits.formatDuration(stop - session.startTime)
        return "You used $energy in $duration."
    }

    private fun requestStartCharging() {
        val sb = sliderButton ?: return
        if (sb.isLocked) return
        GatewayManager.noteUserAction()

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

        // Connecting is now deliberate, so starting a charge without a link is a
        // real state the user can reach. Send them to the fix.
        if (BleConnectionPolicy.needsConnectBeforeCharging(
                GatewayManager.bleTransport.connectionState.value,
            )
        ) {
            sb.setState(true)
            showActionDialog(
                title = "Connect first",
                message = "You need to connect to ${lastDeviceDisplayName()} before charging. " +
                    "Keep the charger switched on and stand close to it.",
                positiveText = "Connect",
                onPositive = { onConnectButtonTapped() },
            )
            return
        }

        // Someone else's session is live — do not start on top of it, and do not
        // offer take-control. Only the phone with the matching lease may stop it.
        (GatewayManager.hardwareSession.value as? HardwareSession.Charging)?.let { running ->
            if (running.transactionId.isNotBlank() &&
                !ChargeSessionLogic.ownsRunningSession(transactionId, running.transactionId)
            ) {
                sb.setState(true)
                promptOccupiedSession(running.transactionId)
                return
            }
        }

        if (!GatewayManager.isDeviceOnline.value && lastFirebaseHeartbeat == 0L) {
            showActionDialog(
                title = "Device looks offline",
                message = "The charger may not receive this command. Move closer, then try again.",
                positiveText = "Try anyway",
                onPositive = { sendStartCommand(mode, value) },
            )
            return
        }

        sendStartCommand(mode, value)
    }

    /**
     * The session is real. [tid] is a *candidate* id from the server ack; the
     * lease only follows it when the charger is running that id too, because the
     * charger can only ever confirm the id we put in RemoteStart.
     */
    private fun commitBilledCharging(candidateTid: String) {
        val owned = SessionLeaseStore.open
        val tid = ChargeSessionLogic.leaseTransactionIdAfterAck(
            proposedTid = owned?.transactionId ?: candidateTid,
            serverTid = candidateTid,
            hardwareTid = (GatewayManager.hardwareSession.value as? HardwareSession.Charging)
                ?.transactionId,
        )
        if (owned != null && owned.transactionId != tid) {
            bindStartedSession(tid, previousProposed = owned.transactionId)
        } else {
            SessionLeaseStore.confirm(tid)
            FirebaseApiManager.claimSessionOwnership(deviceId, tid)
            currentState = DeviceState.STATE_CHARGING
            commandStartTime = 0
            if (chargingUiStartedAt == 0L) chargingUiStartedAt = System.currentTimeMillis()
            sessionEndShownFor = null
            updateOnlineStatus()
        }
        LogRepository.append("Home: billed session confirmed tid=$tid account=${FirebaseApiManager.accountId}")
    }

    private fun handleStartTimeout() {
        commandStartTime = 0
        if (!ChargeSessionLogic.startTimeoutShouldRevert(hardwareStartSeen, serverStartAcked)) {
            if (serverStartAcked || hardwareStartSeen) {
                transactionId?.let { commitBilledCharging(it) }
            }
            return
        }
        // Do not release the lease here: the charger may simply be unreachable.
        // The reconciler ends it once the charger positively reports idle.
        currentState = DeviceState.STATE_AVAILABLE
        chargingUiStartedAt = 0L
        hardwareStartSeen = false
        serverStartAcked = false
        updateOnlineStatus()
        showActionDialog(
            title = "Start didn’t confirm",
            message = "The charger hasn’t confirmed a session yet. Check the cable and that " +
                "${lastDeviceDisplayName()} is switched on, then try again.",
            positiveText = "Try again",
            onPositive = { requestStartCharging() },
        )
    }

    private fun sendStartCommand(mode: String, value: Int?) {
        val sb = sliderButton ?: return
        val ctx = context ?: return
        val tid = "T${System.currentTimeMillis()}"
        val msgId = System.currentTimeMillis().toString()

        // Claim the session *before* sending. The lease is what authorises a
        // reconnect, so if the link drops between here and the charger accepting,
        // we can still get back to it and stop it.
        val address = GatewayManager.bleTransport.connectedAddress.value
            ?: BlePrefs.getLastDeviceAddress(ctx)
            ?: ""
        SessionLeaseStore.request(tid, deviceId, address, mode)
        sessionEndShownFor = null
        occupiedPromptFor = null

        val ocppStart = buildString {
            append("[2,\"$msgId\",\"RemoteStart\",{\"mode\":\"$mode\",\"tid\":\"$tid\"")
            if (mode == "time") append(",\"time\":$time")
            if (mode == "units") append(",\"units\":$units")
            append("}]")
        }
        val bleOk = GatewayManager.sendToCharger(ocppStart)
        if (!bleOk) {
            showActionDialog(
                title = "Bluetooth isn’t ready",
                message = "Start was sent via the cloud. The app is reconnecting. Charging may take a moment to begin.",
            )
        }

        currentState = DeviceState.STATE_STARTING
        commandStartTime = System.currentTimeMillis()
        hardwareStartSeen = false
        serverStartAcked = false
        startProgressMessage = "Starting..."
        updateOnlineStatus()

        sb.showProgress(startProgressMessage)
        FirebaseApiManager.startCharging(deviceId, mode, value, tid,
            onResult = {
                LogRepository.append("Firebase: Start Charging Ack: $it")
                activity?.runOnUiThread {
                    if (currentState != DeviceState.STATE_STARTING) return@runOnUiThread
                    serverStartAcked = true
                    val serverTid = ChargeSessionLogic.transactionIdFromServerAck(it) ?: tid
                    commitBilledCharging(serverTid)
                }
            },
            onError = {
                LogRepository.append("Firebase: Start Charging Error: $it")
                activity?.runOnUiThread {
                    if (currentState == DeviceState.STATE_STARTING) {
                        currentState = DeviceState.STATE_AVAILABLE
                        commandStartTime = 0
                        updateOnlineStatus()
                        sb.hideProgress()
                        showActionDialog(
                            title = "Couldn’t start charging",
                            message = it,
                            positiveText = "Try again",
                            onPositive = { requestStartCharging() },
                        )
                    }
                }
            }
        )
    }

    private fun requestStopCharging() {
        val sb = sliderButton ?: return
        if (sb.isLocked) return
        GatewayManager.noteUserAction()
        performStopCharging()
    }

    /**
     * Sends the stop, without the in-flight guard [requestStopCharging] applies.
     *
     * Split out so an automatic retry can reuse it: a retry only ever happens
     * while a stop is already in flight, which is precisely when the slider is
     * locked and the user-facing entry point refuses to run.
     */
    private fun performStopCharging() {
        val sb = sliderButton ?: return

        // Ownership is the account that started the charge. Local lease is the
        // cache; if it is missing but Firebase says this login owns the live tid,
        // reclaim first so Stop still works after reinstall.
        var leaseTid = transactionId
        val running = (GatewayManager.hardwareSession.value as? HardwareSession.Charging)
            ?.transactionId
            ?.takeIf { it.isNotBlank() }

        if (leaseTid.isNullOrEmpty() &&
            running != null &&
            ChargeSessionLogic.accountOwnsLiveSession(
                currentAccount = FirebaseApiManager.accountId,
                ownerAccount = cloudOwnerAccount,
                cloudState = cloudRawState,
            )
        ) {
            reclaimOwnedSession(running)
            leaseTid = transactionId
        }

        if (leaseTid.isNullOrEmpty()) {
            LogRepository.append("Home: Stop refused — no owned session lease")
            sb.setState(true)
            if (running != null) {
                promptOccupiedSession(running)
            } else {
                showActionDialog(
                    title = "No session to stop",
                    message = "This account didn't start the current charge on ${lastDeviceDisplayName()}, " +
                        "so it can't stop it. Sign in with the account that started charging.",
                )
            }
            return
        }

        if (running != null &&
            !ChargeSessionLogic.ownsRunningSession(leaseTid, running)
        ) {
            // Firmware may have overwritten strTID after our Start. If this
            // login still owns the charge, bind to the live id and stop that —
            // do not Occupied-drop the lease, which is what stranded Stop and
            // auto-reconnect after a walk-away.
            if (ChargeSessionLogic.accountOwnsLiveSession(
                    FirebaseApiManager.accountId,
                    cloudOwnerAccount,
                    cloudRawState,
                )
            ) {
                LogRepository.append(
                    "Home: Stop retargeting lease $leaseTid → live $running (account owns session)",
                )
                reclaimOwnedSession(running)
                leaseTid = transactionId ?: running
            } else {
                LogRepository.append("Home: Stop refused — lease $leaseTid ≠ live $running")
                sb.setState(true)
                handleOccupiedSession(
                    Reconciliation.Occupied(running, ourStaleTransactionId = leaseTid),
                )
                return
            }
        }

        val tid = ChargeSessionLogic.stopTargetTransactionId(leaseTid, running)
        SessionLeaseStore.markStopping()

        val ocppStop = "[2,\"${System.currentTimeMillis()}\",\"RemoteStop\",{\"tid\":\"$tid\"}]"
        val bleOk = GatewayManager.sendToCharger(ocppStop)
        if (!bleOk) {
            showActionDialog(
                title = "Bluetooth dropped",
                message = "Charging continues on the charger. Stop is queued and will be sent when Bluetooth reconnects.",
            )
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
                    if (currentState == DeviceState.STATE_STOPPING) {
                        startProgressMessage = "Confirming stop…"
                        updateOnlineStatus()
                    }
                }
            },
            onError = {
                LogRepository.append("Firebase: Stop Charging Error: $it")
                activity?.runOnUiThread {
                    if (currentState == DeviceState.STATE_STOPPING) {
                        currentState = DeviceState.STATE_CHARGING
                        commandStartTime = 0
                        updateOnlineStatus()
                        sb.hideProgress()
                        showActionDialog(
                            title = "Couldn’t stop charging",
                            message = it,
                            positiveText = "Try again",
                            onPositive = { requestStopCharging() },
                        )
                    }
                }
            }
        )
    }

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
     * Reconnect on app open or when Bluetooth returns — when this login still
     * holds a local lease, or after [reclaimOwnedSession] rebuilt one from
     * Firebase account ownership.
     *
     * The lease's transaction id is matched against the charger before Stop is
     * offered. A different account never auto-connects into someone else's charge.
     */
    private fun resumeOwnedSession() {
        val ctx = context ?: return
        val lease = SessionLeaseStore.open ?: return
        // Coming back to the app is the "when you come back" that Home promised
        // when they disconnected mid-charge.
        GatewayManager.rearmSessionResume()
        if (GatewayManager.userRequestedDisconnect) return
        if (!isBluetoothEnabled()) {
            updateBleStatus(GatewayManager.bleTransport.connectionState.value)
            return
        }
        if (!hasBlePermissions(ctx)) {
            if (blePermissionAsked) return
            blePermissionAsked = true
            silentAutoConnect = true
            pendingPairAddress = lease.bleAddress.takeIf { it.isNotBlank() }
                ?: BlePrefs.getLastDeviceAddress(ctx)
            pendingPairDeviceId = lease.deviceId
            pendingPairName = lastDeviceDisplayName()
            blePermissionLauncher.launch(blePermissions())
            return
        }
        GatewayManager.resumeSessionLink()
    }

    /** The one action on the connection card: connect, or hand the charger back. */
    private fun onConnectButtonTapped() {
        val ctx = context ?: return
        GatewayManager.noteUserAction()
        when (GatewayManager.bleTransport.connectionState.value) {
            ConnectionState.Connected -> {
                requestUserDisconnect()
                return
            }
            ConnectionState.Scanning, ConnectionState.Connecting -> {
                // Cancel belongs to a pairing the user started. A session-resume
                // attempt is retried instead — abandoning it is how people ended
                // up with no route back to a charge only this phone can stop.
                if (isPairingFromHome) {
                    cancelPairing()
                    return
                }
            }
            else -> Unit
        }

        // An open lease pins the target: reconnect to the charger running our
        // session, never to whatever the device dropdown happens to show.
        if (SessionLeaseStore.hasOpenLease) {
            GatewayManager.rearmSessionResume()
            if (GatewayManager.retrySessionLinkNow()) {
                Toast.makeText(
                    ctx,
                    "Reconnecting to ${lastDeviceDisplayName()}…",
                    Toast.LENGTH_SHORT,
                ).show()
                updateBleStatus(GatewayManager.bleTransport.connectionState.value)
                return
            }
        }

        val known = BlePrefs.getKnownDevices(ctx)
        if (known.isEmpty()) {
            showAddDeviceMenu(btnConnect ?: return)
            return
        }
        val selectedId = MqttPrefs.loadDeviceId(ctx)
        val index = known.indexOfFirst {
            DeviceIdentity.sameDevice(it.second, selectedId) ||
                DeviceIdentity.cleanHex(it.second) == DeviceIdentity.cleanHex(selectedId)
        }
        val target = known.getOrNull(index) ?: known.first()
        val resolvedIndex = if (index >= 0) index else 0
        val resolvedId = DeviceIdentity.deviceIdFromBle(target.second)
            ?: DeviceIdentity.cleanHex(target.second)
            ?: target.second
        pairAndConnect(
            target.second,
            resolvedId,
            target.first?.takeIf { it.isNotBlank() } ?: "PowerTap ${resolvedIndex + 1}",
        )
    }

    private fun requestUserDisconnect() {
        val charging = SessionLeaseStore.hasOpenLease
        if (charging) {
            // Walking away mid-charge means losing the ability to stop, so make
            // that explicit rather than silently dropping the link.
            showActionDialog(
                title = "Disconnect while charging?",
                message = "Charging will continue on ${lastDeviceDisplayName()}, but you'll need to " +
                    "reconnect to stop it. We'll reconnect automatically when you come back.",
                positiveText = "Disconnect",
                onPositive = { doUserDisconnect(wasCharging = true) },
                negativeText = "Stay connected",
            )
            return
        }
        doUserDisconnect(wasCharging = false)
    }

    private fun doUserDisconnect(wasCharging: Boolean) {
        GatewayManager.markUserDisconnect()
        val msg = if (wasCharging) {
            "Disconnected. Charging continues on the charger."
        } else {
            "Disconnected. ${lastDeviceDisplayName()} is free for other drivers."
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        updateBleStatus(ConnectionState.Disconnected)
    }

    /** Tapping a greyed-out slider should offer the fix, not do nothing. */
    private fun onSliderBlockedTap() {
        val ctx = context ?: return
        if (!isBluetoothEnabled()) {
            showActionDialog(
                title = "Turn on Bluetooth",
                message = "Bluetooth is needed to talk to your PowerTap.",
                positiveText = "Settings",
                onPositive = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            )
            return
        }
        if (BlePrefs.getKnownDevices(ctx).isEmpty()) {
            showAddDeviceMenu(btnConnect ?: sliderButton ?: return)
            return
        }
        if (BleConnectionPolicy.needsConnectBeforeCharging(
                GatewayManager.bleTransport.connectionState.value,
            )
        ) {
            showActionDialog(
                title = "Connect first",
                message = "Connect to ${lastDeviceDisplayName()} before you can start charging. " +
                    "Keep the charger switched on and stay close to it.",
                positiveText = "Connect",
                onPositive = { onConnectButtonTapped() },
            )
            return
        }
        showActionDialog(
            title = "Charger not responding",
            message = "We're connected but ${lastDeviceDisplayName()} isn't sending updates yet. " +
                "Give it a moment, or reconnect.",
            positiveText = "Reconnect",
            onPositive = { onConnectButtonTapped() },
        )
    }

    /**
     * The charger rejected a command and named the transaction id it is really
     * running. We only continue if that id already matches our lease — strangers
     * must not adopt it.
     *
     * @return true when the rejection was absorbed, meaning the caller should not
     *         also report it as a failure.
     */
    private fun onChargerNamedActiveSession(ack: CommandAck): Boolean {
        val activeTid = ack.activeTid ?: return false
        val previous = SessionLeaseStore.open?.transactionId

        // Already ours: the charger is disagreeing about something else, so let
        // the normal rejection handling explain it.
        if (ChargeSessionLogic.ownsRunningSession(previous, activeTid)) return false

        // We started this charge and the charger is quoting a different id
        // (firmware overwrite). Adopt the live id so Stop and reconnect keep
        // working instead of treating it as a stranger's session.
        if (previous != null &&
            ChargeSessionLogic.accountOwnsLiveSession(
                FirebaseApiManager.accountId,
                cloudOwnerAccount,
                cloudRawState,
            )
        ) {
            LogRepository.append(
                "Home: charger named tid=$activeTid for our session $previous — adopting",
            )
            reclaimOwnedSession(activeTid)
            if (ack.action == "RemoteStop") {
                performStopCharging()
            }
            return true
        }

        // Foreign session — never claim it. Drop a mismatched local lease.
        if (previous != null) {
            handleOccupiedSession(
                Reconciliation.Occupied(activeTid, ourStaleTransactionId = previous),
            )
            return true
        }

        if (ack.action == "RemoteStart" && currentState == DeviceState.STATE_STARTING) {
            currentState = DeviceState.STATE_AVAILABLE
            chargingUiStartedAt = 0L
            commandStartTime = 0
            SessionLeaseStore.release()
            updateOnlineStatus()
            promptOccupiedSession(activeTid)
            return true
        }

        if (ack.action == "RemoteStop") {
            currentState = DeviceState.STATE_AVAILABLE
            commandStartTime = 0
            updateOnlineStatus()
            promptOccupiedSession(activeTid)
            return true
        }
        return false
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
            state == ConnectionState.Connected ->
                Toast.makeText(context, "Connected to $name", Toast.LENGTH_SHORT).show()
            state == ConnectionState.Disconnected &&
                (previous == ConnectionState.Connected || previous == ConnectionState.Connecting) ->
                Toast.makeText(context, "Disconnected from $name", Toast.LENGTH_SHORT).show()
            // While a session is resuming this fires on every failed attempt,
            // which is once per backoff for as long as the user is out of range.
            // The connection card already says the same thing without stacking
            // modals they cannot act on until they walk back.
            state == ConnectionState.Failed && !SessionLeaseStore.hasOpenLease ->
                showActionDialog(
                    title = "Couldn't connect",
                    message = "Make sure $name is switched on and you're standing close to it, " +
                        "then try again.",
                    positiveText = "Try again",
                    onPositive = { onConnectButtonTapped() },
                )
        }
    }

    private fun applySelectedTab(position: Int) {
        // Mode card is swapped out for the live chart while charging — don't
        // fight that. Gate on the card swap, not raw currentState: a stale
        // CHARGING snapshot used to leave every tab stuck on the Full Charge
        // template and hide the Set Time / Set Units controls.
        if (showingChargingCard) return
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

    /**
     * While a session is running, hide the Full Charge / Set Time / Set Units
     * card and show the live Charging Pattern chart. Restore the mode card
     * when the session ends — same swap as PowerTapApp's config panel.
     */
    private fun setChargingChartVisible(visible: Boolean) {
        if (visible == showingChargingCard) return
        showingChargingCard = visible
        if (visible) {
            modeCard?.visibility = View.GONE
            chargingChartCard?.visibility = View.VISIBLE
            chargingChart?.clear()
            val seedFromSession = currentState != DeviceState.STATE_STARTING
            val existing = if (seedFromSession) {
                GatewayManager.sessionMeterSamples(transactionId)
            } else {
                emptyList()
            }
            if (existing.isNotEmpty()) {
                chargingChart?.setSamples(existing)
            } else {
                GatewayManager.latestMeterData.value?.let { chargingChart?.addSample(it) }
            }
        } else {
            chargingChartCard?.visibility = View.GONE
            chargingChart?.clear()
            modeCard?.visibility = View.VISIBLE
            applySelectedTab(tabLayout?.selectedTabPosition ?: 0)
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

    private fun requestBlePermissions() {
        blePermissionLauncher.launch(blePermissions())
    }

    private fun hasBlePermissions(ctx: Context): Boolean {
        return blePermissions().all {
            ActivityCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isOsBonded(ctx: Context, bleAddress: String): Boolean {
        return try {
            val btManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager
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
            updateDeviceSelectorText(MqttPrefs.loadDeviceId(ctx))
            showActionDialog(
                title = "Turn on Bluetooth",
                message = "Bluetooth is required to pair with your PowerTap.",
                positiveText = "Settings",
                onPositive = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            )
            return
        }

        if (!hasBlePermissions(ctx)) {
            blePermissionLauncher.launch(blePermissions())
            return
        }

        // Android bonding interferes with first-time pairing through the app.
        // A charger we have already used (or that is running our session) must
        // still be reconnectable — blocking here is why "come back and tap
        // Connect" did nothing after a walk-away dropped the lease.
        val knownToApp = BlePrefs.isPaired(ctx, bleAddress) || SessionLeaseStore.hasOpenLease
        if (!knownToApp && isOsBonded(ctx, bleAddress)) {
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
            showActionDialog(
                title = "Turn on Bluetooth",
                message = "Bluetooth is required to pair with your PowerTap.",
                positiveText = "Settings",
                onPositive = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            )
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
                "The charger has to be powered on and nearby so the app can pair over Bluetooth. " +
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
            connectionCard?.visibility = View.GONE
            updateOnlineStatus()
            return
        }
        banner?.visibility = View.GONE
        connectionCard?.visibility = View.VISIBLE
        status.visibility = View.VISIBLE

        val hasSession = SessionLeaseStore.hasOpenLease

        // title / detail / icon / colour / button label. A null button label
        // hides the button, so there is never more than one thing to tap.
        val view: ConnectionCardView = when (state) {
            ConnectionState.Scanning -> ConnectionCardView(
                title = "Looking for $name…",
                detail = "Keep the charger switched on and stay close to it.",
                icon = "🔍",
                color = R.color.primary_blue,
                action = if (isPairingFromHome) "Cancel" else "Retry now",
            )
            ConnectionState.Connecting -> ConnectionCardView(
                title = "Connecting to $name…",
                detail = "Almost there — don't walk away yet.",
                icon = "🔄",
                color = R.color.primary_blue,
                action = if (isPairingFromHome) "Cancel" else "Retry now",
            )
            ConnectionState.Connected -> ConnectionCardView(
                title = "Connected to $name",
                detail = if (hasSession) {
                    "Charging now. Slide below to stop."
                } else {
                    "Ready to charge."
                },
                icon = "✅",
                color = R.color.status_success,
                action = "Disconnect",
            )
            ConnectionState.Failed -> ConnectionCardView(
                title = "Couldn't reach $name",
                detail = "Make sure it's switched on and you're standing close to it.",
                icon = "⚠️",
                color = R.color.status_error,
                action = "Try again",
            )
            ConnectionState.Disconnected -> when {
                GatewayManager.isReconnecting.value && hasSession -> ConnectionCardView(
                    title = "Reconnecting to your session…",
                    detail = "Charging continues on the charger. We need the link back to stop it.",
                    icon = "🔄",
                    color = R.color.status_warning,
                    // Never hide this while a session is live: the retry can only
                    // succeed once the user is back in range, and they need a way
                    // to say so rather than waiting out a backoff.
                    action = "Retry now",
                )
                hasSession -> ConnectionCardView(
                    title = "Session still running",
                    detail = "Reconnect to $name so you can stop charging.",
                    icon = "⚠️",
                    color = R.color.status_warning,
                    action = "Reconnect",
                )
                selected == null && known.isEmpty() -> ConnectionCardView(
                    title = "No PowerTap added yet",
                    detail = "Add your charger to get started.",
                    icon = "🔌",
                    color = R.color.text_secondary,
                    action = "Add",
                )
                paired -> ConnectionCardView(
                    title = "Not connected",
                    detail = "Connect to $name to start charging.",
                    icon = "🔌",
                    color = R.color.text_secondary,
                    action = "Connect",
                )
                else -> ConnectionCardView(
                    title = "Not connected",
                    detail = "Tap Connect to pair with $name. Keep it on and nearby.",
                    icon = "🔌",
                    color = R.color.status_warning,
                    action = "Connect",
                )
            }
        }

        txtConnectionTitle?.text = view.title
        txtConnectionTitle?.setTextColor(ctx.getColor(view.color))
        txtConnectionIcon?.text = view.icon
        status.text = view.detail
        btnConnect?.visibility = if (view.action == null) View.GONE else View.VISIBLE
        btnConnect?.text = view.action ?: ""
        updateOnlineStatus()
    }

    /** One row of connection copy: what's happening and the single way to change it. */
    private data class ConnectionCardView(
        val title: String,
        val detail: String,
        val icon: String,
        val color: Int,
        val action: String?,
    )

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
                    idInput.error = "Enter a valid 12-character device ID (or PowerTap_… name)"
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
        val sessionConfirmed = currentState == DeviceState.STATE_CHARGING ||
            currentState == DeviceState.STATE_STARTED
        
        val voltageStr = String.format(Locale.getDefault(), "%.1fV", data.voltage)
        val energyStr = MeterUnits.formatEnergyWh(data.energy)
        val dateFormat = SimpleDateFormat("dd MMM hh:mm a", Locale.getDefault())
        val dateStr = dateFormat.format(Date()).uppercase()

        if (charging) {
            val currentStr = String.format(Locale.getDefault(), "%.1fA", data.current)
            val powerStr = MeterUnits.formatPowerWatts(data.power)
            val durationLabel: String
            val durationStr: String
            if (sessionConfirmed) {
                val startedAt = chargingUiStartedAt.takeIf { it > 0 }
                    ?: commandStartTime.takeIf { it > 0 }
                    ?: System.currentTimeMillis()
                if (chargingUiStartedAt == 0L) chargingUiStartedAt = startedAt
                durationLabel = "DURATION"
                durationStr = MeterUnits.formatDuration(System.currentTimeMillis() - startedAt)
            } else {
                durationLabel = "STATUS"
                durationStr = if (currentState == DeviceState.STATE_STOPPING) "STOPPING" else "WAIT"
            }
            
            lcdView?.setText(
                listOf(
                    LCDSegment(voltageStr, 22f, Align.LEFT, 1f, true, "VOLTAGE"),
                    LCDSegment(dateStr, 14f, Align.CENTER, 1.5f, false, "TIME"),
                    LCDSegment(powerStr, 22f, Align.RIGHT, 1f, true, "POWER")
                ),
                listOf(
                    LCDSegment(energyStr, 18f, Align.LEFT, 1f, true, "ENERGY"),
                    LCDSegment(durationStr, 16f, Align.CENTER, 1.2f, true, durationLabel),
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
        val bleState = GatewayManager.bleTransport.connectionState.value
        val bleConnected = bleState == ConnectionState.Connected
        val isOnline = isFirebaseOnline || isGatewayOnline || bleConnected
        
        activity?.runOnUiThread {
            val sb = sliderButton ?: return@runOnUiThread
            val tl = tabLayout ?: return@runOnUiThread
            if (sliderSection == null) return@runOnUiThread

            val commandInFlight = commandStartTime > 0L
            val renderState = ChargeSessionLogic.sliderState(
                localState = currentState,
                hasOpenLease = SessionLeaseStore.hasOpenLease,
                hardwareCharging = GatewayManager.hardwareSession.value is HardwareSession.Charging,
                commandInFlight = commandInFlight,
            )

            val charging = isChargingUi(renderState)
            setChargingChartVisible(charging)

            // Idle tabs (Full Charge / Set Time / Set Units): always restore the
            // selected tab's own UI. Do not force the Full Charge template or hide
            // the time/units seekbar just because BLE is still disconnected —
            // users configure limits before connecting.
            fun restoreIdleModeCard() {
                applySelectedTab(tl.selectedTabPosition)
            }

            // A session *this phone owns* keeps Stop available even if the link
            // looks flaky. "Online" only matters when we want to *start*.
            if (ChargeSessionLogic.sliderLocked(renderState, commandInFlight)) {
                sb.activate(true)
                sb.showProgress(startProgressMessage)
            } else if (ChargeSessionLogic.treatAsCharging(renderState, commandInFlight)) {
                sb.activate(true)
                sb.hideProgress()
                sb.setState(false)
                sb.setText("Slide to Stop")
                if (renderState == DeviceState.STATE_CHARGING ||
                    renderState == DeviceState.STATE_STARTED
                ) {
                    commandStartTime = 0
                }
            } else if (BleConnectionPolicy.needsConnectBeforeCharging(bleState)) {
                // Charging needs the link, and the link is now always deliberate.
                // Say so on the control the user is reaching for.
                restoreIdleModeCard()
                sb.activate(false)
                sb.hideProgress()
                sb.setState(true)
                sb.setText(
                    when {
                        GatewayManager.isReconnecting.value -> "Reconnecting…"
                        bleState == ConnectionState.Scanning ||
                            bleState == ConnectionState.Connecting -> "Connecting…"
                        else -> "Connect to start charging"
                    },
                )
            } else if (isOnline) {
                when (renderState) {
                    DeviceState.STATE_AVAILABLE, DeviceState.STATE_STOPPED -> {
                        restoreIdleModeCard()
                        sb.activate(true)
                        sb.hideProgress()
                        sb.setState(true)
                        sb.setText(if (tl.selectedTabPosition == 0) "Slide to Start Charging" else "Slide to Confirm")
                    }
                    DeviceState.STATE_STARTING -> {
                        sb.activate(true)
                        sb.showProgress(startProgressMessage)
                    }
                    else -> {
                        sb.activate(true)
                        sb.setText("Status: $renderState")
                    }
                }
            } else {
                restoreIdleModeCard()
                val pairing = isPairingFromHome ||
                    bleState == ConnectionState.Scanning ||
                    bleState == ConnectionState.Connecting ||
                    GatewayManager.isReconnecting.value
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

    private fun setupFirebaseListener() {
        val ctx = context ?: return
        statusListener?.let { deviceRef?.removeEventListener(it) }
        offsetListener?.let { offsetRef?.removeEventListener(it) }

        deviceId = MqttPrefs.loadDeviceId(ctx).lowercase().trim()
        if (deviceId.isNotEmpty()) {
            val database = FirebaseDatabase.getInstance()
            val ref = database.getReference("PowerTapMonitor/$deviceId")
            deviceRef = ref
            val timeOffsetRef = database.getReference(".info/serverTimeOffset")
            offsetRef = timeOffsetRef
            offsetListener = timeOffsetRef.addValueEventListener(object : ValueEventListener {
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

                    val rawState = (snapshot.child("state").value as? Long)?.toInt()
                        ?: DeviceState.STATE_AVAILABLE

                    val heartbeatObj = snapshot.child("time").value
                    var hb = when (heartbeatObj) {
                        is Long -> heartbeatObj
                        is String -> heartbeatObj.toLongOrNull() ?: 0L
                        is Double -> heartbeatObj.toLong()
                        else -> 0L
                    }
                    if (hb in 1..1_999_999_999L) hb *= 1000
                    lastFirebaseHeartbeat = hb

                    val commandInFlight = commandStartTime > 0L &&
                        !ChargeSessionLogic.isCommandTimedOut(
                            commandStartTime,
                            System.currentTimeMillis(),
                            COMMAND_TIMEOUT,
                        )
                    val bleReady = ChargeSessionLogic.isBleReady(
                        GatewayManager.bleTransport.connectionState.value,
                    )
                    val now = System.currentTimeMillis() + serverTimeOffset
                    val fresh = ChargeSessionLogic.isHeartbeatFresh(hb, now)
                    val newState = ChargeSessionLogic.effectiveServerState(
                        rawState,
                        fresh,
                        bleReady,
                        commandInFlight,
                        hasOpenLease = SessionLeaseStore.hasOpenLease,
                    )
                    val accept = ChargeSessionLogic.shouldAcceptServerState(
                        currentState,
                        newState,
                        commandInFlight,
                    )

                    if (accept && currentState != newState) {
                        Log.d(TAG, "Server state transition: $currentState -> $newState (raw=$rawState fresh=$fresh)")
                        currentState = newState
                        if (newState == DeviceState.STATE_CHARGING || newState == DeviceState.STATE_STARTED) {
                            if (chargingUiStartedAt == 0L) chargingUiStartedAt = System.currentTimeMillis()
                            commandStartTime = 0
                        } else if (newState == DeviceState.STATE_AVAILABLE ||
                            newState == DeviceState.STATE_STOPPED
                        ) {
                            chargingUiStartedAt = 0L
                            commandStartTime = 0
                        }
                    }

                    // The cloud copy is evidence for the reconciler, not the
                    // answer. Writing it straight into our own session id is how
                    // the phone used to end up stopping the wrong session — or
                    // none at all, leaving one running on the charger.
                    cloudTransactionId = snapshot.child("transactionId").value as? String
                    cloudRawState = rawState
                    cloudOwnerAccount = (snapshot.child("ownerAccount").value as? String)
                        ?.takeIf { it.isNotBlank() }

                    reconcileSession()
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
