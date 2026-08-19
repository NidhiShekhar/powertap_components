package com.drivool.iot.powertap

import java.util.Locale
import kotlin.math.abs

/**
 * Display helpers for meter values after [GatewayManager] scaling.
 *
 * Firmware sends milli-units (`p`, `e`). After dividing by 1000:
 * - power is **W**
 * - energy is **Wh** (the Home LCD used to label this as kWh)
 *
 * Values at 4 digits (1000+) are shown in kilo-units, matching the
 * device LCD (`utilities.cpp` uses the same 1000 / 1_000_000 thresholds).
 */
object MeterUnits {
    private const val KILO_THRESHOLD = 1000f

    fun formatPowerWatts(watts: Float, locale: Locale = Locale.getDefault()): String {
        return if (abs(watts) >= KILO_THRESHOLD) {
            String.format(locale, "%.1fkW", watts / 1000f)
        } else {
            String.format(locale, "%.1fW", watts)
        }
    }

    fun formatEnergyWh(wattHours: Float, locale: Locale = Locale.getDefault()): String {
        return if (abs(wattHours) >= KILO_THRESHOLD) {
            String.format(locale, "%.1fkWh", wattHours / 1000f)
        } else {
            String.format(locale, "%.1fWh", wattHours)
        }
    }

    fun formatDuration(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
