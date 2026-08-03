package com.drivool.iot.powertap.ble

import android.content.Context

object BlePrefs {
    private const val PREFS = "ble_prefs"
    private const val KEY_LAST_ADDRESS = "last_address"
    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_KNOWN_DEVICES = "known_devices"

    fun saveLastDevice(context: Context, address: String, name: String?) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val known = p.getStringSet(KEY_KNOWN_DEVICES, emptySet())?.toMutableSet() ?: mutableSetOf()
        val entry = if (name != null) "$name|$address" else address
        known.add(entry)
        
        p.edit()
            .putString(KEY_LAST_ADDRESS, address)
            .putStringSet(KEY_KNOWN_DEVICES, known)
            .apply()
    }

    fun getLastDeviceAddress(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_ADDRESS, null)

    fun isAutoConnectEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_CONNECT, true)

    fun setAutoConnectEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_CONNECT, enabled)
            .apply()
    }

    fun getKnownDevices(context: Context): List<Pair<String?, String>> {
        val set = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_KNOWN_DEVICES, emptySet()) ?: emptySet()
        return set.map {
            if (it.contains("|")) {
                val parts = it.split("|")
                parts[0] to parts[1]
            } else {
                null to it
            }
        }
    }
}
