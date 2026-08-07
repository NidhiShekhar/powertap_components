package com.drivool.iot.powertap

import android.net.Uri
import org.json.JSONObject

/**
 * Parsed PowerTap QR payload used to connect over BLE and identify the device for MQTT.
 *
 * Supported formats:
 * - JSON: {"deviceId":"70041dafd038","bleAddress":"70:04:1D:AF:D0:39"}
 * - URI:  powertap://connect?id=70041dafd038&ble=70:04:1D:AF:D0:39
 * - Pipe: 70041dafd038|70:04:1D:AF:D0:39
 * - Device ID only (12 hex) — BLE MAC derived as STA MAC + 1
 * - BLE MAC only (AA:BB:CC:DD:EE:FF) — deviceId derived as BLE MAC - 1
 */
data class PowerTapQr(
    val deviceId: String,
    val bleAddress: String,
) {
    val displayName: String get() = "PowerTap_$deviceId"

    companion object {
        private val DEVICE_ID_REGEX = Regex("^[0-9a-fA-F]{12}$")
        private val MAC_REGEX = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
        private val MAC_NO_COLON_REGEX = Regex("^[0-9a-fA-F]{12}$")

        fun parse(raw: String): PowerTapQr? {
            val text = raw.trim()
            if (text.isEmpty()) return null

            parseJson(text)?.let { return it }
            parseUri(text)?.let { return it }
            parsePipe(text)?.let { return it }
            parseBare(text)?.let { return it }
            return null
        }

        /**
         * Manual entry: accepts a 12-hex device ID, `PowerTap_<id>` name,
         * and an optional BLE MAC (derived from device ID when omitted).
         */
        fun fromManualEntry(nameOrDeviceId: String, bleAddress: String? = null): PowerTapQr? {
            var raw = nameOrDeviceId.trim()
            if (raw.startsWith("PowerTap_", ignoreCase = true)) {
                raw = raw.substringAfter('_')
            }
            return fromParts(raw, bleAddress?.trim().orEmpty())
        }

        private fun parseJson(text: String): PowerTapQr? {
            if (!text.startsWith("{")) return null
            return try {
                val obj = JSONObject(text)
                val deviceId = obj.optString("deviceId")
                    .ifBlank { obj.optString("id") }
                    .ifBlank { obj.optString("device_id") }
                val ble = obj.optString("bleAddress")
                    .ifBlank { obj.optString("ble") }
                    .ifBlank { obj.optString("mac") }
                    .ifBlank { obj.optString("address") }
                fromParts(deviceId, ble)
            } catch (_: Exception) {
                null
            }
        }

        private fun parseUri(text: String): PowerTapQr? {
            if (!text.contains("://") && !text.startsWith("powertap:", ignoreCase = true)) {
                return null
            }
            return try {
                val uri = Uri.parse(text)
                val deviceId = uri.getQueryParameter("id")
                    ?: uri.getQueryParameter("deviceId")
                    ?: uri.getQueryParameter("device_id")
                    ?: ""
                val ble = uri.getQueryParameter("ble")
                    ?: uri.getQueryParameter("bleAddress")
                    ?: uri.getQueryParameter("mac")
                    ?: uri.getQueryParameter("address")
                    ?: ""
                fromParts(deviceId, ble)
            } catch (_: Exception) {
                null
            }
        }

        private fun parsePipe(text: String): PowerTapQr? {
            if (!text.contains("|")) return null
            val parts = text.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            return fromParts(parts[0], parts[1]) ?: fromParts(parts[1], parts[0])
        }

        private fun parseBare(text: String): PowerTapQr? {
            val cleaned = text.replace("-", "").replace(" ", "")
            return when {
                MAC_REGEX.matches(cleaned) -> fromParts(deviceId = "", ble = cleaned)
                DEVICE_ID_REGEX.matches(cleaned) -> fromParts(deviceId = cleaned, ble = "")
                else -> null
            }
        }

        private fun fromParts(deviceId: String, ble: String): PowerTapQr? {
            val normalizedBle = normalizeBleAddress(ble)
            val normalizedId = normalizeDeviceId(deviceId)

            val finalBle = normalizedBle
                ?: normalizedId?.let { deviceIdToBleAddress(it) }
                ?: return null
            val finalId = normalizedId
                ?: bleAddressToDeviceId(finalBle)

            if (!DEVICE_ID_REGEX.matches(finalId)) return null
            if (!MAC_REGEX.matches(finalBle)) return null
            return PowerTapQr(deviceId = finalId.lowercase(), bleAddress = finalBle.uppercase())
        }

        fun normalizeDeviceId(raw: String): String? {
            val cleaned = raw.trim().replace(":", "").replace("-", "").lowercase()
            return cleaned.takeIf { DEVICE_ID_REGEX.matches(it) }
        }

        fun normalizeBleAddress(raw: String): String? {
            val trimmed = raw.trim()
            if (MAC_REGEX.matches(trimmed)) {
                return trimmed.uppercase()
            }
            val noColon = trimmed.replace(":", "").replace("-", "")
            if (!MAC_NO_COLON_REGEX.matches(noColon)) return null
            return noColon.chunked(2).joinToString(":").uppercase()
        }

        /** ESP32 BLE MAC is typically WiFi STA (deviceId) MAC + 1. */
        fun deviceIdToBleAddress(deviceId: String): String? {
            val id = normalizeDeviceId(deviceId) ?: return null
            val next = try {
                val last = id.last().digitToInt(16)
                id.dropLast(1) + ((last + 1) % 16).toString(16)
            } catch (_: Exception) {
                return null
            }
            return next.chunked(2).joinToString(":").uppercase()
        }

        /** Inverse of [deviceIdToBleAddress]. */
        fun bleAddressToDeviceId(bleAddress: String): String {
            val mac = normalizeBleAddress(bleAddress)?.replace(":", "")?.lowercase()
                ?: return ""
            return try {
                val last = mac.last().digitToInt(16)
                if (last > 0) mac.dropLast(1) + (last - 1).toString(16) else mac
            } catch (_: Exception) {
                mac
            }
        }
    }
}
