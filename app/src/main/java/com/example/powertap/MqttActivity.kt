package com.example.powertap

import android.graphics.Color
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.powertap.contract.ConnectionState
import com.example.powertap.contract.PtContract
import com.example.powertap.mqtt.MqttConfig
import com.example.powertap.mqtt.MqttPrefs
import com.example.powertap.mqtt.MqttTransport
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MqttActivity : AppCompatActivity() {

    private lateinit var transport: MqttTransport

    private lateinit var statusView: TextView
    private lateinit var connectButton: Button
    private lateinit var gatewayLogAdapter: ArrayAdapter<String>
    private lateinit var commandLogAdapter: ArrayAdapter<String>
    private lateinit var ackLogAdapter: ArrayAdapter<String>
    
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var userInput: EditText
    private lateinit var passInput: EditText
    private lateinit var deviceIdInput: EditText
    private lateinit var manualPacketInput: EditText

    private lateinit var gatewayTitle: TextView
    private lateinit var cmdTitle: TextView
    private lateinit var ackTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GatewayManager.init(this)
        transport = GatewayManager.mqttTransport
        setContentView(buildUi())
        loadFields()
        observeTransport()
    }

    private fun loadFields() {
        val config = MqttPrefs.load(this)
        hostInput.setText(config.host)
        portInput.setText(config.port.toString())
        userInput.setText(config.username)
        passInput.setText(config.password)
        deviceIdInput.setText(MqttPrefs.loadDeviceId(this))
    }

    private fun readConfig(): MqttConfig? {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull()
        if (host.isBlank() || port == null) {
            Toast.makeText(this, "Host and port are required", Toast.LENGTH_SHORT).show()
            return null
        }
        return MqttConfig(
            host = host,
            port = port,
            username = userInput.text.toString().trim(),
            password = passInput.text.toString(),
        )
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Top Header with Connect Toggle
        val header = FrameLayout(this).apply {
            setPadding(30, 20, 30, 20)
            setBackgroundColor(Color.LTGRAY)
        }
        statusView = TextView(this).apply {
            text = "MQTT: Disconnected"
            setTextColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            )
        }
        connectButton = Button(this).apply {
            text = "CONNECT"
            setBackgroundColor(Color.RED)
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            )
            setOnClickListener {
                if (transport.connectionState.value == ConnectionState.Connected) {
                    transport.disconnect()
                } else {
                    val config = readConfig() ?: return@setOnClickListener
                    val deviceId = deviceIdInput.text.toString().trim().ifBlank { null }
                    MqttPrefs.save(this@MqttActivity, config, deviceId ?: "")
                    transport.connect(config, deviceId)
                }
            }
        }
        header.addView(statusView)
        header.addView(connectButton)
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f
            )
        }
        
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 10, 30, 10)
        }
        scroll.addView(content)

        val wrap = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 4; bottomMargin = 4 }

        val bridgeToggle = Button(this).apply {
            text = "Bridge: OFF"
            layoutParams = wrap
            setOnClickListener {
                val newState = !GatewayManager.isBridgeEnabled.value
                GatewayManager.toggleBridge(newState)
            }
        }
        lifecycleScope.launch {
            GatewayManager.isBridgeEnabled.collect { enabled ->
                bridgeToggle.text = "Bridge: ${if (enabled) "ON" else "OFF"}"
            }
        }

        content.addView(bridgeToggle)
        content.addView(TextView(this).apply { text = "Broker Settings"; textSize = 14f; setTextColor(Color.BLUE) })

        // Two-column Broker Settings
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hostInput = edit("Host", LinearLayout.LayoutParams(0, -2, 0.7f))
        portInput = edit("Port", LinearLayout.LayoutParams(0, -2, 0.3f))
        row1.addView(hostInput)
        row1.addView(portInput)
        content.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        userInput = edit("User", LinearLayout.LayoutParams(0, -2, 0.5f))
        passInput = edit("Pass", LinearLayout.LayoutParams(0, -2, 0.5f)).apply { 
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        row2.addView(userInput)
        row2.addView(passInput)
        content.addView(row2)

        deviceIdInput = edit("Device ID (12 hex)", wrap)
        content.addView(deviceIdInput)

        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 10) }) // Smaller spacer

        content.addView(TextView(this).apply { 
            text = "MANUAL PACKET (JSON ARRAY)"; 
            textSize = 13f; 
            setTextColor(Color.BLUE) 
        })
        manualPacketInput = EditText(this).apply {
            hint = "Enter JSON packet here..."
            setText("""[2,"1048605","MeterValues",{"connectorId":"1","transactionId":"T1784549060382","meterValue":{"v":234620,"c":12880,"p":3022759,"e":23079013,"f":50}},"70041dafd038"]""")
            layoutParams = wrap
            minLines = 2
            gravity = Gravity.TOP
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            textSize = 12f
        }
        val sendManualButton = Button(this).apply {
            text = "SEND PACKET TO SERVER"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            layoutParams = wrap
            setOnClickListener {
                val msg = manualPacketInput.text.toString()
                transport.publishPacket(msg) { ok ->
                    if (!ok) {
                        runOnUiThread { Toast.makeText(this@MqttActivity, "Send failed - Check Connection", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
        content.addView(manualPacketInput)
        content.addView(sendManualButton)

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
                commandLogAdapter.clear()
                ackLogAdapter.clear()
            }
        }
        logsHeader.addView(clearLogsButton)
        root.addView(logsHeader)

        // Bottom Logs Section: Split into Gateway (top) and Incoming (split bottom)
        val logsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.5f
            )
        }

        // 1. Gateway & Outbound Logs
        val gatewaySection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Color.parseColor("#F9F9F9"))
        }
        gatewayTitle = TextView(this).apply { 
            text = "GATEWAY → ${PtContract.TOPIC_PACKET}"; 
            gravity = Gravity.CENTER; textSize = 10f; setTextColor(Color.DKGRAY) 
        }
        gatewaySection.addView(gatewayTitle)
        gatewayLogAdapter = createLogAdapter()
        gatewaySection.addView(ListView(this).apply { adapter = gatewayLogAdapter })
        logsContainer.addView(gatewaySection)

        // 2. Incoming Split: Commands | Acks
        val incomingSplit = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.2f
            )
        }

        val cmdLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setPadding(2, 0, 2, 0)
        }
        cmdTitle = TextView(this).apply { 
            text = "CMD: ${PtContract.TOPIC_COMMAND}"; gravity = Gravity.CENTER; textSize = 9f; setTextColor(Color.BLUE) 
        }
        cmdLayout.addView(cmdTitle)
        commandLogAdapter = createLogAdapter()
        cmdLayout.addView(ListView(this).apply { adapter = commandLogAdapter })

        val ackLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setPadding(2, 0, 2, 0)
        }
        ackTitle = TextView(this).apply { 
            text = "ACKS: (none)"; gravity = Gravity.CENTER; textSize = 9f; setTextColor(Color.parseColor("#388E3C")) 
        }
        ackLayout.addView(ackTitle)
        ackLogAdapter = createLogAdapter()
        ackLayout.addView(ListView(this).apply { adapter = ackLogAdapter })

        incomingSplit.addView(cmdLayout)
        incomingSplit.addView(ackLayout)
        logsContainer.addView(incomingSplit)

        root.addView(scroll)
        root.addView(logsContainer)
        return root
    }

    private fun createLogAdapter() = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf()) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = super.getView(position, convertView, parent)
            val text = getItem(position) ?: ""
            (v.findViewById<TextView>(android.R.id.text1)).apply {
                textSize = 9f
                isSingleLine = false
                setPadding(8, 8, 8, 8)
                
                when {
                    text.contains("Published ->") -> setTextColor(Color.parseColor("#006400")) // Dark Green
                    text.contains("Bridge: BLE -> MQTT") -> setTextColor(Color.parseColor("#8B008B")) // Magenta
                    text.contains("Bridge: MQTT -> BLE") -> setTextColor(Color.BLUE)
                    text.contains("Error") || text.contains("failed") -> setTextColor(Color.RED)
                    else -> setTextColor(Color.BLACK)
                }
            }
            return v
        }
    }

    private fun edit(hint: String, params: LinearLayout.LayoutParams) =
        EditText(this).apply {
            this.hint = hint
            layoutParams = params
        }

    private fun observeTransport() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    transport.connectionState.collect { state ->
                        statusView.text = "MQTT: $state"
                        if (state == ConnectionState.Connected) {
                            connectButton.text = "DISCONNECT"
                            connectButton.setBackgroundColor(Color.parseColor("#4CAF50")) // Green
                            
                            // Update titles with actual topics
                            val id = MqttPrefs.loadDeviceId(this@MqttActivity)
                            if (id.isNotBlank()) {
                                gatewayTitle.text = "PUB: ${PtContract.TOPIC_PACKET}\nACK: ${PtContract.topicAck(id)}"
                                cmdTitle.text = "COMMANDS (Server → Phone)\n${PtContract.topicCommand(id)}"
                                ackTitle.text = "ACKS (Server → Phone)\n${PtContract.topicAck(id)}"
                            }
                        } else {
                            connectButton.text = "CONNECT"
                            connectButton.setBackgroundColor(Color.RED)
                        }
                    }
                }
                launch {
                    transport.incoming.collect { msg ->
                        if (msg.topic.endsWith("/ack")) {
                            ackLogAdapter.insert(msg.payload, 0)
                        } else {
                            commandLogAdapter.insert(msg.payload, 0)
                        }
                    }
                }
                LogRepository.logs.observe(this@MqttActivity) { logs ->
                    gatewayLogAdapter.clear()
                    gatewayLogAdapter.addAll(logs.take(50))
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Removed transport.disconnect() to keep background forwarding alive
    }
}
