package com.drivool.iot.powertap

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class MeterUnitsTest {

    private val us = Locale.US

    @Test
    fun formatPowerWatts_keepsWattsBelowFourDigits() {
        assertEquals("0.0W", MeterUnits.formatPowerWatts(0f, us))
        assertEquals("302.3W", MeterUnits.formatPowerWatts(302.2759f, us))
        assertEquals("999.9W", MeterUnits.formatPowerWatts(999.9f, us))
    }

    @Test
    fun formatPowerWatts_convertsFourDigitWattsToKw() {
        assertEquals("1.0kW", MeterUnits.formatPowerWatts(1000f, us))
        assertEquals("3.0kW", MeterUnits.formatPowerWatts(3022.759f, us))
        assertEquals("12.5kW", MeterUnits.formatPowerWatts(12500f, us))
    }

    @Test
    fun formatEnergyWh_labelsWhNotKwhWhenBelowOneKwh() {
        assertEquals("0.0Wh", MeterUnits.formatEnergyWh(0f, us))
        assertEquals("23.1Wh", MeterUnits.formatEnergyWh(23.079f, us))
        assertEquals("230.8Wh", MeterUnits.formatEnergyWh(230.79f, us))
        assertEquals("999.0Wh", MeterUnits.formatEnergyWh(999f, us))
    }

    @Test
    fun formatEnergyWh_convertsFourDigitWhToKwh() {
        assertEquals("1.0kWh", MeterUnits.formatEnergyWh(1000f, us))
        assertEquals("23.1kWh", MeterUnits.formatEnergyWh(23079.013f, us))
    }

    @Test
    fun formatDuration_formatsElapsedChargingTime() {
        assertEquals("00:00:00", MeterUnits.formatDuration(0))
        assertEquals("00:00:36", MeterUnits.formatDuration(36_000))
        assertEquals("01:05:09", MeterUnits.formatDuration(3_909_000))
    }
}
