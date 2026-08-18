package com.drivool.iot.powertap

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drivool.iot.powertap.contract.ChargingSession
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private val adapter = HistoryAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_history, container, false)
        recyclerView = view.findViewById(R.id.history_recycler)
        emptyView = view.findViewById(R.id.empty_view)

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            TransactionRepository.sessions.collect { sessions ->
                if (sessions.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    adapter.submitList(sessions)
                }
            }
        }

        return view
    }

    class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
        private var sessions = emptyList<ChargingSession>()

        fun submitList(newSessions: List<ChargingSession>) {
            sessions = newSessions
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history_session, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(sessions[position])
        }

        override fun getItemCount() = sessions.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val txtDate: TextView = view.findViewById(R.id.txtDate)
            private val txtStatus: TextView = view.findViewById(R.id.txtStatus)
            private val txtTid: TextView = view.findViewById(R.id.txtTid)
            private val txtEnergy: TextView = view.findViewById(R.id.txtEnergy)
            private val txtDuration: TextView = view.findViewById(R.id.txtDuration)
            private val txtTimeStart: TextView = view.findViewById(R.id.txtTimeStart)
            private val txtTimeEnd: TextView = view.findViewById(R.id.txtTimeEnd)
            private val txtMeterStart: TextView = view.findViewById(R.id.txtMeterStart)
            private val txtMeterEnd: TextView = view.findViewById(R.id.txtMeterEnd)
            private val btnChart: ImageButton = view.findViewById(R.id.btnChart)

            private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

            fun bind(session: ChargingSession) {
                txtDate.text = dateFormat.format(Date(session.startTime))
                txtStatus.text = session.status.uppercase()
                txtStatus.setBackgroundResource(if (session.status == "Active") R.drawable.bg_status_active else R.drawable.bg_status_completed)
                txtTid.text = "ID: ${session.transactionId} (${session.deviceId})"
                
                val energyKwh = session.energyConsumed / 1000f
                txtEnergy.text = String.format(Locale.getDefault(), "%.3f kWh", energyKwh)
                
                txtTimeStart.text = "Started: ${timeFormat.format(Date(session.startTime))}"
                
                if (session.stopTime != null) {
                    txtTimeEnd.text = "Stopped: ${timeFormat.format(Date(session.stopTime!!))}"
                    
                    val durationMillis = session.stopTime!! - session.startTime
                    val minutes = (durationMillis / (1000 * 60)) % 60
                    val hours = (durationMillis / (1000 * 60 * 60))
                    txtDuration.text = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
                } else {
                    txtTimeEnd.text = "Stopped: ---"
                    txtDuration.text = "Charging..."
                }

                txtMeterStart.text = "Start: ${session.meterStart} Wh"
                txtMeterEnd.text = "End: ${session.meterStop ?: "---"} Wh"

                btnChart.setOnClickListener {
                    val intent = Intent(it.context, ChartActivity::class.java).apply {
                        putExtra("TRANSACTION_ID", session.transactionId)
                    }
                    it.context.startActivity(intent)
                }
            }
        }
    }
}
