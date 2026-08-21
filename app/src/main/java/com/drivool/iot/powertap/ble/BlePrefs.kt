package com.drivool.iot.powertap.ble

import android.content.Context
import com.drivool.iot.powertap.DeviceIdentity

object BlePrefs {
    private const val PREFS = "ble_prefs"
    private const val KEY_LAST_ADDRESS = "last_address"
    // Deliberately a new key: the old "auto_connect" opt-out meant something
    // broader, so an existing false value must not silently disable session
    // resume and leave a user unable to stop their own charge.
    private const val KEY_SESSION_RESUME = "session_resume"
    private const val KEY_KNOWN_DEVICES = "known_devices"
    private const val KEY_PAIRED_DEVICES = "paired_devices"

    fun saveLastDevice(context: Context, address: String, name: String?) {
        val ble = DeviceIdentity.toBleAddress(address)
            ?: DeviceIdentity.bleFromDeviceId(address)
            ?: address
        val displayName = DeviceIdentity.preferredName(name, ble)

        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val known = p.getStringSet(KEY_KNOWN_DEVICES, emptySet())?.toMutableSet() ?: mutableSetOf()

        // Drop older aliases for the same physical PowerTap (unknown/id/ble variants).
        known.removeAll { entry ->
            val storedAddress = entry.substringAfter('|', entry)
            DeviceIdentity.sameDevice(storedAddress, ble) ||
                DeviceIdentity.sameDevice(entry.substringBefore('|', ""), ble)
        }
        known.add("$displayName|$ble")

        p.edit()
            .putString(KEY_LAST_ADDRESS, ble)
            .putStringSet(KEY_KNOWN_DEVICES, known)
            .apply()
    }

    fun getLastDeviceAddress(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_ADDRESS, null)

    /**
     * Whether the app may reconnect on its own to resume a session it owns.
     *
     * This is no longer "connect to the last charger on app open" — that
     * silently occupied chargers, because the firmware stops advertising while a
     * phone is connected. It now only covers picking a running session back up
     * after a drop or an app restart.
     */
    fun isSessionResumeEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SESSION_RESUME, true)

    fun setSessionResumeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SESSION_RESUME, enabled)
            .apply()
    }

    /**
     * Known devices deduped to one row per physical PowerTap.
     * Pair = (displayName, bleAddress).
     */
    fun getKnownDevices(context: Context): List<Pair<String?, String>> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = p.getStringSet(KEY_KNOWN_DEVICES, emptySet()) ?: emptySet()

        data class Acc(var name: String?, var address: String, var nameScore: Int)

        val byFamily = linkedMapOf<String, Acc>()
        for (entry in set) {
            val (rawName, rawAddress) = if (entry.contains("|")) {
                val parts = entry.split("|", limit = 2)
                parts[0] to parts[1]
            } else {
                null to entry
            }
            val ble = DeviceIdentity.toBleAddress(rawAddress)
                ?: DeviceIdentity.bleFromDeviceId(rawAddress)
                ?: continue
            val family = DeviceIdentity.familyKey(ble) ?: continue
            val score = DeviceIdentity.scoreName(rawName)
            val existing = byFamily[family]
            if (existing == null) {
                byFamily[family] = Acc(
                    name = DeviceIdentity.preferredName(rawName, ble),
                    address = ble,
                    nameScore = score,
                )
            } else {
                if (score > existing.nameScore) {
                    existing.name = DeviceIdentity.preferredName(rawName, ble)
                    existing.nameScore = score
                }
                // Prefer colon BLE form (always true after toBleAddress)
                existing.address = ble
            }
        }

        val deduped = byFamily.values.map { it.name to it.address }

        // Rewrite prefs so stale aliases don't keep coming back.
        val rewritten = deduped.map { (name, addr) -> "${name ?: "PowerTap"}|$addr" }.toSet()
        if (rewritten != set) {
            p.edit().putStringSet(KEY_KNOWN_DEVICES, rewritten).apply()
        }

        return deduped
    }

    /**
     * True once GATT has connected at least once. Used so Home can label
     * a charger as "needs pairing" vs "tap to reconnect".
     */
    fun markPaired(context: Context, address: String) {
        val ble = DeviceIdentity.toBleAddress(address) ?: return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val known = p.getStringSet(KEY_PAIRED_DEVICES, emptySet())?.toMutableSet() ?: mutableSetOf()
        known.removeAll { DeviceIdentity.sameDevice(it, ble) }
        known.add(ble)
        p.edit().putStringSet(KEY_PAIRED_DEVICES, known).apply()
    }

    fun isPaired(context: Context, address: String): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val known = p.getStringSet(KEY_PAIRED_DEVICES, emptySet()) ?: emptySet()
        return known.any { DeviceIdentity.sameDevice(it, address) }
    }
}
