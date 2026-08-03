package com.drivool.iot.powertap.mqtt

/** A message received from the MQTT broker. */
data class MqttIncoming(
    val topic: String,
    val payload: String,
)
