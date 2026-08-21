package com.drivool.iot.powertap

import com.drivool.iot.powertap.contract.MeterData

/**
 * Maps live [MeterData] onto the Charging Pattern chart series.
 *
 * Port of PowerTapApp `UIComponents/ChargingChart`: Current (A), Voltage (V),
 * Power (kW). [MeterData.power] is already in watts, so power is divided by
 * 1000 for the kW axis.
 */
object ChargingChartSeries {
    enum class Metric(val tabLabel: String, val axisLabel: String) {
        CURRENT("Current", "Current (A)"),
        VOLTAGE("Voltage", "Voltage (V)"),
        POWER("Power", "Power (kW)"),
    }

    data class Point(val timestamp: Long, val value: Float)

    fun isPlottable(sample: MeterData): Boolean =
        (sample.voltage != 0f) || (sample.current != 0f) || (sample.power != 0f)

    fun valueOf(sample: MeterData, metric: Metric): Float = when (metric) {
        Metric.CURRENT -> sample.current
        Metric.VOLTAGE -> sample.voltage
        Metric.POWER -> sample.power / 1000f
    }

    fun points(samples: List<MeterData>, metric: Metric): List<Point> =
        samples.asSequence()
            .filter(::isPlottable)
            .map { Point(it.timestamp, valueOf(it, metric)) }
            .toList()

    /**
     * Evenly pick [maxPoints] samples so a long session still shows its
     * overall shape instead of only the last few minutes.
     */
    fun downsample(samples: List<MeterData>, maxPoints: Int): List<MeterData> {
        if (maxPoints <= 0 || samples.size <= maxPoints) return samples
        if (maxPoints == 1) return listOf(samples.last())
        val lastIndex = samples.size - 1
        return List(maxPoints) { i ->
            val index = ((i.toLong() * lastIndex) / (maxPoints - 1)).toInt()
            samples[index]
        }
    }
}
