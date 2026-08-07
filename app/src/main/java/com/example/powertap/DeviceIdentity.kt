package com.drivool.iot.powertap

/**
 * Helpers to treat WiFi STA device IDs and BLE MACs as one physical PowerTap.
 *
 * ESP32: BLE MAC ≈ WiFi STA MAC (deviceId) + 1.
 */
object DeviceIdentity {

    private val HEX_12 = Regex("^[0-9a-fA-F]{12}$")
    private val MAC_COLONS = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")

    /** 12-char lowercase hex with no separators. */
    fun cleanHex(raw: String): String? {
        val cleaned = raw.trim().replace(":", "").replace("-", "").lowercase()
        return cleaned.takeIf { HEX_12.matches(it) }
    }

    /** Android-style BLE address, uppercase with colons. */
    fun toBleAddress(raw: String): String? {
        val trimmed = raw.trim()
        if (MAC_COLONS.matches(trimmed)) return trimmed.uppercase()
        val hex = cleanHex(trimmed) ?: return null
        return hex.chunked(2).joinToString(":").uppercase()
    }

    fun toDeviceId(bleOrId: String): String? {
        val hex = cleanHex(bleOrId) ?: return null
        // Prefer explicit PowerTap_ name handling by callers; here assume BLE→STA (−1)
        // only when [preferBleAsSource] — see [deviceIdFromBle].
        return hex
    }

    fun deviceIdFromBle(bleAddress: String): String? {
        val hex = cleanHex(bleAddress) ?: return null
        return try {
            val last = hex.last().digitToInt(16)
            if (last > 0) hex.dropLast(1) + (last - 1).toString(16) else hex
        } catch (_: Exception) {
            hex
        }
    }

    fun bleFromDeviceId(deviceId: String): String? {
        val hex = cleanHex(deviceId) ?: return null
        return try {
            val last = hex.last().digitToInt(16)
            val bleHex = hex.dropLast(1) + ((last + 1) % 16).toString(16)
            bleHex.chunked(2).joinToString(":").uppercase()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Stable key for one physical unit: WiFi STA / deviceId (12 hex).
     * Colon MACs from Android are treated as BLE (STA = BLE − 1).
     * Bare 12-hex values are treated as deviceId already (STA).
     */
    fun familyKey(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.contains(":")) {
            return deviceIdFromBle(trimmed)
        }
        return cleanHex(trimmed)
    }

    fun sameDevice(a: String, b: String): Boolean {
        val ka = familyKey(a) ?: return false
        val kb = familyKey(b) ?: return false
        if (ka == kb) return true
        // Also match if one was stored as BLE hex without colons (STA+1)
        val bleOfA = bleFromDeviceId(ka)?.let { cleanHex(it) }
        val bleOfB = bleFromDeviceId(kb)?.let { cleanHex(it) }
        return ka == bleOfB || kb == bleOfA || bleOfA == bleOfB
    }

    fun preferredName(name: String?, bleAddress: String): String {
        if (!name.isNullOrBlank() && name.startsWith("PowerTap_", ignoreCase = true)) {
            return name
        }
        val id = when {
            !name.isNullOrBlank() && cleanHex(name.removePrefix("PowerTap_").removePrefix("powertap_")) != null ->
                cleanHex(name.substringAfter('_'))!!
            bleAddress.contains(":") -> deviceIdFromBle(bleAddress)
            else -> cleanHex(bleAddress)
        }
        return if (id != null && id.length == 12) "PowerTap_$id" else (name ?: "PowerTap")
    }

    fun scoreName(name: String?): Int = when {
        name.isNullOrBlank() -> 0
        name.startsWith("PowerTap_", ignoreCase = true) -> 3
        cleanHex(name) != null -> 1 // bare id used as name
        name.equals("(unknown)", true) || name.equals("unknown", true) -> 0
        else -> 2
    }
}
