package com.drivool.iot.powertap

import com.drivool.iot.powertap.contract.MeterData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingChartSeriesTest {

    @Test
    fun isPlottable_rejectsEmptyStartTransactionSamples() {
        val empty = MeterData(voltage = 0f, current = 0f, power = 0f, energy = 12f, frequency = 50f)
        assertFalse(ChargingChartSeries.isPlottable(empty))
    }

    @Test
    fun isPlottable_acceptsLiveMeterSamples() {
        val live = MeterData(voltage = 232.4f, current = 4.37f, power = 1024.5f, energy = 261.8f, frequency = 50f)
        assertTrue(ChargingChartSeries.isPlottable(live))
    }

    @Test
    fun points_mapsCurrentVoltageAndPowerInKw() {
        val samples = listOf(
            MeterData(voltage = 0f, current = 0f, power = 0f, energy = 260f, frequency = 50f, timestamp = 1L),
            MeterData(voltage = 232.455f, current = 4.370f, power = 3500f, energy = 261.8f, frequency = 50f, timestamp = 2L),
        )

        val current = ChargingChartSeries.points(samples, ChargingChartSeries.Metric.CURRENT)
        val voltage = ChargingChartSeries.points(samples, ChargingChartSeries.Metric.VOLTAGE)
        val power = ChargingChartSeries.points(samples, ChargingChartSeries.Metric.POWER)

        assertEquals(1, current.size)
        assertEquals(4.370f, current[0].value, 0.0001f)
        assertEquals(232.455f, voltage[0].value, 0.0001f)
        assertEquals(3.5f, power[0].value, 0.0001f)
        assertEquals(2L, power[0].timestamp)
    }

    @Test
    fun metric_axisLabelsMatchJsChargingChart() {
        assertEquals("Current (A)", ChargingChartSeries.Metric.CURRENT.axisLabel)
        assertEquals("Voltage (V)", ChargingChartSeries.Metric.VOLTAGE.axisLabel)
        assertEquals("Power (kW)", ChargingChartSeries.Metric.POWER.axisLabel)
    }

    @Test
    fun downsample_keepsShortSeriesUnchanged() {
        val samples = (1..3).map { i ->
            MeterData(230f, 4f, 900f, 10f, 50f, timestamp = i.toLong())
        }
        assertEquals(samples, ChargingChartSeries.downsample(samples, 10))
    }

    @Test
    fun downsample_keepsFirstMiddleAndLast() {
        val samples = (0..4).map { i ->
            MeterData(230f, i.toFloat(), 900f, 10f, 50f, timestamp = i.toLong())
        }
        val reduced = ChargingChartSeries.downsample(samples, 3)
        assertEquals(3, reduced.size)
        assertEquals(0L, reduced[0].timestamp)
        assertEquals(2L, reduced[1].timestamp)
        assertEquals(4L, reduced[2].timestamp)
    }
}
