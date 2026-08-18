package com.drivool.iot.powertap

import android.graphics.Color
import android.os.Bundle
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChartActivity : AppCompatActivity() {

    private lateinit var lineChart: LineChart
    private lateinit var txtTitle: TextView
    private lateinit var cbVoltage: CheckBox
    private lateinit var cbCurrent: CheckBox
    private lateinit var cbEnergy: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chart)

        lineChart = findViewById(R.id.lineChart)
        txtTitle = findViewById(R.id.txtTitle)
        cbVoltage = findViewById(R.id.cbVoltage)
        cbCurrent = findViewById(R.id.cbCurrent)
        cbEnergy = findViewById(R.id.cbEnergy)

        val tid = intent.getStringExtra("TRANSACTION_ID") ?: return
        txtTitle.text = "Session: $tid"

        setupChart()
        loadData(tid)
    }

    private fun setupChart() {
        lineChart.description.isEnabled = false
        lineChart.setTouchEnabled(true)
        lineChart.isDragEnabled = true
        lineChart.setScaleEnabled(true)
        lineChart.setPinchZoom(true)
        lineChart.setDrawGridBackground(false)

        val xAxis = lineChart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.valueFormatter = object : ValueFormatter() {
            private val mFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            override fun getFormattedValue(value: Float): String {
                return mFormat.format(Date(value.toLong()))
            }
        }

        lineChart.axisRight.isEnabled = false
        lineChart.legend.isEnabled = false // We use our own CheckBoxes as legend
    }

    private fun loadData(tid: String) {
        val dataList = TransactionRepository.getMeterDataList(tid)
        if (dataList.isEmpty()) {
            lineChart.setNoDataText("No data available for this session")
            return
        }

        val voltageEntries = dataList.map { Entry(it.timestamp.toFloat(), it.voltage) }
        val currentEntries = dataList.map { Entry(it.timestamp.toFloat(), it.current) }
        val energyEntries = dataList.map { Entry(it.timestamp.toFloat(), it.energy) }

        val voltageSet = createDataSet(voltageEntries, "Voltage", Color.RED)
        val currentSet = createDataSet(currentEntries, "Current", Color.GREEN)
        val energySet = createDataSet(energyEntries, "Energy", Color.BLUE)

        val lineData = LineData(voltageSet, currentSet, energySet)
        lineChart.data = lineData

        cbVoltage.setOnCheckedChangeListener { _, isChecked ->
            voltageSet.isVisible = isChecked
            lineChart.invalidate()
        }
        cbCurrent.setOnCheckedChangeListener { _, isChecked ->
            currentSet.isVisible = isChecked
            lineChart.invalidate()
        }
        cbEnergy.setOnCheckedChangeListener { _, isChecked ->
            energySet.isVisible = isChecked
            lineChart.invalidate()
        }

        lineChart.invalidate()
    }

    private fun createDataSet(entries: List<Entry>, label: String, color: Int): LineDataSet {
        return LineDataSet(entries, label).apply {
            this.color = color
            setCircleColor(color)
            lineWidth = 2f
            circleRadius = 3f
            setDrawCircleHole(false)
            valueTextSize = 9f
            setDrawValues(false)
            highLightColor = Color.rgb(244, 117, 117)
        }
    }
}
