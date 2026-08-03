package com.drivool.iot.powertap.mqtt

import android.content.Context
import com.drivool.iot.powertap.BuildConfig

/** Persists MQTT settings entered in the UI (overrides BuildConfig when set). */
object MqttPrefs {
    private const val PREFS = "mqtt_prefs"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_USER = "user"
    private const val KEY_PASS = "pass"
    private const val KEY_DEVICE_ID = "device_id"

    fun load(context: Context): MqttConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return MqttConfig(
            host = p.getString(KEY_HOST, null) ?: BuildConfig.MQTT_HOST,
            port = p.getInt(KEY_PORT, BuildConfig.MQTT_PORT),
            username = p.getString(KEY_USER, null) ?: BuildConfig.MQTT_USER,
            password = p.getString(KEY_PASS, null) ?: BuildConfig.MQTT_PASS,
        )
    }

    fun loadDeviceId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_ID, "") ?: ""

    fun save(context: Context, config: MqttConfig, deviceId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, config.host)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USER, config.username)
            .putString(KEY_PASS, config.password)
            .putString(KEY_DEVICE_ID, deviceId.trim().lowercase())
            .apply()
    }
}
