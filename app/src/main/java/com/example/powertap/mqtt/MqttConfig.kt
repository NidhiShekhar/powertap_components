package com.drivool.iot.powertap.mqtt

import com.drivool.iot.powertap.BuildConfig

/** Broker connection settings (defaults from build.gradle). */
data class MqttConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
) {
    val brokerUri: String get() = "tcp://$host:$port"

    companion object {
        fun fromBuildConfig(): MqttConfig = MqttConfig(
            host = BuildConfig.MQTT_HOST,
            port = BuildConfig.MQTT_PORT,
            username = BuildConfig.MQTT_USER,
            password = BuildConfig.MQTT_PASS,
        )
    }
}
