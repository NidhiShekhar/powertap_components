package com.drivool.iot.powertap

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.drivool.iot.powertap.contract.MeterData
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.tabs.TabLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live meter chart that replaces the Full Charge / Set Time / Set Units card
 * while a session is running. Port of PowerTapApp `ChargingChart.js`.
 */
class ChargingChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val lineChart: LineChart
    private val tabLayout: TabLayout
    private val samples = mutableListOf<MeterData>()
    private var metric = ChargingChartSeries.Metric.CURRENT
    private var firstTimestamp = 0L
    private var emptyMessage = "Waiting for meter data…"
    private var drawCircles = true

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        inflate(context, R.layout.view_charging_chart, this)

        lineChart = findViewById(R.id.chargingLineChart)
        tabLayout = findViewById(R.id.chartTabLayout)

        ChargingChartSeries.Metric.entries.forEach { item ->
            tabLayout.addTab(tabLayout.newTab().setText(item.tabLabel.uppercase(Locale.US)))
        }
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {

            override fun onTabSelected(tab: TabLayout.Tab?) {
                metric = ChargingChartSeries.Metric.entries.getOrElse(tab?.position ?: 0) {
                    ChargingChartSeries.Metric.CURRENT
                }
                render()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        setupChart()
    }

    fun clear() {
        samples.clear()
        firstTimestamp = 0L
        drawCircles = true
        render()
    }

    fun setEmptyMessage(message: String) {
        emptyMessage = message
        lineChart.setNoDataText(emptyMessage)
        if (samples.isEmpty()) render()
    }

    /** Stretch the plot to fill leftover space (history screen). */
    fun expandChart() {
        val params = lineChart.layoutParams as LayoutParams
        params.height = 0
        params.weight = 1f
        lineChart.layoutParams = params
    }

    fun setSamples(data: List<MeterData>) {
        samples.clear()
        firstTimestamp = 0L
        drawCircles = true
        data.filter(ChargingChartSeries::isPlottable).forEach { append(it) }
        render()
    }

    /**
     * Load a completed session: keep the full time range, downsample if needed,
     * and skip point markers so a long series stays a smooth filled line.
     */
    fun setSessionSamples(data: List<MeterData>) {
        val plottable = data.asSequence()
            .filter(ChargingChartSeries::isPlottable)
            .sortedBy { it.timestamp }
            .toList()
        val reduced = ChargingChartSeries.downsample(plottable, MAX_POINTS)
        samples.clear()
        samples.addAll(reduced)
        firstTimestamp = reduced.firstOrNull()?.timestamp ?: 0L
        drawCircles = false
        render()
    }

    fun addSample(sample: MeterData) {
        if (!append(sample)) return
        render()
    }

    private fun append(sample: MeterData): Boolean {
        if (!ChargingChartSeries.isPlottable(sample)) return false
        val lastTs = samples.lastOrNull()?.timestamp ?: 0L
        if (sample.timestamp == lastTs) return false
        if (firstTimestamp == 0L) firstTimestamp = sample.timestamp
        samples.add(sample)
        if (samples.size > MAX_POINTS) {
            samples.removeAt(0)
            firstTimestamp = samples.first().timestamp
        }
        return true
    }

    private fun setupChart() {
        val axisColor = ThemeColors.onSurfaceVariant(context)
        val axisGridColor = ColorUtils.setAlphaComponent(axisColor, 40)

        lineChart.description.isEnabled = true
        lineChart.legend.isEnabled = false
        lineChart.setTouchEnabled(true)
        lineChart.isDragEnabled = true
        lineChart.setScaleEnabled(true)
        lineChart.setPinchZoom(true)
        lineChart.setDrawGridBackground(false)
        lineChart.setNoDataText(emptyMessage)
        lineChart.setNoDataTextColor(axisColor)
        lineChart.setBackgroundColor(Color.TRANSPARENT)
        lineChart.minOffset = 12f
        lineChart.extraLeftOffset = 10f
        lineChart.extraTopOffset = 10f
        lineChart.extraRightOffset = 8f
        lineChart.extraBottomOffset = 6f
        lineChart.clipToPadding = false
        lineChart.clipChildren = false

        lineChart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            textColor = axisColor
            textSize = 10f
            yOffset = 6f
            granularity = 1f
            valueFormatter = object : ValueFormatter() {
                private val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                override fun getFormattedValue(value: Float): String {
                    if (firstTimestamp == 0L) return ""
                    return format.format(Date(firstTimestamp + (value * 1000f).toLong()))
                }
            }
        }

        lineChart.axisLeft.apply {
            textColor = axisColor
            textSize = 10f
            setDrawGridLines(true)
            gridColor = axisGridColor
            setPosition(YAxis.YAxisLabelPosition.OUTSIDE_CHART)
            xOffset = 4f
            spaceTop = 16f
            spaceBottom = 8f
            setLabelCount(5, true)
        }
        lineChart.axisRight.isEnabled = false
        lineChart.description.apply {
            isEnabled = true
            textSize = 10f
            textColor = axisColor
        }
    }

    private fun render() {
        val points = ChargingChartSeries.points(samples, metric)
        if (points.isEmpty()) {
            lineChart.clear()
            lineChart.setNoDataText(emptyMessage)
            lineChart.invalidate()
            return
        }

        val origin = firstTimestamp.takeIf { it > 0 } ?: points.first().timestamp
        val entries = points.map { point ->
            Entry(((point.timestamp - origin) / 1000f).coerceAtLeast(0f), point.value)
        }

        val window = ChargeSessionLogic.yAxisWindow(points.map { it.value })
        lineChart.axisLeft.apply {
            axisMinimum = window.min
            axisMaximum = window.max
            granularity = ((window.max - window.min) / 4f).coerceAtLeast(0.01f)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    return String.format(Locale.getDefault(), "%.${window.decimals}f", value)
                }
            }
        }

        val lineColor = ThemeColors.primary(context)
        val dataSet = LineDataSet(entries, metric.tabLabel).apply {
            color = lineColor
            setCircleColor(lineColor)
            lineWidth = 2f
            circleRadius = 3f
            setDrawCircles(drawCircles)
            setDrawCircleHole(false)
            setDrawValues(false)
            // Cubic bezier overshoots a nearly-flat live series into a fake bowl.
            mode = LineDataSet.Mode.LINEAR
            setDrawFilled(true)
            fillColor = lineColor
            fillAlpha = 50
            setDrawHighlightIndicators(false)
        }

        lineChart.data = LineData(dataSet)
        lineChart.description.text = metric.axisLabel
        lineChart.notifyDataSetChanged()
        lineChart.invalidate()
    }

    companion object {
        private const val MAX_POINTS = 240
    }
}
