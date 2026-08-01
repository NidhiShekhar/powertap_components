package com.example.powertap.mqtt

import com.example.powertap.contract.ConnectionState
import com.example.powertap.contract.PtContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

/**
 * MQTT client for the phone gateway.
 */
class MqttTransport(
    private val log: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incoming = MutableSharedFlow<MqttIncoming>(extraBufferCapacity = 32)
    val incoming: SharedFlow<MqttIncoming> = _incoming.asSharedFlow()

    private var client: MqttAsyncClient? = null
    private var subscribedDeviceId: String? = null

    fun connect(config: MqttConfig, deviceId: String? = null) {
        val id = deviceId?.trim()?.lowercase()
        if (id != null && (id.length != 12 || !id.all { it in '0'..'9' || it in 'a'..'f' })) {
            log("Device ID must be 12 hex characters")
            _connectionState.value = ConnectionState.Failed
            return
        }
        if (config.host.isBlank()) {
            log("MQTT host is empty")
            _connectionState.value = ConnectionState.Failed
            return
        }

        scope.launch {
            disconnectInternal()
            _connectionState.value = ConnectionState.Connecting
            log("Connecting to ${config.brokerUri}...")

            try {
                val clientId = "powertap-android-${UUID.randomUUID().toString().take(8)}"
                val mqtt = MqttAsyncClient(config.brokerUri, clientId, MemoryPersistence())
                mqtt.setCallback(mqttCallback)

                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 30
                    keepAliveInterval = 60
                    if (config.username.isNotBlank()) {
                        userName = config.username
                        password = config.password.toCharArray()
                    }
                }

                mqtt.connect(options).waitForCompletion(30_000)
                client = mqtt
                subscribedDeviceId = id

                mqtt.subscribe(PtContract.TOPIC_COMMAND, PtContract.MQTT_QOS).waitForCompletion(10_000)

                if (id != null) {
                    val cmdTopic = PtContract.topicCommand(id)
                    val ackTopic = PtContract.topicAck(id)
                    mqtt.subscribe(cmdTopic, PtContract.MQTT_QOS).waitForCompletion(5_000)
                    mqtt.subscribe(ackTopic, PtContract.MQTT_QOS).waitForCompletion(5_000)
                }

                _connectionState.value = ConnectionState.Connected
            } catch (e: Exception) {
                log("MQTT connect failed: ${e.message}")
                disconnectInternal()
                _connectionState.value = ConnectionState.Failed
            }
        }
    }

    fun disconnect() {
        scope.launch { disconnectInternal() }
    }

    private suspend fun disconnectInternal() {
        withContext(Dispatchers.IO) {
            try {
                client?.disconnect()?.waitForCompletion(5_000)
            } catch (_: Exception) { }
            try {
                client?.close()
            } catch (_: Exception) { }
        }
        client = null
        subscribedDeviceId = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun publishPacket(payload: String, onResult: (Boolean) -> Unit = {}) {
        publish(PtContract.TOPIC_PACKET, payload, onResult)
    }

    fun publishAck(payload: String, onResult: (Boolean) -> Unit = {}) {
        val id = subscribedDeviceId
        if (id != null) {
            publish(PtContract.topicAck(id), payload, onResult)
        } else {
            publishPacket(payload, onResult)
        }
    }

    fun publish(topic: String, payload: String, onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            val ok = publishBlocking(topic, payload)
            onResult(ok)
        }
    }

    private suspend fun publishBlocking(topic: String, payload: String): Boolean =
        withContext(Dispatchers.IO) {
            val mqtt = client
            if (mqtt == null || !mqtt.isConnected) return@withContext false
            try {
                val msg = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                    qos = PtContract.MQTT_QOS
                }
                mqtt.publish(topic, msg).waitForCompletion(10_000)
                true
            } catch (e: Exception) {
                false
            }
        }

    private val mqttCallback = object : MqttCallback {
        override fun connectionLost(cause: Throwable?) {
            client = null
            subscribedDeviceId = null
            _connectionState.value = ConnectionState.Disconnected
        }

        override fun messageArrived(topic: String, message: MqttMessage) {
            val payload = String(message.payload, Charsets.UTF_8)
            _incoming.tryEmit(MqttIncoming(topic, payload))
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) { }
    }
}
