package com.drivool.iot.powertap

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.drivool.iot.powertap.contract.ChargingSession
import com.google.android.material.appbar.MaterialToolbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChartActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chart)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val tid = intent.getStringExtra(EXTRA_TRANSACTION_ID)
        if (tid.isNullOrEmpty()) {
            finish()
            return
        }

        val session = TransactionRepository.sessions.value.firstOrNull { it.transactionId == tid }
        findViewById<TextView>(R.id.txtSubtitle).text = sessionSummary(session)

        val chart = findViewById<ChargingChartView>(R.id.chargingChart)
        chart.expandChart()
        chart.setEmptyMessage("No meter data saved for this session")
        chart.setSessionSamples(TransactionRepository.getMeterDataList(tid))
    }

    private fun sessionSummary(session: ChargingSession?): String {
        if (session == null) return "Session chart"
        val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        val energy = MeterUnits.formatEnergyWh(session.energyConsumed)
        val duration = session.stopTime?.let { MeterUnits.formatDuration(it - session.startTime) }
        return buildString {
            append(dateFormat.format(Date(session.startTime)))
            append("  ·  ")
            append(energy)
            if (duration != null) {
                append("  ·  ")
                append(duration)
            }
        }
    }

    companion object {
        const val EXTRA_TRANSACTION_ID = "TRANSACTION_ID"
    }
}
