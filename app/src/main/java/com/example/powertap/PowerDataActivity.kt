package com.drivool.iot.powertap

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.drivool.iot.powertap.contract.MeterData
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PowerDataActivity : AppCompatActivity() {

    private lateinit var voltageView: TextView
    private lateinit var currentView: TextView
    private lateinit var powerView: TextView
    private lateinit var energyView: TextView
    private lateinit var frequencyView: TextView
    private lateinit var historyAdapter: ArrayAdapter<MeterData>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        observeBleData()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        val title = TextView(this).apply {
            text = "LIVE METER DATA"
            textSize = 20f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        root.addView(title)

        // Real-time grid
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(24, 24, 24, 24)
            elevation = 4f
        }

        voltageView = dataRow(grid, "Voltage", "V")
        currentView = dataRow(grid, "Current", "A")
        powerView = dataRow(grid, "Power", "W")
        energyView = dataRow(grid, "Energy", "Wh")
        frequencyView = dataRow(grid, "Frequency", "Hz")

        root.addView(grid)

        val historyTitle = TextView(this).apply {
            text = "HISTORY"
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, 48, 0, 16)
        }
        root.addView(historyTitle)

        historyAdapter = object : ArrayAdapter<MeterData>(this, android.R.layout.simple_list_item_1, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val data = getItem(position)!!
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(data.timestamp))
                return TextView(context).apply {
                    setPadding(16, 16, 16, 16)
                    textSize = 12f
                    text = "$time -> V:${data.voltage} C:${data.current} P:${data.power} E:${data.energy} F:${data.frequency}"
                }
            }
        }

        val listView = ListView(this).apply {
            adapter = historyAdapter
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        root.addView(listView)

        return root
    }

    private fun dataRow(parent: LinearLayout, label: String, unit: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        row.addView(TextView(this).apply {
            text = "$label:"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setTextColor(Color.GRAY)
        })
        val valueView = TextView(this).apply {
            text = "--- $unit"
            setTextColor(Color.BLACK)
            textSize = 18f
        }
        row.addView(valueView)
        parent.addView(row)
        return valueView
    }

    private fun observeBleData() {
        lifecycleScope.launch {
            GatewayManager.latestMeterData.collect { data ->
                data?.let { updateUiValues(it) }
            }
        }
        lifecycleScope.launch {
            GatewayManager.meterHistory.collect { history ->
                historyAdapter.clear()
                historyAdapter.addAll(history)
            }
        }
    }

    private fun updateUiValues(data: MeterData) {
        runOnUiThread {
            voltageView.text = String.format(Locale.US, "%.2f V", data.voltage)
            currentView.text = String.format(Locale.US, "%.2f A", data.current)
            powerView.text = String.format(Locale.US, "%.2f W", data.power)
            energyView.text = String.format(Locale.US, "%.2f Wh", data.energy)
            frequencyView.text = String.format(Locale.US, "%.1f Hz", data.frequency)
        }
    }
}
